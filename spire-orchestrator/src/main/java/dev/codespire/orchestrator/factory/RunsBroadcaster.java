package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Sends committed run rows to list subscribers without delaying them on a browser's response. */
@ApplicationScoped
public class RunsBroadcaster {

    /** Shared with the transport; the factory package does not depend on the socket package. */
    public static final String ENDPOINT_ID = "runs";

    private static final Logger LOG = Logger.getLogger(RunsBroadcaster.class);

    @Inject
    OpenConnections connections;

    @Inject
    FactoryRunProjection runs;

    @Inject
    ObjectMapper mapper;

    public void push(String runId) {
        try {
            var subscribers = connections.findByEndpointId(ENDPOINT_ID);
            if (subscribers.isEmpty()) return;
            var row = runs.listOne(runId);
            if (row.isEmpty()) return;
            String payload = mapper.writeValueAsString(row.get());
            for (WebSocketConnection subscriber : subscribers) send(subscriber, payload);
        } catch (JsonProcessingException | RuntimeException failure) {
            LOG.debugf(failure, "run %s: live update dropped", runId);
        }
    }

    private void send(WebSocketConnection subscriber, String payload) {
        try {
            subscriber.sendText(payload).subscribe().with(ignored -> { }, failure ->
                    LOG.debugf("a runs subscriber dropped an update: %s", failure.getClass().getSimpleName()));
        } catch (RuntimeException failure) {
            // A synchronous send failure must not prevent delivery to the remaining subscribers.
            LOG.debugf(failure, "a runs subscriber dropped an update");
        }
    }
}
