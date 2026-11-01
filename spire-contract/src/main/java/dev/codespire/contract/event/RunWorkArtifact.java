package dev.codespire.contract.event;

import java.util.Map;
import java.util.Objects;

/**
 * A specification or plan run's artifact (M4 slice 3), scrubbed of the run's credentials by the worker. A
 * terminal result: the unit is destroyed once it is recorded, and nothing was pushed.
 *
 * <p>{@code repositoryChanged} is a warning, never a failure: the agent committed in a run asked to change
 * nothing, and those commits were discarded with the unit. Usage is nullable and null IS unknown, as on
 * {@link RunResult.RunFinished}. Rebuild through {@link #withUsage(Map)}, which enumerates the components once.
 */
public record RunWorkArtifact(String runId, dev.codespire.contract.work.WorkDraftBinding draft, String text,
                              boolean repositoryChanged, Map<String, Long> tokenUsage,
                              long activeWallSeconds, java.util.List<RunResult.EgressAttempt> egress) implements RunResult {

    /** The transport cap in UTF-8 bytes, the publisher's own cap on the handoff file. */
    public static final int MAX_TEXT_BYTES = 32 * 1024;

    public RunWorkArtifact {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("An artifact needs its run");
        // An artifact written before the field existed reads as none: that worker had no proxy to report on.
        egress = egress == null ? java.util.List.of() : java.util.List.copyOf(egress);
        Objects.requireNonNull(draft, "An artifact needs its draft binding");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("An artifact needs its text");
        int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT_BYTES)
            throw new IllegalArgumentException("An artifact of " + bytes + " bytes is over the " + MAX_TEXT_BYTES + " byte cap");
        if (tokenUsage != null) {
            if (tokenUsage.isEmpty()) throw new IllegalArgumentException("An empty usage map is not a measurement");
            tokenUsage = Map.copyOf(tokenUsage);
        }
        if (activeWallSeconds < 0) throw new IllegalArgumentException("Active wall time cannot be negative");
    }

    /** Rebuild carrying measured usage, enumerating the components once. */
    public RunWorkArtifact withUsage(Map<String, Long> usage) {
        return new RunWorkArtifact(runId, draft, text, repositoryChanged, usage, activeWallSeconds, egress);
    }

    /** Rebuild carrying what the agent asked its egress proxy for, enumerating the components once. */
    public RunWorkArtifact withEgress(java.util.List<RunResult.EgressAttempt> attempts) {
        return new RunWorkArtifact(runId, draft, text, repositoryChanged, tokenUsage, activeWallSeconds, attempts);
    }
}
