package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the publisher reported, accumulated from its stdout.
 *
 * <p>The publisher writes one JSON line per outcome and nothing is extracted from the pod
 * (ADR-038), so this stream IS the run's audit trail. It is also the only channel by which a gate
 * refusal reaches the worker.
 *
 * <p><b>The last push wins, and refusals accumulate.</b> Continuous checkpointing means several
 * pushes per run, each superseding the last; a refusal is terminal, so once one arrives the run is
 * refused however many pushes preceded it.
 */
public final class PublisherOutcome {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = Logger.getLogger(PublisherOutcome.class);

    private final List<String> changedPaths = new ArrayList<>();

    private final List<String> blockedPaths = new ArrayList<>();

    private String pushedRef;

    private String failureCause;

    private String failureDetail;

    /** A terminal publisher failure that arrived after a checkpoint had already pushed. */
    private boolean failedAfterPush;

    /**
     * Reads one line. An unparseable one is skipped — the publisher also logs plain text on the
     * same stream — but never silently: a report line that fails to parse is the one case where a
     * push could have happened with nothing here to say so, and a debug line is the only trace.
     */
    public void accept(String line) {
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (JsonProcessingException e) {
            LOG.debugf("publisher wrote a non-JSON line, skipped: %s", e.getOriginalMessage());
            return;
        }
        if (node == null || !node.isObject()) {
            return;
        }
        switch (node.path("event").asText("")) {
            case "pushed" -> {
                pushedRef = node.path("ref").asText(null);
                collect(node.path("changed"), changedPaths);
                // A push after a failure means the failure was transient and cured; only a failure
                // that is the LAST word withholds the ref.
                failedAfterPush = false;
            }
            case "gate_refused" -> {
                collect(node.path("blocked"), blockedPaths);
                collect(node.path("changed"), changedPaths);
            }
            case "failed" -> {
                failureCause = node.path("cause").asText("PUBLISHER_FAILED");
                failureDetail = node.path("detail").asText("");
                // A failure AFTER a checkpoint pushed means the run's final state did not reach the
                // remote. Remembered so pushedRef() can withhold the stale ref, for the same reason
                // a refusal outranks the pushes before it.
                failedAfterPush = pushedRef != null;
            }
            default -> {
                // A shape this worker does not model. Ignored rather than fatal: the publisher is
                // free to add outcomes, and an unknown one must not fail a run that already pushed.
            }
        }
    }

    private static void collect(JsonNode array, List<String> into) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode entry : array) {
            String path = entry.isObject() ? entry.path("path").asText(null) : entry.asText(null);
            if (path != null && !into.contains(path)) {
                into.add(path);
            }
        }
    }

    /**
     * A gate refusal outranks any push that preceded it.
     *
     * <p>Continuous checkpointing means a run can push several times and THEN be refused, so
     * reporting the last push as the outcome would announce a successful run whose final state the
     * gate rejected.
     */
    public boolean refused() {
        return !blockedPaths.isEmpty();
    }

    public Optional<String> pushedRef() {
        // A refusal or a later failure both mean the final state is not on the remote; the ref of
        // an earlier checkpoint would announce a delivery that did not happen.
        return refused() || failedAfterPush ? Optional.empty() : Optional.ofNullable(pushedRef);
    }

    public List<String> changedPaths() {
        return List.copyOf(changedPaths);
    }

    public List<String> blockedPaths() {
        return List.copyOf(blockedPaths);
    }

    public Optional<String> failureCause() {
        return Optional.ofNullable(failureCause);
    }

    public String failureDetail() {
        return failureDetail == null ? "" : failureDetail;
    }
}
