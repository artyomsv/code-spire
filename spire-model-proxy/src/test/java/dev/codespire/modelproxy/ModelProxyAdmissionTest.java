package dev.codespire.modelproxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an agent's request may cost before the vendor is contacted: how many at once, how large a body, and how long
 * its head and body may take to arrive. The JDK server's own bounds (the 30-second request time, measured here at the
 * test JVM's 2 seconds) are {@link ServerLimits}'s; the rest is {@link Limits}'.
 */
class ModelProxyAdmissionTest extends ProxyTestSupport {

    /** Small enough to exhaust: two requests at once, a 1 KiB body. */
    private static final Limits SMALL = new Limits(2, 1024, Duration.ofSeconds(10), Duration.ofSeconds(10),
            Duration.ofSeconds(30));

    private static final String HEAD = "POST /v1/responses HTTP/1.1\r\nHost: model\r\n";

    @Test
    void aDeclaredBodyOverTheCapIsRefusedBeforeItIsRead() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), SMALL);

        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            socket.setSoTimeout(10_000);
            RawSockets.write(socket, HEAD + "Content-Length: 1025\r\n\r\n");

            assertEquals(413, RawSockets.statusOf(socket), "answered without a byte of the body sent");
        }
        assertEquals(0, hits.get());
        assertEquals(List.of("spire-model POST /v1/responses -> 413"), loggedLines());
    }

    @Test
    void aChunkedBodyOverTheCapIsRefusedAtTheFirstByteOver() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), SMALL);

        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            socket.setSoTimeout(10_000);
            String chunk = "a".repeat(512);
            RawSockets.write(socket, HEAD + "Transfer-Encoding: chunked\r\n\r\n"
                    + "200\r\n" + chunk + "\r\n200\r\n" + chunk + "\r\n1\r\nb\r\n0\r\n\r\n");

            assertEquals(413, RawSockets.statusOf(socket));
        }
        assertEquals(0, hits.get());
        assertEquals(List.of("spire-model POST /v1/responses -> 413"), loggedLines());
    }

    @Test
    void aBodyAtTheCapIsForwarded() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), SMALL);

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                .POST(HttpRequest.BodyPublishers.ofString("a".repeat(1024))));

        assertEquals(200, response.statusCode());
        assertEquals(1024, seenBody.length);
    }

    @Test
    void anIncompleteHeadIsClosed() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            RawSockets.write(socket, HEAD);

            assertTrue(RawSockets.closesWithin(socket, 8), "a head that never ends must not hold its connection");
        }
        assertEquals(0, hits.get());
        assertEquals(List.of(), loggedLines());
    }

    @Test
    void aStalledBodyIsClosedWithoutReachingTheVendor() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            RawSockets.write(socket, HEAD + "Content-Length: 100\r\n\r\n" + "a".repeat(10));

            assertTrue(RawSockets.closesWithin(socket, 8), "a body that stops must not hold its connection");
        }
        assertEquals(0, hits.get());
        assertEquals(List.of("spire-model POST /v1/responses -> aborted"), awaitLines(1));
    }

    @Test
    void aTricklingBodyIsClosedWithoutReachingTheVendor() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            RawSockets.write(socket, HEAD + "Content-Length: 1000\r\n\r\n");
            Thread trickle = Thread.ofVirtual().start(() -> trickle(socket));

            assertTrue(RawSockets.closesWithin(socket, 8), "a body that never stops arriving must still end");
            trickle.interrupt();
        }
        assertEquals(0, hits.get());
        assertEquals(List.of("spire-model POST /v1/responses -> aborted"), awaitLines(1));
    }

    @Test
    void aRequestOverTheConcurrencyIsRefusedAndCapacityReturns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        behaviour = exchange -> {
            ModelProxyStreamTest.await(release);
            respond(exchange, 200, "TEST-ok");
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), SMALL);
        List<CompletableFuture<HttpResponse<String>>> held = List.of(postAsync(proxy), postAsync(proxy));
        awaitHits(2);

        HttpResponse<String> refused = post(proxy, "/v1/responses");
        release.countDown();

        assertEquals(503, refused.statusCode());
        assertEquals("{\"error\":{\"message\":\"spire-model: too many requests at once\"}}", refused.body());
        for (CompletableFuture<HttpResponse<String>> answer : held) {
            assertEquals(200, answer.get().statusCode());
        }
        assertEquals(200, post(proxy, "/v1/responses").statusCode(), "capacity returns once a request ends");
        assertEquals(3, hits.get(), "the refused request never reached the vendor");
    }

    private CompletableFuture<HttpResponse<String>> postAsync(ModelProxy proxy) {
        return client.sendAsync(HttpRequest.newBuilder(at(proxy, "/v1/responses")).timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitHits(int expected) throws InterruptedException {
        for (int waited = 0; hits.get() < expected && waited < 100; waited++) {
            Thread.sleep(100);
        }
        assertEquals(expected, hits.get(), "the vendor must be holding the first requests");
    }

    /** The log, once it holds {@code count} lines or five seconds have passed: the handler writes after the close. */
    private List<String> awaitLines(int count) throws InterruptedException {
        for (int waited = 0; loggedLines().size() < count && waited < 50; waited++) {
            Thread.sleep(100);
        }
        return loggedLines();
    }

    /** One byte every 200 ms, for longer than any bound; it stops at the first refused write. */
    private static void trickle(Socket socket) {
        try {
            for (int sent = 0; sent < 60; sent++) {
                RawSockets.write(socket, "a");
                Thread.sleep(200);
            }
        } catch (IOException | InterruptedException stopped) {
            // The proxy closed the connection, or the test is over.
        }
    }
}
