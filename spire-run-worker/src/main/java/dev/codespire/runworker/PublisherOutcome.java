package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the publisher reported, folded from its stdout JSON lines.
 *
 * <p>Three rules, each learned from a defect. The LAST push wins, because every checkpoint pushes
 * the same branch and the latest ref is the branch's state. A refusal outranks any push: what was
 * pushed before the gate tripped is on the remote, but the run's outcome is the refusal. And a
 * TERMINAL failure after the last push empties the ref — a forge refusing checkpoint five leaves
 * the branch at four, and reporting four as the run's success hid the refusal entirely — while a
 * non-terminal one ({@code BUNDLE_UNREADABLE}: the publisher skips that bundle and reads on) leaves
 * the earlier push standing, because four checkpoints really are on the branch.
 *
 * <p>The path lists are bounded: a run that touches sixty thousand files would otherwise produce a
 * result larger than a Kafka record, and the only trace of its completion would be a log line.
 */
public final class PublisherOutcome {

    /** Well past any reviewable change; enough to keep the result under the broker's record size. */
    static final int MAX_PATHS = 1000;

    /** Causes after which the publisher keeps reading — an earlier push is not undone by them. */
    private static final Set<String> NON_TERMINAL_CAUSES = Set.of("BUNDLE_UNREADABLE");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = Logger.getLogger(PublisherOutcome.class);

    private final Set<String> changedPaths = new LinkedHashSet<>();

    private final Set<String> blockedPaths = new LinkedHashSet<>();

    private int omittedPaths;

    private String pushedRef;

    private String failureCause;

    private String failureDetail;

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
                failedAfterPush = false;
            }
            case "gate_refused" -> {
                collect(node.path("blocked"), blockedPaths);
                collect(node.path("changed"), changedPaths);
            }
            case "failed" -> {
                failureCause = node.path("cause").asText("PUBLISHER_FAILED");
                failureDetail = node.path("detail").asText("");
                failedAfterPush = pushedRef != null && !NON_TERMINAL_CAUSES.contains(failureCause);
            }
            default -> {
            }
        }
    }

    private void collect(JsonNode array, Set<String> into) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode entry : array) {
            String path = entry.isObject() ? entry.path("path").asText(null) : entry.asText(null);
            if (path == null || into.contains(path)) {
                continue;
            }
            if (into.size() >= MAX_PATHS) {
                omittedPaths++;
                continue;
            }
            into.add(path);
        }
    }

    public boolean refused() {
        return !blockedPaths.isEmpty();
    }

    public Optional<String> pushedRef() {
        return refused() || failedAfterPush ? Optional.empty() : Optional.ofNullable(pushedRef);
    }

    public List<String> changedPaths() {
        return List.copyOf(changedPaths);
    }

    public List<String> blockedPaths() {
        return List.copyOf(blockedPaths);
    }

    /** Paths beyond {@link #MAX_PATHS} that the lists do not carry; logged by the launcher, never lost silently. */
    public int omittedPaths() {
        return omittedPaths;
    }

    public Optional<String> failureCause() {
        return Optional.ofNullable(failureCause);
    }

    public String failureDetail() {
        return failureDetail == null ? "" : failureDetail;
    }
}
