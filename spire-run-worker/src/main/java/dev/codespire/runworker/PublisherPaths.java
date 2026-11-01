package dev.codespire.runworker;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.event.RunResult;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The changed and refused paths a publisher reported, bounded: a run that touches sixty thousand files would otherwise
 * produce a result larger than a Kafka record, and the only trace of its completion would be a log line.
 */
final class PublisherPaths {

    /** Well past any reviewable change; enough to keep the result under the broker's record size. */
    static final int MAX_PATHS = 1000;

    /** Longer than any real path, short enough that a thousand of them stay readable. */
    static final int MAX_PATH_CHARS = 512;

    /** The longest real value is RENAMED_FROM; anything longer is not a kind. */
    static final int MAX_KIND_CHARS = 32;

    private final Set<String> changedPaths = new LinkedHashSet<>();

    /**
     * Keyed by path, so a path refused twice is listed once — the deduplication the set gave
     * before, kept now that the value carries the kind alongside it.
     */
    private final Map<String, RunResult.BlockedChange> blocked = new LinkedHashMap<>();

    private int omittedPaths;

    /**
     * The refused paths, each WITH what the run did to it.
     *
     * <p>Separate from {@link #changed} because this is the operator-facing list and the other is
     * not. The kind used to be read out of this JSON on the very next line and dropped, while
     * OutcomeWriter, PushDecision and PushGate each carried a javadoc arguing for it: "ci.yml was
     * blocked" does not say whether the factory edited that workflow or deleted it.
     *
     * <p>A legacy publisher wrote a bare string per entry rather than an object. That reads back as
     * a path with a null kind, which is what it is — the alternative is inventing one.
     */
    void blocked(JsonNode array) {
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
    static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    void changed(JsonNode array) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode entry : array) {
            String path = entry.isObject() ? entry.path("path").asText(null) : entry.asText(null);
            if (path == null || changedPaths.contains(path)) {
                continue;
            }
            if (changedPaths.size() >= MAX_PATHS) {
                omittedPaths++;
                continue;
            }
            changedPaths.add(path);
        }
    }

    boolean anyBlocked() {
        return !blocked.isEmpty();
    }

    List<String> changedPaths() {
        return List.copyOf(changedPaths);
    }

    List<RunResult.BlockedChange> blockedChanges() {
        return List.copyOf(blocked.values());
    }

    int omittedPaths() {
        return omittedPaths;
    }
}
