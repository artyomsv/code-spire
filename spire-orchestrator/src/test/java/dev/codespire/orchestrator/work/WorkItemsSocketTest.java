package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real HTTP upgrade and real frames, end to end from a committed write through the application's own listener (spec §4.3).
 * Item ids and the moment an item changes are data, so the socket asks for the same roles as the work-item reads.
 */
@QuarkusTest
class WorkItemsSocketTest extends WorkFixture {

    @Inject WorkItemsBroadcaster broadcaster;

    @TestHTTPResource("/api/ws/work-items")
    URI endpoint;

    @Test
    @TestSecurity(user = "TEST-viewer", roles = "spire-viewer")
    void aViewerReceivesNoticesAndNothingOnOpen() throws Exception {
        intake.accept(signed("900123"));
        settleSetupNotices();
        try (Feed feed = open()) {
            assertNull(feed.frames.poll(300, TimeUnit.MILLISECONDS), "the socket must send nothing on open");
            executeWith("UPDATE work_item SET reason=? WHERE id=?", "TEST-socket-" + UUID.randomUUID(), itemId);
            JsonNode frame = feed.next();
            assertEquals(mapper.createObjectNode().set("changed", mapper.createArrayNode().add(itemId)), frame);
        }
    }

    @Test
    @TestSecurity(user = "TEST-admin", roles = "spire-admin")
    void anAdminReceivesNotices() throws Exception {
        intake.accept(signed("900123"));
        settleSetupNotices();
        try (Feed feed = open()) {
            broadcaster.refreshAll();
            assertEquals(mapper.createObjectNode().put("changed", "*"), feed.next());
        }
    }

    @Test
    @TestSecurity(user = "TEST-norole", roles = "TEST-other")
    void anIdentityWithoutAViewerRoleIsRefused() throws Exception {
        Feed feed = new Feed();
        ExecutionException refused = assertThrows(ExecutionException.class, () -> feed.client.newWebSocketBuilder()
                .buildAsync(socketUri(), feed).get(10, TimeUnit.SECONDS), "the handshake must be refused");
        assertInstanceOf(java.net.http.WebSocketHandshakeException.class, refused.getCause());
        broadcaster.refreshAll();
        assertNull(feed.frames.poll(300, TimeUnit.MILLISECONDS), "a refused identity received a frame");
    }

    /**
     * The fixture's seeding and the intake commit notices the listener may still hold in its 250 ms window. A marker
     * write commits after them, so once a control socket sees the marker, none of them can reach a socket opened next.
     */
    private void settleSetupNotices() throws Exception {
        try (Feed control = open()) {
            String marker = copyItem(itemId);
            Instant deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline)) {
                String frame = control.frames.poll(100, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                JsonNode changed = mapper.readTree(frame).path("changed");
                if (changed.isArray() && changed.toString().contains(marker)) return;
                // A batch holding '*' is sent as '*' alone, so the marker may have been folded into it: write another.
                if ("*".equals(changed.asText())) marker = copyItem(itemId);
            }
            fail("the marker write never reached the control socket");
        }
    }

    private URI socketUri() {
        return URI.create(endpoint.toString().replaceFirst("^http", "ws"));
    }

    private Feed open() throws Exception {
        Feed feed = new Feed();
        feed.socket = feed.client.newWebSocketBuilder().buildAsync(socketUri(), feed).get(10, TimeUnit.SECONDS);
        return feed;
    }

    private final class Feed implements WebSocket.Listener, AutoCloseable {
        final HttpClient client = HttpClient.newHttpClient();
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final StringBuilder fragments = new StringBuilder();
        WebSocket socket;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                frames.add(fragments.toString());
                fragments.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        JsonNode next() throws Exception {
            String frame = frames.poll(10, TimeUnit.SECONDS);
            assertNotNull(frame, "the work-items socket delivered no frame");
            return mapper.readTree(frame);
        }

        @Override
        public void close() {
            if (socket != null) socket.abort();
        }
    }
}
