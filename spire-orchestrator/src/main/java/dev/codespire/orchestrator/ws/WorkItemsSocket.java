package dev.codespire.orchestrator.ws;

import dev.codespire.orchestrator.work.WorkItemsBroadcaster;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import jakarta.annotation.security.RolesAllowed;

/**
 * Change notices for the work-item screens, sent by {@link WorkItemsBroadcaster}. Nothing is sent on open: the client
 * reads the current state over REST when the socket opens.
 *
 * <p>The same roles as {@code WorkItemResource}: item ids and the moment an item changes are data, and the {@code /api/*}
 * policy only asks for a signed-in identity. The handshake of an identity with neither role is refused, because a later
 * REST refusal cannot take back a notice already sent.
 */
@WebSocket(path = "/api/ws/work-items", endpointId = WorkItemsSocket.ENDPOINT_ID)
@RolesAllowed({"spire-viewer", "spire-admin"})
public class WorkItemsSocket {

    public static final String ENDPOINT_ID = WorkItemsBroadcaster.ENDPOINT_ID;

    /** Registers the endpoint's open; sends nothing. */
    @OnOpen
    public void onOpen() {
    }
}
