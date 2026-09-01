package dev.codespire.runworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    private final List<String> changedPaths = new ArrayList<>();

    private final List<String> blockedPaths = new ArrayList<>();

    private String pushedRef;

    private String failureCause;

    private String failureDetail;

    /** Reads one line. An unparseable one is ignored — the publisher also logs plain text. */
    public void accept(String line) {
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (Exception e) {
            return;
        }
        if (node == null || !node.isObject()) {
            return;
        }
        switch (node.path("event").asText("")) {
            case "pushed" -> {
                pushedRef = node.path("ref").asText(null);
                collect(node.path("changed"), changedPaths);
            }
            case "gate_refused" -> {
                collect(node.path("blocked"), blockedPaths);
                collect(node.path("changed"), changedPaths);
            }
            case "failed" -> {
                failureCause = node.path("cause").asText("PUBLISHER_FAILED");
                failureDetail = node.path("detail").asText("");
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
        return refused() ? Optional.empty() : Optional.ofNullable(pushedRef);
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
