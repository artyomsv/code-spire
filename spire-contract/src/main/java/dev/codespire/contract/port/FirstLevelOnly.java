package dev.codespire.contract.port;

/**
 * Optional {@link ContextProvider} capability: a marker for a provider whose inputs are entirely
 * carried on the command itself — never discovered by mining text {@code ContextWorker} retrieved
 * at an earlier level — so it has nothing further to contribute at level 2+ of the aggregator's
 * bounded two-level fan-out (CONTRACT §8). The same shape {@link ContextResolutionSource} and
 * {@link ThreadSource} already use: a plain {@code instanceof} check, gated on the capability
 * interface rather than a concrete provider class.
 *
 * <p>Only the code provider implements this today. {@code codeReferences} rides unchanged on every
 * level's {@link dev.codespire.contract.review.ContextRequest} — level 2 mines the PREVIOUS level's
 * retrieved text for fresh references, and the diff's own changed paths and identifiers are not that
 * kind of reference — so without this marker {@code supports(request)} would report {@code true}
 * again at level 2, and the whole fetch-and-extract pipeline would re-run a second time inside the
 * same 20s budget for no new information. A provider gated this way still runs once, at level 1 — it
 * never sits out the fan-out entirely.
 */
public interface FirstLevelOnly {
}
