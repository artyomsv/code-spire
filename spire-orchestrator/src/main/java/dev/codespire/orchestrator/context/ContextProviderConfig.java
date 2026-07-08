package dev.codespire.orchestrator.context;

import java.util.UUID;

/**
 * A resolved context provider for internal use — carries the DECRYPTED secret, so
 * it never leaves the orchestrator except packed+encrypted onto a GatherContext
 * command. The context analog of {@link dev.codespire.orchestrator.llm.LlmProviderConfig}.
 */
public record ContextProviderConfig(
        UUID id,
        String name,
        String type,
        String baseUrl,
        String authKind,
        String username,
        String secret,
        String projectKeys,
        boolean enabled,
        boolean isDefault) {
}
