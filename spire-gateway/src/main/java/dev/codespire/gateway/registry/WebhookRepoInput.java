package dev.codespire.gateway.registry;

/**
 * Create/update payload for a webhook registration. {@code scope} is
 * {@code repo} (target = {@code owner/repo}) or {@code org} (target = {@code owner},
 * one webhook for every repo in the org). {@code secret} is the HMAC/token secret
 * (write-only); on update a blank/absent secret keeps the stored one. The routing
 * {@code webhookKey} is generated server-side and returned in the view — not set by
 * the client. {@code providerType} is sent directly (the gateway does not know the
 * orchestrator's provider registry).
 */
public record WebhookRepoInput(
        String providerType,
        String scope,
        String target,
        String secret,
        Boolean enabled) {
}
