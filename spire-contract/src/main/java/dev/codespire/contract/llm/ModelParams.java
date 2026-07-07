package dev.codespire.contract.llm;

/**
 * Per-call model parameters. {@code profile} declares the model's API dialect
 * (which token-cap parameter, whether temperature is allowed, …) so the worker
 * builds a request the model actually accepts; it is never null.
 */
public record ModelParams(String model, double temperature, Integer maxTokens, ModelParamProfile profile) {

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
