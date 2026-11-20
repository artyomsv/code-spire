package dev.codespire.orchestrator.llm.catalog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.codespire.llmcatalog.CatalogLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fetcher against a JDK HttpServer on loopback. Plain HTTP on purpose: the https rule lives in
 * {@link LlmCatalogRefresh}, not here, and what this class decides (redirects, the body cap, 304, the
 * failure text) is the same over either scheme. The client is the production builder's.
 */
class CatalogFetcherTest {

    private static final String BODY = "providers: []\nmodels: []\n";

    private HttpServer server;
    private final CountDownLatch released = new CountDownLatch(1);
    private final CountDownLatch endlessStopped = new CountDownLatch(1);
    private final AtomicReference<String> seenIfNoneMatch = new AtomicReference<>();
    private final CatalogFetcher fetcher = new CatalogFetcher(CatalogFetcher.clientBuilder().build());

    /** What a context answers: a status, a body and an optional ETag. */
    private record Reply(int status, byte[] body, String etag) {
        static Reply of(int status, String body) {
            return new Reply(status, body.getBytes(StandardCharsets.UTF_8), null);
        }
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/catalog", this::catalog);
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/catalog");
            respond(exchange, Reply.of(302, ""));
        });
        server.createContext("/large", exchange ->
                respond(exchange, new Reply(200, new byte[CatalogLimits.MAX_BYTES + 1], null)));
        server.createContext("/broken", exchange -> respond(exchange, Reply.of(500, "SECRET-BODY")));
        server.createContext("/stall", this::stall);
        server.createContext("/endless", this::endless);
        server.start();
    }

    private void catalog(HttpExchange exchange) throws IOException {
        seenIfNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
        if ("\"TEST-etag\"".equals(seenIfNoneMatch.get())) {
            respond(exchange, Reply.of(304, ""));
        } else {
            respond(exchange, new Reply(200, BODY.getBytes(StandardCharsets.UTF_8), "\"TEST-etag\""));
        }
    }

    /** Headers and a first chunk, then nothing: the body never ends until the test is over. */
    private void stall(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 1000);
        OutputStream out = exchange.getResponseBody();
        out.write("providers: []\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        try {
            released.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        exchange.close();
    }

    /** A chunked body that never ends: writes until the client stops reading and the connection fails. */
    private void endless(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        byte[] chunk = new byte[64 * 1024];
        try (OutputStream out = exchange.getResponseBody()) {
            while (released.getCount() > 0) {
                out.write(chunk);
                out.flush();
            }
        } catch (IOException clientGone) {
            endlessStopped.countDown();
        }
    }

    @AfterEach
    void stopServer() {
        released.countDown();
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, Reply reply) throws IOException {
        if (reply.etag() != null) {
            exchange.getResponseHeaders().add("ETag", reply.etag());
        }
        exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(reply.body());
        }
    }

    private URI at(String path) {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path);
    }

    @Test
    void aBodyIsFetchedWithItsEtag() {
        CatalogFetcher.Fetched fetched = assertInstanceOf(CatalogFetcher.Fetched.class, fetcher.fetch(at("/catalog"), null));

        assertEquals(BODY, fetched.body());
        assertEquals("\"TEST-etag\"", fetched.etag());
    }

    @Test
    void aRedirectIsAFailure() {
        CatalogFetcher.Failed failed = assertInstanceOf(CatalogFetcher.Failed.class, fetcher.fetch(at("/moved"), null));

        assertEquals("http_302", failed.reason());
    }

    /** HttpRequest.timeout ends at the headers; the fetcher's deadline covers the body as well. */
    @Test
    void aBodyThatStallsAfterTheHeadersFailsAtTheDeadline() {
        CatalogFetcher quick = new CatalogFetcher(CatalogFetcher.clientBuilder().build(), Duration.ofMillis(300));
        long started = System.nanoTime();

        CatalogFetcher.Failed failed = assertInstanceOf(CatalogFetcher.Failed.class, quick.fetch(at("/stall"), null));

        assertEquals("TimeoutException", failed.reason());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                "the read waited past its deadline");
    }

    /**
     * The client's policy, stated. Deleting {@code followRedirects(NEVER)} changes nothing, since NEVER is the
     * JDK default; what refuses a redirect is the fetcher's own non-2xx check, which aRedirectIsAFailure pairs.
     */
    @Test
    void theProductionClientFollowsNoRedirect() {
        assertEquals(java.net.http.HttpClient.Redirect.NEVER, CatalogFetcher.clientBuilder().build().followRedirects());
    }

    @Test
    void aBodyOverTheCapIsAFailure() {
        assertInstanceOf(CatalogFetcher.Failed.class, fetcher.fetch(at("/large"), null));
    }

    /**
     * The cap stops the read, not the deadline: a body that never ends fails as over the cap, long before the
     * deadline, and the client stops consuming it (the server's write fails). A read that buffered the whole body
     * before checking its size would wait for the deadline and fail as a timeout instead.
     */
    @Test
    void anEndlessBodyIsCutAtTheCapNotAtTheDeadline() throws InterruptedException {
        CatalogFetcher patient = new CatalogFetcher(CatalogFetcher.clientBuilder().build(), Duration.ofSeconds(60));

        CatalogFetcher.Failed failed = assertInstanceOf(CatalogFetcher.Failed.class, patient.fetch(at("/endless"), null));

        assertEquals("body_over_" + CatalogLimits.MAX_BYTES + "_bytes", failed.reason());
        assertTrue(endlessStopped.await(30, TimeUnit.SECONDS), "the client stopped reading the endless body");
    }

    @Test
    void notModifiedWhenTheEtagStillMatches() {
        assertInstanceOf(CatalogFetcher.NotModified.class, fetcher.fetch(at("/catalog"), "\"TEST-etag\""));
        assertEquals("\"TEST-etag\"", seenIfNoneMatch.get());
    }

    @Test
    void theFailureReasonNeverContainsTheBody() {
        CatalogFetcher.Failed failed = assertInstanceOf(CatalogFetcher.Failed.class, fetcher.fetch(at("/broken"), null));

        assertEquals("http_500", failed.reason());
        assertFalse(failed.reason().contains("SECRET-BODY"));
    }

    @Test
    void anUnreachableUrlIsAFailureNamedByItsExceptionClass() throws IOException {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }

        CatalogFetcher.Failed failed = assertInstanceOf(CatalogFetcher.Failed.class,
                fetcher.fetch(URI.create("http://127.0.0.1:" + port + "/catalog"), null));

        assertFalse(failed.reason().isBlank());
    }
}
