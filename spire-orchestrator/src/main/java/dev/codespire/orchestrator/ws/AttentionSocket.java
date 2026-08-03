package dev.codespire.orchestrator.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.codespire.orchestrator.attention.AttentionBroadcaster;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import jakarta.inject.Inject;

/**
 * Live feed of the orchestrator's attention conditions: on connect, the full current list; afterwards
 * {@link AttentionBroadcaster} pushes a fresh list whenever it changes.
 *
 * <p>The whole list rather than deltas, deliberately. A row is derived state with no identity of its
 * own — nothing to address an incremental update to — and the list is a handful of entries, so
 * resending it is simpler than a diff protocol and cannot drift out of sync with the server.
 *
 * <p>Read-side transport only, mirroring {@link ReviewsSocket}. The gateway serves its own conditions
 * on its own socket, because neither service reads the other's schema.
 */
@WebSocket(path = "/api/ws/attention")
public class AttentionSocket {

    @Inject
    AttentionBroadcaster broadcaster;

    @OnOpen
    public String onOpen() throws JsonProcessingException {
        return broadcaster.snapshot();
    }
}
