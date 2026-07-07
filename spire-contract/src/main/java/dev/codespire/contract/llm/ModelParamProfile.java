package dev.codespire.contract.llm;

import java.util.Map;

/**
 * Per-model API parameter profile (ADR-018) — how a specific model expects its
 * request parameters, so the worker adapts instead of hardcoding one dialect.
 *
 * <p>Different models accept different parameters: classic Chat Completions models
 * (gpt-4o, gpt-4-turbo) take {@code max_tokens} + a custom {@code temperature},
 * while newer reasoning models (o1, o3, gpt-5) reject {@code max_tokens} (they
 * require {@code max_completion_tokens}) and reject a non-default temperature.
 * Rather than sniff model names in code, the operator declares each model's
 * profile on the catalog; the orchestrator brokers it to the worker per review.
 *
 * @param outputTokenParam    which output-cap parameter the model accepts
 * @param supportsTemperature false for models that only allow the default temperature (omit it)
 * @param reasoningEffort     OpenAI {@code reasoning_effort} (low|medium|high), or null to omit
 * @param extraParams         free-form pass-through params (OpenAI customParameters); never null
 */
public record ModelParamProfile(
        OutputTokenParam outputTokenParam,
        boolean supportsTemperature,
        String reasoningEffort,
        Map<String, Object> extraParams) {

    /** Which parameter carries the output-token cap for this model. */
    public enum OutputTokenParam {
        MAX_TOKENS,             // classic Chat Completions (gpt-4o, gpt-4-turbo, gpt-3.5)
        MAX_COMPLETION_TOKENS,  // reasoning / newer models (o1, o3, gpt-5)
        NONE                    // model rejects any explicit output cap
    }

    public ModelParamProfile {
        if (outputTokenParam == null) {
            outputTokenParam = OutputTokenParam.MAX_TOKENS;
        }
        reasoningEffort = (reasoningEffort == null || reasoningEffort.isBlank()) ? null : reasoningEffort;
        extraParams = extraParams == null ? Map.of() : Map.copyOf(extraParams);
    }

    /** The classic Chat Completions dialect — the safe default for existing models. */
    public static ModelParamProfile legacyDefault() {
        return new ModelParamProfile(OutputTokenParam.MAX_TOKENS, true, null, Map.of());
    }
}
