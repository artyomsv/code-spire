package dev.codespire.gateway.registry;

import java.time.Instant;

/**
 * A webhook registration as the API returns it — the secret is NEVER included
 * ({@code hasSecret} only reports whether one is set). The {@code webhookKey} IS
 * returned: it is the (non-secret) URL path segment the operator needs to build the
 * payload URL. {@code scope} is {@code repo} or {@code org}; {@code target} is
 * {@code owner/repo} or {@code owner} accordingly.
 */
public record WebhookRepoView(
        String id,
        String providerType,
        String scope,
        String target,
        String webhookKey,
        boolean hasSecret,
        boolean enabled,
        Instant createdAt) {
}
