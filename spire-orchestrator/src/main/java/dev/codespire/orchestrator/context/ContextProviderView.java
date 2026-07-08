package dev.codespire.orchestrator.context;

import java.time.Instant;

/**
 * A registered context provider as the API returns it — the stored secret is
 * NEVER included; {@code hasSecret} only reports whether one is set.
 */
public record ContextProviderView(
        String id,
        String name,
        String type,
        String baseUrl,
        String authKind,
        String username,
        String projectKeys,
        boolean hasSecret,
        boolean enabled,
        boolean isDefault,
        Instant createdAt) {
}
