package dev.codespire.scm.bitbucket;

/**
 * Adapter configuration. Values come from the host service's config (env /
 * secrets) — NO defaults for credentials, fail fast when unset (SECURITY.md).
 *
 * <p>Authentication is EITHER a Bitbucket API access token ({@code apiToken},
 * sent as {@code Authorization: Bearer ...} — a workspace/project/repository
 * access token, the recommended app-password replacement) OR Basic auth
 * ({@code botUsername} + {@code botAppPassword} — an app password, or an
 * Atlassian API token with your account email as the username). The compact
 * constructor enforces that one of the two is present.
 *
 * @param baseUrl        API base, {@code https://api.bitbucket.org/2.0} in production;
 *                       overridable for tests (WireMock)
 * @param botUsername    the bot account's username or email (Basic auth)
 * @param botAppPassword the bot's App Password or Atlassian API token (Basic auth)
 * @param apiToken       a Bitbucket API access token (Bearer auth); takes precedence when set
 * @param webhookSecret  per-hook HMAC secret for X-Hub-Signature verification
 */
public record BitbucketCloudConfig(String baseUrl,
                                   String botUsername,
                                   String botAppPassword,
                                   String apiToken,
                                   String webhookSecret) {

    public BitbucketCloudConfig {
        require(baseUrl, "baseUrl");
        require(webhookSecret, "webhookSecret");
        boolean hasToken = notBlank(apiToken);
        boolean hasBasic = notBlank(botUsername) && notBlank(botAppPassword);
        if (!hasToken && !hasBasic) {
            throw new IllegalArgumentException("Bitbucket Cloud auth: provide an API access token "
                    + "(Bearer) OR username + app password (Basic)");
        }
    }

    /** Backward-compatible Basic-auth constructor (no Bearer access token). */
    public BitbucketCloudConfig(String baseUrl, String botUsername, String botAppPassword,
                                String webhookSecret) {
        this(baseUrl, botUsername, botAppPassword, null, webhookSecret);
    }

    /** True when a Bearer access token is configured (takes precedence over Basic). */
    public boolean usesBearerToken() {
        return notBlank(apiToken);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Bitbucket Cloud config '" + name + "' is required");
        }
    }
}
