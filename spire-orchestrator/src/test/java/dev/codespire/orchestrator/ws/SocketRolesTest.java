package dev.codespire.orchestrator.ws;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every dashboard socket asks for the roles its REST reads ask for (viewer or admin). The {@code /api/*} policy only
 * asks for a signed-in identity, so before this an identity with neither role received the reviews list, the runs,
 * the attention rows, the timeline and run transcripts over a socket while REST refused it the same data.
 */
@QuarkusTest
class SocketRolesTest {

    @TestHTTPResource("/")
    URI root;

    /** Each socket's path; the transcript names a run in the query, which a refused handshake never reads. */
    private static final List<String> SOCKETS = List.of(
            "/api/ws/reviews",
            "/api/ws/runs",
            "/api/ws/attention",
            "/api/ws/timeline",
            RunTranscriptSocket.PATH + "?" + RunTranscriptSocket.RUN_ID_PARAM + "=TEST-ws%2FTEST-run");

    @Test
    @TestSecurity(user = "TEST-norole", roles = "TEST-other")
    void anIdentityWithoutAViewerRoleIsRefusedEverySocket() {
        for (String path : SOCKETS) {
            ExecutionException refused = assertThrows(ExecutionException.class, () -> open(path),
                    path + " must refuse the handshake");
            assertInstanceOf(WebSocketHandshakeException.class, refused.getCause(), path);
        }
    }

    @Test
    @TestSecurity(user = "TEST-viewer", roles = "spire-viewer")
    void aViewerOpensEverySocket() throws Exception {
        for (String path : SOCKETS) {
            WebSocket socket = open(path);
            assertNotNull(socket, path);
            socket.abort();
        }
    }

    @Test
    @TestSecurity(user = "TEST-admin", roles = "spire-admin")
    void anAdminOpensEverySocket() throws Exception {
        for (String path : SOCKETS) {
            WebSocket socket = open(path);
            assertNotNull(socket, path);
            socket.abort();
        }
    }

    private WebSocket open(String path) throws Exception {
        URI uri = URI.create(root.toString().replaceFirst("^http", "ws").replaceFirst("/$", "") + path);
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(uri, new WebSocket.Listener() { })
                .get(10, TimeUnit.SECONDS);
    }
}
