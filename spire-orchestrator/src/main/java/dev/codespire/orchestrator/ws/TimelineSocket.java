package dev.codespire.orchestrator.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import jakarta.inject.Inject;

/**
 * Read-side transport only (ARCHITECTURE §8): pushes timeline entries to the
 * dashboard. The domain flow never touches WebSockets.
 */
@WebSocket(path = "/ws/timeline")
public class TimelineSocket {

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    ObjectMapper mapper;

    @OnOpen
    public String onOpen() throws JsonProcessingException {
        // Initial snapshot as a JSON array; live entries follow as single objects.
        return mapper.writeValueAsString(timeline.snapshot());
    }
}
