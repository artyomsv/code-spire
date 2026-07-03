package dev.codespire.scm.bitbucket;

/**
 * Adapter configuration. Values come from the host service's config (env /
 * secrets) — NO defaults for credentials, fail fast when unset (SECURITY.md).
 *
 * @param baseUrl        API base, {@code https://api.bitbucket.org/2.0} in production;
 *                       overridable for tests (WireMock)
 * @param botUsername    the ONE bot account's username (Basic auth)
 * @param botAppPassword the bot's App Password (scopes: pullrequest:write, repository:read)
 * @param webhookSecret  per-hook HMAC secret for X-Hub-Signature verification
 * @param botAccountId   the bot's stable account_id — ingress drops events it authored (ADR-013)
 */
public record BitbucketCloudConfig(String baseUrl,
                                   String botUsername,
                                   String botAppPassword,
                                   String webhookSecret,
                                   String botAccountId) {

    public BitbucketCloudConfig {
        require(baseUrl, "baseUrl");
        require(botUsername, "botUsername");
        require(botAppPassword, "botAppPassword");
        require(webhookSecret, "webhookSecret");
        require(botAccountId, "botAccountId");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Bitbucket Cloud config '" + name + "' is required");
        }
    }
}
