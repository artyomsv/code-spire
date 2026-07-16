package dev.codespire.orchestrator.provider;

import java.time.Instant;
import java.util.List;

/**
 * A registered SCM provider as the API returns it — the stored token is NEVER
 * included; {@code hasSecret} only reports whether one is set.
 */
public record ProviderView(
        String id,
        String name,
        String type,
        String baseUrl,
        String workspace,
        String authKind,
        String authUsername,
        boolean hasSecret,
        String botAccountId,
        boolean enabled,
        List<String> authors,
        Instant createdAt,
        String botUsername,
        String conversationLevel) {
}
