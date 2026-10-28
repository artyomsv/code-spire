package dev.codespire.orchestrator.ws;

import dev.codespire.orchestrator.work.WorkItemsBroadcaster;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;

/** Change notices for the work-item screens. */
@WebSocket(path = "/api/ws/work-items", endpointId = WorkItemsSocket.ENDPOINT_ID)
public class WorkItemsSocket {

    public static final String ENDPOINT_ID = WorkItemsBroadcaster.ENDPOINT_ID;

    /** Registers the endpoint's open; sends nothing. */
    @OnOpen
    public void onOpen() {
    }
}
