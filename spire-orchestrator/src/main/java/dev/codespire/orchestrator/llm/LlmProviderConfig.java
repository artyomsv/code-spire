package dev.codespire.orchestrator.llm;

import java.util.UUID;

/**
 * A resolved LLM provider for internal use — carries the DECRYPTED api key, so it
 * never leaves the orchestrator except packed+encrypted onto a command. The LLM
 * analog of {@link dev.codespire.orchestrator.provider.ScmProvider}.
 */
public record LlmProviderConfig(
        UUID id,
        String name,
        String type,
        String baseUrl,
        String apiKey,
        String model,
        double temperature,
        Integer maxTokens,
        boolean enabled,
        boolean isDefault) {
}
