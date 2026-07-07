package dev.codespire.contract.review;

import java.util.List;

/**
 * The review output. Rides INLINE in events — never stored as a blob (ADR-011).
 * {@code truncated} = the diff exceeded the prompt budget and was clipped, so the
 * review is partial (surfaced on the dashboard + the posted summary comment).
 */
public record ReviewResult(List<Finding> findings, String summary, ModelUsage usage, boolean truncated) {

    public ReviewResult {
        findings = findings == null ? null : List.copyOf(findings);
    }

    /** Convenience: a complete (non-truncated) result — the common case. */
    public ReviewResult(List<Finding> findings, String summary, ModelUsage usage) {
        this(findings, summary, usage, false);
    }
}
