package dev.codespire.orchestrator.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.orchestrator.factory.RunEventProjection;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The live tail of one run's transcript (FR-F5).
 *
 * <p>Opening sends what the run has produced so far, and everything recorded afterwards arrives as
 * it lands. Without the snapshot a tail opened mid-run would show nothing until the agent's next
 * event, which for a model that thinks for a minute reads as a broken page.
 *
 * <p><b>Bounded at both ends.</b> The snapshot is a page rather than the whole stream, for the same
 * reason the REST read is, and the per-run cap upstream means the stream itself cannot grow without
 * limit while a tail is open.
 *
 * <p>The path carries the run id, which spans several segments — a run id embeds the repository and
 * a GitLab workspace can itself contain a slash — so the parameter is matched greedily and the
 * socket path deliberately ends there.
 */
@WebSocket(path = "/api/ws/runs/{runId}/transcript")
public class RunTranscriptSocket {

    /** What a newly opened tail is shown before live events start arriving. */
    static final int SNAPSHOT_EVENTS = 200;

    @Inject
    RunEventProjection transcript;

    @Inject
    ObjectMapper mapper;

    @OnOpen
    public String onOpen(WebSocketConnection connection) throws Exception {
        return mapper.writeValueAsString(
                transcript.transcript(connection.pathParam("runId"), SNAPSHOT_EVENTS));
    }

    /**
     * Pushes one event to whoever is tailing that run, and to nobody else.
     *
     * <p>Called by the consumer as events land. Filtering by the connection's own path parameter is
     * what keeps one run's transcript out of another's tail — the sockets share a path template, so
     * without it every subscriber would receive every run's events.
     */
    @ApplicationScoped
    public static class Tail {

        private static final Logger LOG = Logger.getLogger(Tail.class);

        @Inject
        OpenConnections connections;

        @Inject
        ObjectMapper mapper;

        public void push(RunEventRecord event) {
            String payload;
            try {
                payload = mapper.writeValueAsString(List.of(event));
            } catch (Exception unserialisable) {
                LOG.debugf("run %s: event %d could not be serialised for the live tail",
                        event.runId(), event.sequence());
                return;
            }
            connections.findByEndpointId(RunTranscriptSocket.class.getSimpleName()).forEach(open -> {
                if (event.runId().equals(open.pathParam("runId"))) {
                    // Fire and forget: a slow or dead subscriber must not hold up recording the
                    // transcript, which has already happened by the time this runs.
                    open.sendText(payload).subscribe().with(ignored -> { }, failure ->
                            LOG.debugf("a transcript tail dropped an event: %s",
                                    failure.getClass().getSimpleName()));
                }
            });
        }
    }
}
