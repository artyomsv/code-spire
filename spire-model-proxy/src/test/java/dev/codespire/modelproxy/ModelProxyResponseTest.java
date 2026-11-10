package dev.codespire.modelproxy;

import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What of the vendor's answer reaches the agent: allowed headers only, never a {@code Location}, and no echo of the
 * credential or the seat's account in a header or a body, whatever the status. A refused credential's body is fixed.
 */
class ModelProxyResponseTest extends ProxyTestSupport {

    @Test
    void aRefusedCredentialsBodyAndEchoingHeadersAreReplaced() throws Exception {
        behaviour = exchange -> {
            Headers headers = exchange.getResponseHeaders();
            headers.set("WWW-Authenticate", "Bearer realm=\"TEST\"");
            headers.set("Set-Cookie", "TEST-cookie=1");
            headers.set("X-Echo", "Bearer " + API_SECRET);
            headers.set("Authorization", "Bearer " + API_SECRET);
            respond(exchange, 401, "Incorrect API key provided: " + API_SECRET);
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<String> response = post(proxy, "/v1/responses");

        assertEquals(401, response.statusCode());
        assertEquals(REFUSED_CREDENTIAL_BODY, response.body());
        assertEquals(Set.of("content-type"), visibleHeaders(response));
        assertEquals("application/json", response.headers().firstValue("content-type").orElseThrow());
        assertEquals(List.of("spire-model POST /v1/responses -> 401"), loggedLines());
    }

    @Test
    void a400BodyEchoingTheKeyIsMasked() throws Exception {
        behaviour = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            respond(exchange, 400, "{\"error\":\"bad header: Bearer " + API_SECRET + "\"}");
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<String> response = post(proxy, "/v1/responses");

        assertEquals(400, response.statusCode());
        assertEquals("{\"error\":\"bad header: Bearer " + WITHHELD + "\"}", response.body());
        assertEquals(Set.of("content-type"), visibleHeaders(response));
    }

    @Test
    void a500BodyEchoingTheSeatsTokenAndAccountIsMasked() throws Exception {
        behaviour = exchange -> respond(exchange, 500,
                "trace: authorization=Bearer " + SEAT_SECRET + " chatgpt-account-id=" + SEAT_ACCOUNT + " end");
        ModelProxy proxy = start(seatRoute(upstreamOrigin()));

        HttpResponse<String> response = post(proxy, "/backend-api/codex/responses");

        assertEquals(500, response.statusCode());
        assertEquals("trace: authorization=Bearer " + WITHHELD + " chatgpt-account-id=" + WITHHELD + " end",
                response.body());
    }

    @Test
    void aRedirectsLocationNeverReachesTheAgent() throws Exception {
        behaviour = exchange -> {
            exchange.getResponseHeaders().set("Location", "https://elsewhere.example/cb?key=" + API_SECRET);
            respond(exchange, 302, "");
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<String> response = post(proxy, "/v1/responses");

        assertEquals(302, response.statusCode());
        assertEquals(Set.of(), visibleHeaders(response));
        assertEquals("", response.body());
    }

    /** Only the named headers pass, and a named one is dropped when its value carries the credential. */
    @Test
    void onlyAllowedHeadersWithoutTheCredentialPass() throws Exception {
        behaviour = exchange -> {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream");
            headers.set("X-Request-Id", "TEST-request-1");
            headers.set("X-Codex-Primary-Used-Percent", "12");
            headers.set("X-Ratelimit-Remaining-Requests", "99");
            headers.set("Openai-Processing-Ms", "TEST-" + API_SECRET);
            headers.set("X-Debug", "TEST-debug");
            headers.set("Server", "TEST-server");
            respond(exchange, 200, "data: TEST-1\n\n");
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<String> response = post(proxy, "/v1/responses");

        assertEquals(Set.of("content-type", "x-request-id", "x-codex-primary-used-percent",
                "x-ratelimit-remaining-requests"), visibleHeaders(response));
        assertEquals("data: TEST-1\n\n", response.body());
        assertFalse(response.headers().map().toString().contains(API_SECRET));
    }

    /**
     * An echo split across two chunks is still caught, and the stream is not held for it: what precedes the split
     * reaches the agent while the vendor still holds the rest, and only the bytes that may begin the credential wait.
     */
    @Test
    void aStreamedEchoSplitAcrossChunksIsMasked() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        behaviour = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            ModelProxyStreamTest.writeAndFlush(body, "data: Bearer " + API_SECRET.substring(0, 8));
            ModelProxyStreamTest.await(release);
            ModelProxyStreamTest.writeAndFlush(body, API_SECRET.substring(8) + " done\n\n");
            exchange.close();
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            byte[] head = new byte["data: Bearer ".length()];
            try {
                ModelProxyStreamTest.awaitWhileHeld(CompletableFuture.supplyAsync(() -> readFully(body, head)),
                        "the bytes before the split");
            } finally {
                release.countDown();
            }
            assertEquals("data: Bearer ", new String(head, StandardCharsets.UTF_8));
            assertEquals(WITHHELD + " done\n\n", new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static byte[] readFully(InputStream in, byte[] into) {
        try {
            int read = in.readNBytes(into, 0, into.length);
            assertEquals(into.length, read);
            return into;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A body the proxy cannot read as it is (compressed) cannot be checked, so it is refused. */
    @Test
    void anEncodedBodyIsRefused() throws Exception {
        behaviour = exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            respond(exchange, 200, "TEST-not-really-gzip " + API_SECRET);
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<String> response = post(proxy, "/v1/responses");

        assertEquals(502, response.statusCode());
        assertEquals("", response.body());
        assertEquals(Set.of(), visibleHeaders(response));
        assertEquals(List.of("spire-model POST /v1/responses -> 502"), loggedLines());
    }
}
