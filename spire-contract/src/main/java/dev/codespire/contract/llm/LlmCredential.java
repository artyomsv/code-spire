package dev.codespire.contract.llm;

/**
 * The LLM credential brokered (KEK-encrypted) to the worker on a GenerateReview
 * command (ADR-018) — the LLM analog of {@link dev.codespire.contract.scm.ScmCredential}.
 * The orchestrator resolves the applicable LLM provider from the registry, packs
 * this record, and encrypts it; the worker decrypts it to build the model per
 * command. Never logged.
 *
 * @param type        provider type — {@code openai} (Phase 1); {@code anthropic}/{@code gemini} later
 * @param baseUrl     API base URL
 * @param apiKey      the API key (decrypted only inside the worker)
 * @param model       the model name
 * @param temperature sampling temperature
 * @param maxTokens   max output tokens, or {@code null} for the provider default
 * @param profile     the model's API parameter dialect (never null)
 */
public record LlmCredential(String type, String baseUrl, String apiKey, String model,
                            double temperature, Integer maxTokens, ModelParamProfile profile) {

    public LlmCredential {
        if (profile == null) {
            profile = ModelParamProfile.legacyDefault();
        }
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
