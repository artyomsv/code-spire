package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.orchestrator.ws.RunTranscriptSocket;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Pushes a recorded event to whoever is tailing that run, and to nobody else.
 *
 * <p>Lives in the package that owns the transcript rather than beside the socket, matching the two
 * existing broadcasters: a socket depends inward on its data, never the reverse. The first version
 * sat inside the socket class and made {@code factory → ws → factory} a cycle.
 *
 * <p>Connections are looked up by the socket's <b>declared</b> endpoint id. The default is the fully
 * qualified class name and the lookup is an exact match, so the first version's simple-name lookup
 * matched nothing and every push was a no-op — a tail that opened, showed its snapshot, and never
 * moved again, which reads as a quiet run rather than a broken feature.
 */
@ApplicationScoped
public class RunTranscriptBroadcaster {

    private static final Logger LOG = Logger.getLogger(RunTranscriptBroadcaster.class);

    @Inject
    OpenConnections connections;

    @Inject
    ObjectMapper mapper;

    public void push(RunEventRecord event) {
        List<WebSocketConnection> tails = connections.findByEndpointId(RunTranscriptSocket.ENDPOINT_ID)
                .stream()
                .filter(open -> event.runId().equals(RunTranscriptSocket.runIdOf(open)))
                .toList();
        if (tails.isEmpty()) {
            // Checked before serialising. Ten thousand events per run, on the consumer thread, for
            // the common case of nobody watching.
            return;
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(List.of(event));
        } catch (JsonProcessingException unserialisable) {
            // WARN, not DEBUG: debug is off in production, and an event that can never be
            // serialised reaches no tail at all — that must not be silent.
            LOG.warnf(unserialisable, "run %s: event %d could not be serialised for the live tail",
                    event.runId(), event.sequence());
            return;
        }
        for (WebSocketConnection tail : tails) {
            // Fire and forget: a slow or dead subscriber must not hold up the consumer, and the
            // event is already recorded by the time this runs.
            tail.sendText(payload).subscribe().with(ignored -> { }, failure ->
                    LOG.debugf("a transcript tail dropped an event: %s", failure.getClass().getSimpleName()));
        }
    }
}
