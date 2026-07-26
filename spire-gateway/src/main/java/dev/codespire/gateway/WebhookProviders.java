package dev.codespire.gateway;

import java.util.Set;

/**
 * The provider types this gateway actually exposes a webhook endpoint for — the gateway's
 * counterpart to the orchestrator's {@code ProviderClients.SUPPORTED_TYPES}.
 *
 * <p>Composed from the per-provider resources' own constants rather than restating the names,
 * so the registry can never accept a type that has no endpoint to receive its deliveries. The
 * shared registry edge asks this question instead of carrying its own list, which is what kept
 * a second copy of the names drifting in provider-neutral code.
 *
 * <p>Narrower than {@code ScmType}, which also declares types no ingress implements yet.
 * Adding a provider means adding its resource and one line here — no edit to shared code.
 */
public final class WebhookProviders {

    public static final Set<String> SUPPORTED_TYPES = Set.of(
            GitHubWebhookResource.PROVIDER,
            GitLabWebhookResource.PROVIDER,
            BitbucketWebhookResource.PROVIDER);

    private WebhookProviders() {
    }
}
