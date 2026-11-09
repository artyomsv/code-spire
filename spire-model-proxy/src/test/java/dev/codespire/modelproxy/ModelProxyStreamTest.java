package dev.codespire.modelproxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static dev.codespire.modelproxy.RawSockets.readEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A streamed answer reaches the agent as it arrives, and a broken one reaches it broken. */
class ModelProxyStreamTest extends ProxyTestSupport {

    @Test
    void aStreamIsPassedOnAsItArrives() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        behaviour = exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            writeAndFlush(body, "data: TEST-1\n\n");
            await(release);
            body.write("data: TEST-2\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        InputStream body = null;
        try {
            body = firstEventWhileHeld(proxy);
        } finally {
            release.countDown();
        }
        try (InputStream rest = body) {
            assertEquals("data: TEST-2\n\n", new String(rest.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * The response and its first event are both awaited while the vendor still holds the stream: a proxy that buffers
     * (its headers included, which the JDK server sends only on a flush) delivers neither until the vendor's release,
     * so each bound here, shorter than the vendor's wait, fails it.
     */
    private InputStream firstEventWhileHeld(ModelProxy proxy) throws Exception {
        CompletableFuture<HttpResponse<InputStream>> sent = client.sendAsync(
                HttpRequest.newBuilder(at(proxy, "/v1/responses")).POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = awaitWhileHeld(sent, "the response").body();
        assertEquals("data: TEST-1\n\n",
                awaitWhileHeld(CompletableFuture.supplyAsync(() -> readEvent(body)), "the first event"));
        return body;
    }

    /**
     * A vendor that dies mid-stream must reach the agent as a broken response. Ending the chunked body cleanly would
     * hand the agent a truncated answer that reads as complete.
     */
    @Test
    void aMidStreamResetIsNotAcleanEnd() throws Exception {
        CountDownLatch firstEventRead = new CountDownLatch(1);
        try (ServerSocket vendor = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> RawSockets.oneEventThenReset(vendor, firstEventRead));
            ModelProxy proxy = start(apiKeyRoute("http://127.0.0.1:" + vendor.getLocalPort()));

            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream body = response.body()) {
                assertEquals("data: TEST-1\n\n", readEvent(body));
                firstEventRead.countDown();

                assertThrows(IOException.class, body::readAllBytes,
                        "the agent must see the stream break, not a clean end of a truncated body");
            }
            assertEquals(List.of("spire-model POST /v1/responses -> aborted"), loggedLines());
        }
    }

    static <T> T awaitWhileHeld(CompletableFuture<T> pending, String what) throws Exception {
        try {
            return pending.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException held) {
            throw new AssertionError(what + " must arrive while the vendor is still holding the stream open", held);
        }
    }

    static void writeAndFlush(OutputStream body, String text) throws IOException {
        body.write(text.getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
