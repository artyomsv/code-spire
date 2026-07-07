package dev.codespire.orchestrator.llm;

/**
 * Create/update payload for an LLM provider. {@code apiKey} is write-only; on
 * update a blank/absent key keeps the stored one. {@code isDefault} is honored
 * only on create — use the {@code /default} endpoint to change the default later.
 */
public record LlmProviderInput(
        String name,
        String type,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Integer maxTokens,
        Boolean enabled,
        Boolean isDefault) {
}
