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
    private final AtomicReference<String> seenIfNoneMatch = new AtomicReference<>();
    private final CatalogFetcher fetcher = new CatalogFetcher(CatalogFetcher.clientBuilder().build());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/catalog", exchange -> {
            seenIfNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            if ("\"TEST-etag\"".equals(seenIfNoneMatch.get())) {
                respond(exchange, 304, new byte[0], null);
            } else {
                respond(exchange, 200, BODY.getBytes(StandardCharsets.UTF_8), "\"TEST-etag\"");
            }
        });
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/catalog");
            respond(exchange, 302, new byte[0], null);
        });
        server.createContext("/large", exchange ->
                respond(exchange, 200, new byte[CatalogLimits.MAX_BYTES + 1], null));
        server.createContext("/broken", exchange ->
                respond(exchange, 500, "SECRET-BODY".getBytes(StandardCharsets.UTF_8), null));
        server.createContext("/stall", exchange -> {
            // Headers and a first chunk, then nothing: the body never ends until the test is over.
            exchange.sendResponseHeaders(200, 1000);
            OutputStream out = exchange.getResponseBody();
            out.write("providers: []
".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                released.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        released.countDown();
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, String etag) throws IOException {
        if (etag != null) {
            exchange.getResponseHeaders().add("ETag", etag);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
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

    @Test
    void aBodyOverTheCapIsAFailure() {
        assertInstanceOf(CatalogFetcher.Failed.class, fetcher.fetch(at("/large"), null));
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
