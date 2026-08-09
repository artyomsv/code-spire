package dev.codespire.orchestrator.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import jakarta.inject.Inject;

/**
 * Live feed for the reviews list (spire-ui): on connect, a JSON array snapshot
 * of every review; afterwards ReviewProjection pushes single updated summaries
 * as reviews change. Read-side transport only.
 */
@WebSocket(path = "/api/ws/reviews")
public class ReviewsSocket {

    @Inject
    ReviewProjection projection;

    @Inject
    ObjectMapper mapper;

    @OnOpen
    public String onOpen() throws JsonProcessingException {
        // Live rows only. This snapshot REPLACES the client's list on every reconnect (and ADR-022's
        // cookie expiry makes reconnects routine), so archived rows sent here would be dropped again
        // moments later — Show-archived is a plain REST fetch instead.
        return mapper.writeValueAsString(projection.listSummaries(false));
    }
}
