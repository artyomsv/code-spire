package dev.codespire.orchestrator.llm;

/**
 * Create/update payload for an LLM provider: a connection, with no model (the model is chosen where it is used).
 * {@code apiKey} is write-only; on update a blank/absent key keeps the stored one. {@code isDefault} is honored only
 * on create, and {@code defaultModel} only beside it: the default review model written together with the new default
 * provider. Use the {@code /default} endpoint to change the default later.
 */
public record LlmProviderInput(
        String name,
        String type,
        String baseUrl,
        String apiKey,
        Double temperature,
        Integer maxTokens,
        Boolean enabled,
        Boolean isDefault,
        String defaultModel) {
}
