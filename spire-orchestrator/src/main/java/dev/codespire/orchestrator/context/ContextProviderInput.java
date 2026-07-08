package dev.codespire.orchestrator.context;

/**
 * Create/update payload for a context provider. {@code secret} is write-only; on
 * update a blank/absent secret keeps the stored one. {@code isDefault} is honored
 * only on create — use the {@code /default} endpoint to change the default later.
 */
public record ContextProviderInput(
        String name,
        String type,
        String baseUrl,
        String authKind,
        String username,
        String secret,
        String projectKeys,
        Boolean enabled,
        Boolean isDefault) {
}
