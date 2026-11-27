package dev.codespire.gateway.attention;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The gateway's attention socket asks for the roles {@code GET /gw/webhook-repos/attention} asks for (viewer or
 * admin). The {@code /gw/*} policy only asks for a signed-in identity, so before this an identity with neither role
 * received the attention rows over the socket while REST refused it them.
 */
@QuarkusTest
class WebhookAttentionSocketRolesTest {

    @TestHTTPResource("/gw/ws/webhook-attention")
    URI endpoint;

    @Test
    @TestSecurity(user = "TEST-norole", roles = "TEST-other")
    void anIdentityWithoutAViewerRoleIsRefused() {
        ExecutionException refused = assertThrows(ExecutionException.class, this::open,
                "the handshake must be refused");
        assertInstanceOf(WebSocketHandshakeException.class, refused.getCause());
    }

    @Test
    @TestSecurity(user = "TEST-viewer", roles = "spire-viewer")
    void aViewerOpensTheSocket() throws Exception {
        WebSocket socket = open();
        assertNotNull(socket);
        socket.abort();
    }

    @Test
    @TestSecurity(user = "TEST-admin", roles = "spire-admin")
    void anAdminOpensTheSocket() throws Exception {
        WebSocket socket = open();
        assertNotNull(socket);
        socket.abort();
    }

    private WebSocket open() throws Exception {
        URI uri = URI.create(endpoint.toString().replaceFirst("^http", "ws"));
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(uri, new WebSocket.Listener() { })
                .get(10, TimeUnit.SECONDS);
    }
}
