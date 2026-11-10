package dev.codespire.modelproxy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * How long the vendor may take: to send its response headers, between two bytes of the body, and for the whole body.
 * Each bound that expires breaks the agent's response (or answers 502 before one began) and frees what it held.
 */
class ModelProxyDeadlineTest extends ProxyTestSupport {

    /** One second for headers and between bytes, two for a whole response. */
    private static final Limits SHORT = new Limits(8, 1024 * 1024, Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofSeconds(2));

    @Test
    void aVendorThatSendsNoHeadersIsABadGateway() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        behaviour = exchange -> {
            ModelProxyStreamTest.await(release);
            respond(exchange, 200, "TEST-late");
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), SHORT);

        try {
            HttpResponse<String> response = post(proxy, "/v1/responses");

            assertEquals(502, response.statusCode());
            assertEquals(List.of("spire-model POST /v1/responses -> 502"), loggedLines());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aStalledStreamIsBrokenAfterTheIdleBound() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        behaviour = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            ModelProxyStreamTest.writeAndFlush(exchange.getResponseBody(), "data: TEST-1\n\n");
            ModelProxyStreamTest.await(release);
            exchange.close();
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), SHORT);

        try (InputStream body = stream(proxy)) {
            assertEquals("data: TEST-1\n\n", RawSockets.readEvent(body));
            assertThrows(IOException.class, body::readAllBytes, "a stalled vendor must break the agent's stream");
        } finally {
            release.countDown();
        }
        assertEquals(List.of("spire-model POST /v1/responses -> aborted"), loggedLines());
    }

    @Test
    void aTricklingStreamIsBrokenAfterTheTotalBound() throws Exception {
        CountDownLatch stop = new CountDownLatch(1);
        behaviour = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            trickle(exchange.getResponseBody(), stop);
            exchange.close();
        };
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()), SHORT);

        try (InputStream body = stream(proxy)) {
            assertThrows(IOException.class, body::readAllBytes,
                    "a vendor that never stops sending must still be cut at the total bound");
        } finally {
            stop.countDown();
        }
        assertEquals(List.of("spire-model POST /v1/responses -> aborted"), loggedLines());
    }

    private InputStream stream(ModelProxy proxy) throws Exception {
        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    /** One byte every 100 ms for up to ten seconds: never idle for a second, always past two in all. */
    private static void trickle(OutputStream body, CountDownLatch stop) throws IOException {
        try {
            for (int sent = 0; sent < 100 && !stop.await(100, TimeUnit.MILLISECONDS); sent++) {
                ModelProxyStreamTest.writeAndFlush(body, "a");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
