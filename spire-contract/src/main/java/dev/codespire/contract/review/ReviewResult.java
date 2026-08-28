package dev.codespire.contract.review;

import java.util.List;

/**
 * The review output. Rides INLINE in events — never stored as a blob (ADR-011).
 * {@code truncated} = the diff exceeded the prompt budget and was clipped, so the
 * review is partial (surfaced on the dashboard + the posted summary comment).
 * {@code degraded} = the model's response yielded no structured findings at all, so this is
 * not a review with nothing to report — it is the absence of a review.
 *
 * <p>The distinction is the point: a degraded parse posts zero findings, and zero findings is
 * exactly what a clean review posts too. Two different models were once charged for a large diff,
 * returned nothing parseable, and each produced a run the dashboard rendered as done with no
 * findings — the operator had no way to tell a passing review from one that never happened. The
 * summary comment always said so; nothing else did.
 */
public record ReviewResult(List<Finding> findings, String summary, ModelUsage usage,
                           boolean truncated, boolean degraded) {

    public ReviewResult {
        findings = findings == null ? null : List.copyOf(findings);
    }

    /**
     * Convenience: a result whose findings were parsed normally — the common case, whether or not the
     * diff had to be clipped.
     */
    public ReviewResult(List<Finding> findings, String summary, ModelUsage usage, boolean truncated) {
        this(findings, summary, usage, truncated, false);
    }

    /** Convenience: a complete (non-truncated) result — the common case. */
    public ReviewResult(List<Finding> findings, String summary, ModelUsage usage) {
        this(findings, summary, usage, false, false);
    }

    /**
     * The same result, marked as reviewing a clipped diff.
     *
     * <p>A wither rather than a {@code new ReviewResult(...)} at the call site. Every rebuild that
     * re-lists components silently drops any component the call site predates — and it still
     * compiles, because the shorter convenience constructors remain valid. Both rebuild sites in the
     * worker dropped {@code degraded} the moment it was added, for exactly that reason. Enumerating
     * the components here instead means the next component is a compile error in one place.
     */
    public ReviewResult withTruncated(boolean truncated) {
        return new ReviewResult(findings, summary, usage, truncated, degraded);
    }

    /** The same result over a different finding list — the reconcile path drops anchor collisions. */
    public ReviewResult withFindings(List<Finding> findings) {
        return new ReviewResult(findings, summary, usage, truncated, degraded);
    }
}
