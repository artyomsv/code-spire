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
 * {@code clippedInputs} = the names of the prompt inputs cut to their budget ({@code diff}, {@code context},
 * {@code code_context}, {@code prior_findings}); empty on a result written before they were named. Never null.
 * Only a cut diff means a changed line went unseen; a cut context only leaves the model less background.
 *
 * <p>The distinction is the point: a degraded parse posts zero findings, and zero findings is
 * exactly what a clean review posts too. Two different models were once charged for a large diff,
 * returned nothing parseable, and each produced a run the dashboard rendered as done with no
 * findings — the operator had no way to tell a passing review from one that never happened. The
 * summary comment always said so; nothing else did.
 *
 * <p>{@code omittedFiles} and {@code clippedInputs} came after the other components: a result written before
 * them decodes with empty lists, and an older reader ignores them.
 */
public record ReviewResult(List<Finding> findings, String summary, ModelUsage usage,
                           boolean truncated, boolean degraded, List<String> omittedFiles,
                           List<String> clippedInputs) {

    /** The name of the diff in {@link #clippedInputs}. */
    public static final String DIFF_INPUT = "diff";

    public ReviewResult {
        findings = findings == null ? null : List.copyOf(findings);
        omittedFiles = omittedFiles == null ? List.of() : List.copyOf(omittedFiles);
        clippedInputs = clippedInputs == null ? List.of() : List.copyOf(clippedInputs);
    }

    /** Convenience: no named inputs — every result written before the clipped inputs were named. */
    public ReviewResult(List<Finding> findings, String summary, ModelUsage usage,
                        boolean truncated, boolean degraded, List<String> omittedFiles) {
        this(findings, summary, usage, truncated, degraded, omittedFiles, List.of());
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
        return new ReviewResult(findings, summary, usage, truncated, degraded, omittedFiles, clippedInputs);
    }

    /**
     * The same result, naming the files the model did not fully see. Does not set {@code truncated}: the caller
     * says whether anything was clipped, since a clipped context can make a review partial with every file seen.
     */
    public ReviewResult withOmittedFiles(List<String> omittedFiles) {
        return new ReviewResult(findings, summary, usage, truncated, degraded, omittedFiles, clippedInputs);
    }

    /**
     * The same result, naming the prompt inputs that were cut to their budget. Does not set {@code truncated}, as
     * {@link #withOmittedFiles} does not.
     */
    public ReviewResult withClippedInputs(List<String> clippedInputs) {
        return new ReviewResult(findings, summary, usage, truncated, degraded, omittedFiles, clippedInputs);
    }

    /**
     * Whether the model may not have seen every changed line: files were skipped or cut, the diff was clipped, or the
     * result is clipped without naming what (written before the inputs were named), read as the worst case. A cut
     * context, code context or prior-findings list alone is not this: every changed line was still reviewed.
     */
    public boolean diffNotWhole() {
        if (!omittedFiles.isEmpty() || clippedInputs.contains(DIFF_INPUT)) {
            return true;
        }
        return truncated && clippedInputs.isEmpty();
    }

    /** The same result with another summary — a review that started before CI finished says so in it. */
    public ReviewResult withSummary(String summary) {
        return new ReviewResult(findings, summary, usage, truncated, degraded, omittedFiles, clippedInputs);
    }

    /** The same result over a different finding list — the reconcile path drops anchor collisions. */
    public ReviewResult withFindings(List<Finding> findings) {
        return new ReviewResult(findings, summary, usage, truncated, degraded, omittedFiles, clippedInputs);
    }
}
