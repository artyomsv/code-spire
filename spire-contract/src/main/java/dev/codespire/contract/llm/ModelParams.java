package dev.codespire.contract.llm;

/**
 * Per-call model parameters. {@code profile} declares the model's API dialect
 * (which token-cap parameter, whether temperature is allowed, …) so the worker
 * builds a request the model actually accepts; it is never null.
 */
public record ModelParams(String model, double temperature, Integer maxTokens, ModelParamProfile profile) {

    /**
     * Output cap applied when {@link #maxTokens()} is unset: the paid call never runs without a bound.
     *
     * <p>Here rather than in {@code spire-llm} because two modules read it: the provider sends it, and the
     * orchestrator subtracts it from the model's context window to learn how much input a review may send.
     * Was 4096, which a reasoning model exhausts before it answers (DECISIONS.md, 2026-08-28).
     */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 16384;

    public ModelParams {
        if (profile == null) {
            profile = ModelParamProfile.legacyDefault();
        }
    }

    /** Convenience: the classic Chat Completions dialect (max_tokens + temperature). */
    public ModelParams(String model, double temperature, Integer maxTokens) {
        this(model, temperature, maxTokens, ModelParamProfile.legacyDefault());
    }
}
