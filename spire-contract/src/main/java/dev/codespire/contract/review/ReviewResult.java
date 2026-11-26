package dev.codespire.contract.review;

import java.util.List;

/**
 * The review output. Rides INLINE in events — never stored as a blob (ADR-011).
 * {@code truncated} = some prompt input exceeded its budget and was clipped, so the
 * review is partial (surfaced on the dashboard + the posted summary comment).
 * {@code degraded} = the model's response yielded no structured findings at all, so this is
 * not a review with nothing to report — it is the absence of a review.
 * {@code omittedFiles} = the paths of changed files the model did not fully see because the diff
 * exceeded the review input limit; empty when the diff was not clipped. Never null.
 *
 * <p>The distinction is the point: a degraded parse posts zero findings, and zero findings is
 * exactly what a clean review posts too. Two different models were once charged for a large diff,
 * returned nothing parseable, and each produced a run the dashboard rendered as done with no
 * findings — the operator had no way to tell a passing review from one that never happened. The
 * summary comment always said so; nothing else did.
 *
 * <p>{@code omittedFiles} came after the other components: a result written before it decodes with an
 * empty list, and an older reader ignores it.
 */
public record ReviewResult(List<Finding> findings, String summary, ModelUsage usage,
                           boolean truncated, boolean degraded, List<String> omittedFiles) {

    public ReviewResult {
        findings = findings == null ? null : List.copyOf(findings);
        omittedFiles = omittedFiles == null ? List.of() : List.copyOf(omittedFiles);
    }

    /** Convenience: no omitted files — every result written before the review input limit existed. */
    public ReviewResult(List<Finding> findings, String summary, ModelUsage usage,
                        boolean truncated, boolean degraded) {
        this(findings, summary, usage, truncated, degraded, List.of());
    }

    /** Convenience: a complete (non-truncated) result — the common case. */
    public ReviewResult(List<Finding> findings, String summary, ModelUsage usage) {
        this(findings, summary, usage, false, false);
    }

    /**
     * A run that produced no usable findings, carrying only the explanation.
     *
     * <p>Named rather than {@code new ReviewResult(List.of(), summary, usage, false, true)}: two
     * adjacent positional booleans at a call site cannot be read without the constructor open
     * beside them, and transposing them would report the absence of a review as a clipped diff.
     */
    public static ReviewResult degraded(String summary, ModelUsage usage) {
        return new ReviewResult(List.of(), summary, usage, false, true);
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
        return new ReviewResult(findings, summary, usage, truncated, degraded, omittedFiles);
    }

    /**
     * The same result, naming the files the model did not fully see. Does not set {@code truncated}: the caller
     * says whether anything was clipped, since a clipped context can make a review partial with every file seen.
     */
    public ReviewResult withOmittedFiles(List<String> omittedFiles) {
        return new ReviewResult(findings, summary, usage, truncated, degraded, omittedFiles);
    }

    /** The same result with another summary — a review that started before CI finished says so in it. */
    public ReviewResult withSummary(String summary) {
        return new ReviewResult(findings, summary, usage, truncated, degraded, omittedFiles);
    }

    /** The same result over a different finding list — the reconcile path drops anchor collisions. */
    public ReviewResult withFindings(List<Finding> findings) {
        return new ReviewResult(findings, summary, usage, truncated, degraded, omittedFiles);
    }
}
