package dev.codespire.modelproxy;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static dev.codespire.modelproxy.RawSockets.rawStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which requests the proxy refuses before the vendor is contacted. */
class ModelProxyPathTest extends ProxyTestSupport {

    @Test
    void anUndeclaredPathIsRefusedWithoutReachingTheVendor() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/files")).GET());

        assertEquals(403, response.statusCode());
        assertEquals(0, hits.get());
        assertEquals(List.of("spire-model GET <refused> -> refused"), loggedLines());
    }

    @Test
    void aDotSegmentCannotEscapeAnAllowedPath() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        for (String target : List.of("/v1/responses/../files", "/v1/responses%2F..%2Ffiles", "/v1/%2e%2e/files")) {
            assertEquals(403, rawStatus(proxy, "GET " + target + " HTTP/1.1\r\nHost: model\r\n"), target);
        }
        // The JDK server itself may answer these two 400 before any handler runs (a leading // was measured at 400
        // in CI on JDK 25; a backslash is a malformed URI to it). The proxy's own guard refuses both too
        // (PathGuardTest); here what matters is that neither reaches the vendor, whichever layer refuses it.
        for (String target : List.of("//v1/responses", "/v1\\responses")) {
            int status = rawStatus(proxy, "GET " + target + " HTTP/1.1\r\nHost: model\r\n");
            assertTrue(status == 400 || status == 403, target + " got " + status);
        }
        assertEquals(0, hits.get());
    }

    /**
     * The discriminating half: on a prefix route every one of these starts with an allowed prefix, so only the guard's
     * check of the raw path stands between it and the vendor.
     */
    @Test
    void aDotSegmentCannotEscapeAnAllowedPrefix() throws Exception {
        ModelProxy proxy = start(seatRoute(upstreamOrigin()));

        for (String target : List.of("/backend-api/codex/../../v1/files", "/backend-api/codex/%2e%2e/x",
                "/backend-api/codex/%2F..%2Fx", "/backend-api/codex//x")) {
            assertEquals(403, rawStatus(proxy, "GET " + target + " HTTP/1.1\r\nHost: model\r\n"), target);
        }
        assertEquals(0, hits.get());
    }

    @Test
    void anAbsoluteFormTargetIsRefused() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        int status = rawStatus(proxy, "GET http://other.example/v1/responses HTTP/1.1\r\nHost: other.example\r\n");

        assertEquals(403, status);
        assertEquals(0, hits.get());
    }

    @Test
    void aConnectIsRefused() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        int status = rawStatus(proxy, "CONNECT api.openai.com:443 HTTP/1.1\r\nHost: api.openai.com:443\r\n");

        // Measured in CI on JDK 25: the JDK server closes the connection without an answer (-1) before any handler
        // runs; the target has no path for it to match a context with. The handler's own 501 for CONNECT stays as a
        // second line in case a later JDK lets the request through.
        assertTrue(status == RawSockets.CLOSED || status == 501, "CONNECT got " + status);
        assertEquals(0, hits.get());
    }

    @Test
    void anApiKeyRouteMatchesExactly() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        assertEquals(403, send(HttpRequest.newBuilder(at(proxy, "/v1/responses/extra")).GET()).statusCode());
        assertEquals(0, hits.get());

        HttpResponse<String> forwarded = post(proxy, "/v1/responses?x=1");

        assertEquals(200, forwarded.statusCode());
        assertEquals("TEST-ok", forwarded.body());
        assertEquals(1, hits.get());
        assertEquals("x=1", seenQuery);
    }

    @Test
    void aWebSocketUpgradeIsRefused() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        int status = rawStatus(proxy, "GET /v1/responses HTTP/1.1\r\nHost: model\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n");

        assertEquals(501, status);
        assertEquals(0, hits.get());
        assertEquals(List.of("spire-model GET <refused> -> refused"), loggedLines());
    }

    @Test
    void aMethodOtherThanGetOrPostIsRefused() throws Exception {
        ModelProxy proxy = start(apiKeyRoute(upstreamOrigin()));

        HttpResponse<String> response = send(HttpRequest.newBuilder(at(proxy, "/v1/responses"))
                .method("DELETE", HttpRequest.BodyPublishers.noBody()));

        assertEquals(405, response.statusCode());
        assertEquals(0, hits.get());
    }
}
