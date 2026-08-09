package dev.codespire.orchestrator.llm;

/**
 * A catalog model's delete or rename was refused because a provider still references it — the
 * deliberate in-use guards in {@link LlmModelRegistry#delete} and its rename check.
 *
 * <p>{@link LlmModelRegistry} otherwise wraps every {@code SQLException} — a genuine infrastructure
 * fault, not a request problem — as a plain {@link IllegalStateException} too. Without a
 * distinguishable type for the deliberate refusal, {@link LlmModelResource} could not catch one and
 * map it to 409 without also catching the other and mislabelling a broken database as a conflict.
 * Extends {@link IllegalStateException} so it still satisfies any existing broad catch of that type.
 */
public class ModelInUseException extends IllegalStateException {

    public ModelInUseException(String message) {
        super(message);
    }
}
