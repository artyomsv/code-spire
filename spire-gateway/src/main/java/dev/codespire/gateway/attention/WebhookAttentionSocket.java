package dev.codespire.gateway.attention;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import jakarta.inject.Inject;

/**
 * Live feed of the gateway's attention conditions: on connect, the full current list; afterwards
 * {@link WebhookAttentionBroadcaster} pushes a fresh list whenever one changes.
 *
 * <p>A path distinct from the orchestrator's socket because the browser reaches both through one origin,
 * so they must be separable by the dev-server proxy. The UI opens both and merges,
 * exactly as it already did with the two HTTP feeds — neither service reads the other's schema, so
 * there is no aggregating service to hold a single socket.
 */
@WebSocket(path = "/ws/webhook-attention")
public class WebhookAttentionSocket {

    @Inject
    WebhookAttentionBroadcaster broadcaster;

    @OnOpen
    public String onOpen() throws JsonProcessingException {
        return broadcaster.snapshot();
    }
}
