package dev.codespire.contract.llm;

import dev.codespire.contract.review.ModelUsage;

/**
 * One model response.
 *
 * @param text         the model's raw output
 * @param usage        token counts, priced by the orchestrator's ledger (ADR-023)
 * @param outputCapped the provider stopped because the response reached its output limit, rather
 *                     than because the model had finished. A neutral boolean, not the underlying
 *                     client's enum: this module is framework-free, and every provider expresses
 *                     the same fact differently.
 *
 *                     <p>Load-bearing. Without it, "the model ran out of room" is inferred only
 *                     from a total parse failure, which misses the case where the response was cut
 *                     off after some complete findings — that parses, reports a partial finding set,
 *                     and looks exactly like a finished review. Raising the output cap does not
 *                     remove that case; it makes it the likely one, because a model with room to
 *                     start answering is cut off part-way rather than before it begins.
 */
public record Completion(String text, ModelUsage usage, boolean outputCapped) {

    /** A response the provider finished on its own terms. */
    public Completion(String text, ModelUsage usage) {
        this(text, usage, false);
    }
}
