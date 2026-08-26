package dev.codespire.contract.review;

/**
 * Per-invocation resolution-pipeline counts for a
 * {@link dev.codespire.contract.port.ContextResolutionSource} — the diagnostic that distinguishes
 * "nothing to do" from "systematically broken" in cases where {@link ContextContribution} alone
 * cannot: both report {@link ContribStatus#EMPTY} identically, since the contribution only says how
 * many items came out, never how many inputs went in.
 *
 * @param extracted        inputs the provider was handed to resolve (e.g. identifiers extracted from
 *                          the diff, or references extracted from the PR) — zero here means nothing to
 *                          look up, which is correct and uninteresting, not broken.
 * @param resolved          candidates the provider successfully resolved from those inputs, counted
 *                          before any output budget is applied. Zero while {@code extracted} is
 *                          positive is the broken case: plenty to look up, none of it resolved.
 * @param contributed       items actually present in the {@link ContextContribution} this run
 *                          produced, after any ranking and output cap.
 * @param droppedForBudget resolved candidates cut by an output cap ({@code resolved - contributed}) —
 *                          a nonzero value means candidates that DID resolve were discarded for space,
 *                          not that resolution failed; without this, a deployment silently losing good
 *                          candidates to a budget would look identical to one working fine.
 */
public record ContextResolutionCounts(int extracted, int resolved, int contributed, int droppedForBudget) {
}
