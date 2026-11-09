package dev.codespire.modelproxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The report line, {@code spire-model <METHOD> <route> -> <outcome>}: the route the guard matched (an exact path, or a
 * prefix and {@code *}), or {@code <refused>}. Never the agent's path, query, header or body, and never an exception.
 */
class ModelProxyLogTest extends ProxyTestSupport {

    @Test
    void theLogNamesTheRouteAndNoSecretOrQuery() throws Exception {
        ModelProxy apiKey = start(apiKeyRoute(upstreamOrigin()));
        ModelProxy seat = start(seatRoute(upstreamOrigin()));

        assertEquals(200, post(apiKey, "/v1/responses?key=TEST-q").statusCode());
        assertEquals(200, post(seat, "/backend-api/codex/responses?key=TEST-q").statusCode());

        assertEquals(List.of("spire-model POST /v1/responses -> 200",
                "spire-model POST /backend-api/codex/responses -> 200"), loggedLines());
        for (String secret : List.of(API_SECRET, SEAT_SECRET, SEAT_ACCOUNT, "TEST-q")) {
            assertFalse(logged().contains(secret), "the log carries " + secret + ": " + logged());
        }
    }

    @Test
    void aFailedVendorCallIsOneLineWithNothingOfTheException() throws Exception {
        try (ServerSocket resetting = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> RawSockets.resetEveryConnection(resetting));
            ModelProxy broken = start(apiKeyRoute("http://127.0.0.1:" + resetting.getLocalPort()));

            HttpResponse<String> response = post(broken, "/v1/responses");

            assertEquals(502, response.statusCode());
            assertEquals(List.of("spire-model POST /v1/responses -> 502"), loggedLines(),
                    "one line with the status and nothing of the exception");
        }
    }

    /** An allowed suffix is the agent's to choose, and may name a person: only the prefix it matched is logged. */
    @Test
    void anIdentityBearingSuffixIsLoggedAsItsPrefix() throws Exception {
        ModelProxy seat = start(seatRoute(upstreamOrigin()));
        String path = "/backend-api/wham/users/TEST-jane.doe-example.com/TEST-repository-token";

        assertEquals(200, send(HttpRequest.newBuilder(at(seat, path)).GET()).statusCode());

        assertEquals(List.of("spire-model GET /backend-api/wham/* -> 200"), loggedLines());
        assertEquals(path, seenPath, "the vendor still gets the path the agent sent");
    }

    @Test
    void aLongAllowedPathIsForwardedWholeAndLoggedAsItsPrefix() throws Exception {
        ModelProxy seat = start(seatRoute(upstreamOrigin()));
        String path = "/backend-api/codex/" + "a".repeat(4000);

        assertEquals(200, send(HttpRequest.newBuilder(at(seat, path)).GET()).statusCode());

        assertEquals(List.of("spire-model GET /backend-api/codex/* -> 200"), loggedLines());
        assertEquals(path, seenPath, "the forwarded target is never cut");
    }

    @Test
    void aRefusedPathIsLoggedAsAFixedToken() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        assertEquals(403, send(HttpRequest.newBuilder(
                at(proxy, "/v1/users/TEST-jane.doe/TEST-repository-token?x=TEST-q")).GET()).statusCode());
        assertEquals(403, RawSockets.rawStatus(proxy,
                "GET http://TEST-user:TEST-pass@other.example/TEST-path HTTP/1.1\r\nHost: other.example\r\n"));

        assertEquals(List.of("spire-model GET <refused> -> refused", "spire-model GET <refused> -> refused"),
                loggedLines());
        assertEquals(0, hits.get());
    }

    /** A method is the one thing of the agent's the line keeps, so it is reduced to visible ASCII and bounded. */
    @Test
    void aForgedMethodCannotForgeOrFloodALine() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ModelProxy proxy = start(ProxyStart.of(apiKeyRoute(upstreamOrigin()), new InetSocketAddress("127.0.0.1", 0),
                new PrintStream(bytes, true, StandardCharsets.UTF_8)));

        RawSockets.rawStatus(proxy, "X".repeat(300) + " /v1/responses HTTP/1.1\r\nHost: model\r\n");

        List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(List.of("spire-model " + "X".repeat(16) + " <refused> -> refused"), lines);
    }
}
