package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunResult;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** Longer than any real path, short enough that a thousand of them stay readable. */
    private static final int MAX_PATH_CHARS = 512;

    /** The longest real value is RENAMED_FROM; anything longer is not a kind. */
    private static final int MAX_KIND_CHARS = 32;

    private final Set<String> changedPaths = new LinkedHashSet<>();

    /**
     * Keyed by path, so a path refused twice is listed once — the deduplication the set gave
     * before, kept now that the value carries the kind alongside it.
     */
    private final Map<String, RunResult.BlockedChange> blocked = new LinkedHashMap<>();

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
                collectBlocked(node.path("blocked"));
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

    /**
     * The refused paths, each WITH what the run did to it.
     *
     * <p>Separate from {@link #collect} because this is the operator-facing list and the other is
     * not. The kind used to be read out of this JSON on the very next line and dropped, while
     * OutcomeWriter, PushDecision and PushGate each carried a javadoc arguing for it: "ci.yml was
     * blocked" does not say whether the factory edited that workflow or deleted it.
     *
     * <p>A legacy publisher wrote a bare string per entry rather than an object. That reads back as
     * a path with a null kind, which is what it is — the alternative is inventing one.
     */
    private void collectBlocked(JsonNode array) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode entry : array) {
            String path = entry.isObject() ? entry.path("path").asText(null) : entry.asText(null);
            if (path == null || blocked.containsKey(path)) {
                continue;
            }
            if (blocked.size() >= MAX_PATHS) {
                omittedPaths++;
                continue;
            }
            String kind = entry.isObject() ? entry.path("kind").asText(null) : null;
            blocked.put(path, new RunResult.BlockedChange(clip(path, MAX_PATH_CHARS),
                    clip(kind, MAX_KIND_CHARS)));
        }
    }

    /**
     * Bounds one entry, because the count cap alone does not bound the TEXT.
     *
     * <p>These reach an operator's attention row as one joined sentence, and both values come from
     * a branch an agent authored. A thousand paths was already capped; a thousand paths each a
     * megabyte long was not. Clipped rather than rejected: a truncated path still tells an operator
     * which file tripped the gate, and dropping the entry would lose that.
     */
    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
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
        return !blocked.isEmpty();
    }

    public Optional<String> pushedRef() {
        return refused() || failedAfterPush ? Optional.empty() : Optional.ofNullable(pushedRef);
    }

    public List<String> changedPaths() {
        return List.copyOf(changedPaths);
    }

    public List<RunResult.BlockedChange> blocked() {
        return List.copyOf(blocked.values());
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
