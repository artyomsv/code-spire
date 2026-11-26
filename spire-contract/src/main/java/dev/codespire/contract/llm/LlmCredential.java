package dev.codespire.contract.llm;

/**
 * The LLM credential brokered (KEK-encrypted) to the worker on a GenerateReview
 * command (ADR-018) — the LLM analog of {@link dev.codespire.contract.scm.ScmCredential}.
 * The orchestrator resolves the applicable LLM provider from the registry, packs
 * this record, and encrypts it; the worker decrypts it to build the model per
 * command. Never logged.
 *
 * <p>The two input limits size a review's diff (review input limit design, 2026-11-26). Both are null on a
 * credential packed before they existed, and the worker reads null as "today's fixed budgets".
 *
 * @param type           provider type — {@code openai}, {@code anthropic}, or {@code gemini}
 * @param baseUrl        API base URL
 * @param apiKey         the API key (decrypted only inside the worker)
 * @param model          the model name
 * @param temperature    sampling temperature
 * @param maxTokens      max output tokens, or {@code null} for the provider default
 * @param profile        the model's API parameter dialect (never null)
 * @param maxInputTokens the model's context window minus the output cap the call sends, or {@code null} when the
 *                       catalog has no window for the model (a manual model, an unknown one)
 * @param diffTokenCap   the operator's Review diff limit in tokens, or {@code null} when it is not set
 */
public record LlmCredential(String type, String baseUrl, String apiKey, String model,
                            double temperature, Integer maxTokens, ModelParamProfile profile,
                            Integer maxInputTokens, Integer diffTokenCap) {

    public LlmCredential {
        if (profile == null) {
            profile = ModelParamProfile.legacyDefault();
        }
    }

    /** Convenience: no input limits, so the worker keeps the fixed prompt budgets. */
    public LlmCredential(String type, String baseUrl, String apiKey, String model,
                         double temperature, Integer maxTokens, ModelParamProfile profile) {
        this(type, baseUrl, apiKey, model, temperature, maxTokens, profile, null, null);
    }

    /** Convenience: the classic Chat Completions dialect (max_tokens + temperature). */
    public LlmCredential(String type, String baseUrl, String apiKey, String model,
                         double temperature, Integer maxTokens) {
        this(type, baseUrl, apiKey, model, temperature, maxTokens, ModelParamProfile.legacyDefault());
    }

    /** Binds the ciphertext to the PR workspace — distinct prefix from the SCM cred AAD. */
    public static String aad(String workspace) {
        return "worker-llm-cred:" + workspace;
    }
}
