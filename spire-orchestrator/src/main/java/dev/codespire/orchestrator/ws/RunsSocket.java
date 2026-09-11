package dev.codespire.orchestrator.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunsBroadcaster;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import jakarta.inject.Inject;

/** Snapshot on open, then single-row pushes. Clients filter locally as rows change status. */
@WebSocket(path = "/api/ws/runs", endpointId = RunsSocket.ENDPOINT_ID)
public class RunsSocket {

    public static final String ENDPOINT_ID = RunsBroadcaster.ENDPOINT_ID;

    @Inject
    FactoryRunProjection runs;

    @Inject
    ObjectMapper mapper;

    @OnOpen
    public String onOpen() throws JsonProcessingException {
        return mapper.writeValueAsString(runs.list(new FactoryRunProjection.RunFilter(null, null, null, 200)));
    }
}
