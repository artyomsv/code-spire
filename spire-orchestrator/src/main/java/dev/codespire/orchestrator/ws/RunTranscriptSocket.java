package dev.codespire.orchestrator.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.orchestrator.factory.RunEventProjection;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The live tail of one run's transcript (FR-F5).
 *
 * <p>Opening sends the newest page the run has produced, and everything recorded afterwards arrives
 * as it lands. Without the snapshot a tail opened mid-run would show nothing until the agent's next
 * event, which for a model that thinks for a minute reads as a broken page.
 *
 * <p><b>The run id rides the query string, not the path, and that is forced rather than chosen.</b>
 * A run id always contains a slash — {@code RunIds.of} builds {@code workspace + "/" + slug} — and a
 * WebSocket path parameter here compiles to a Vert.x {@code :name}, which matches exactly one
 * segment. A templated path therefore 404s the handshake for every run that exists. The first
 * version of this class used one and claimed in its own javadoc that the parameter "is matched
 * greedily"; it is not, and no run could ever open a tail. The REST route escapes this with an
 * explicit {@code {runId:.+}} regex, which this API has no equivalent of.
 *
 * <p><b>The endpoint id is declared.</b> It defaults to the fully qualified class name, and
 * {@link io.quarkus.websockets.next.OpenConnections#findByEndpointId} is an exact match — so looking
 * connections up by the simple name, as the first version did, silently matched nothing and made
 * every push a no-op. A named constant removes the guess from both sides.
 */
@WebSocket(path = RunTranscriptSocket.PATH, endpointId = RunTranscriptSocket.ENDPOINT_ID)
public class RunTranscriptSocket {

    public static final String PATH = "/api/ws/runs/transcript";

    /** Declared rather than defaulted, so both this class and the broadcaster name the same thing. */
    public static final String ENDPOINT_ID = "run-transcript";

    /** The query parameter carrying the run id, since a path segment cannot hold one. */
    public static final String RUN_ID_PARAM = "runId";

    /** What a newly opened tail is shown before live events start arriving. */
    static final int SNAPSHOT_EVENTS = 200;

    @Inject
    RunEventProjection transcript;

    @Inject
    ObjectMapper mapper;

    @OnOpen
    public String onOpen(WebSocketConnection connection) throws JsonProcessingException {
        String runId = runIdOf(connection);
        if (runId.isBlank()) {
            connection.close(new CloseReason(1008, "a transcript tail must name its run"));
            return null;
        }
        if (transcript.countFor(runId) < 0) {
            // Closed rather than answered with an empty page, matching the REST route's reasoning:
            // an empty transcript reads as "this run produced nothing", which is a different answer
            // from "there is no such run" and sends an operator somewhere else.
            connection.close(new CloseReason(1008, "no such run"));
            return null;
        }
        return mapper.writeValueAsString(transcript.newestPage(runId, SNAPSHOT_EVENTS));
    }

    /**
     * The run id from the handshake's query string, decoded.
     *
     * <p>Read the same way by the broadcaster, so the value a tail registered under and the value an
     * event is matched against cannot diverge.
     */
    public static String runIdOf(WebSocketConnection connection) {
        String query = connection.handshakeRequest().query();
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && RUN_ID_PARAM.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /** The snapshot's shape, so a caller can serialise an empty page the same way. */
    static List<?> emptyPage() {
        return List.of();
    }
}
