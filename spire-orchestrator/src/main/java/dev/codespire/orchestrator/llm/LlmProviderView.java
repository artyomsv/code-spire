package dev.codespire.orchestrator.llm;

import java.time.Instant;

/**
 * A registered LLM provider as the API returns it — the stored api key is NEVER
 * included; {@code hasApiKey} only reports whether one is set.
 */
public record LlmProviderView(
        String id,
        String name,
        String type,
        String baseUrl,
        String model,
        double temperature,
        Integer maxTokens,
        boolean hasApiKey,
        boolean enabled,
        boolean isDefault,
        Instant createdAt) {
}
