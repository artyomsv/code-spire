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
        Boolean enabled,
        String forgeOrigin,
        java.util.UUID repositoryId,
        dev.codespire.contract.event.RepositoryEventKind eventKind,
        java.util.UUID sourceId) {
    public WebhookRepoInput {
        if (forgeOrigin != null) forgeOrigin = dev.codespire.contract.scm.ForgeOrigin.of(forgeOrigin);
    }

    /** Legacy callers omit origin; updates preserve an origin already supplied by an operator. */
    public WebhookRepoInput(String providerType, String scope, String target, Boolean enabled) {
        this(providerType, scope, target, enabled, null, null, null, null);
    }

    public WebhookRepoInput(String providerType, String scope, String target, Boolean enabled, String forgeOrigin) {
        this(providerType, scope, target, enabled, forgeOrigin, null, null, null);
    }
}
