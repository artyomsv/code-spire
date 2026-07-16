package dev.codespire.gateway.registry;

/**
 * Create/update payload for a webhook registration. {@code scope} is
 * {@code repo} (target = {@code owner/repo}) or {@code org} (target = {@code owner},
 * one webhook for every repo in the org). The secret is NOT set by the client: it is
 * minted server-side on create and returned once (see {@link WebhookRepoSecret}), and
 * rotated via the dedicated rotate-secret endpoint — never carried in this payload.
 * The routing {@code webhookKey} is likewise generated server-side and returned in the
 * view. {@code providerType} is sent directly (the gateway does not know the
 * orchestrator's provider registry).
 */
public record WebhookRepoInput(
        String providerType,
        String scope,
        String target,
        Boolean enabled) {
}
