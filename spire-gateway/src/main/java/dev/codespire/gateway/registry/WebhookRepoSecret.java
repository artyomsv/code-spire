package dev.codespire.gateway.registry;

/**
 * The result of creating or rotating a webhook registration: the saved
 * {@link WebhookRepoView} plus the freshly minted secret in PLAINTEXT — the ONLY time
 * the secret ever leaves the gateway. It is generated server-side (never by the
 * client), returned once so the operator can paste it into the provider's webhook
 * config, and thereafter only {@code hasSecret} is ever exposed. Never return this
 * type from list/get.
 */
public record WebhookRepoSecret(WebhookRepoView repo, String secret) {
}
