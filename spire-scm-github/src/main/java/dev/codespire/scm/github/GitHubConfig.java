package dev.codespire.scm.github;

/**
 * GitHub adapter configuration (SCM-MAPPING.md §GitHub). Values come from the
 * host service's config / the encrypted provider registry — NO defaults for
 * credentials, fail fast when unset (SECURITY.md).
 *
 * <p>GitHub auth is always a Bearer token — a fine-grained or classic PAT, or a
 * GitHub App installation token (all sent as {@code Authorization: Bearer ...}).
 * There is no Basic-auth path (unlike Bitbucket).
 *
 * @param baseUrl       API base, {@code https://api.github.com} for github.com;
 *                      {@code https://<host>/api/v3} for GitHub Enterprise
 * @param apiToken      the bot's Bearer token
 * @param webhookSecret per-hook HMAC secret for X-Hub-Signature-256 (ingress only;
 *                      null/placeholder for the worker + manual-register paths)
 * @param botAccountId  the bot's stable user id — ingress drops events it authored (ADR-013)
 */
public record GitHubConfig(String baseUrl, String apiToken, String webhookSecret, String botAccountId) {

    public GitHubConfig {
        require(baseUrl, "baseUrl");
        require(apiToken, "apiToken");
        require(botAccountId, "botAccountId");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GitHub config '" + name + "' is required");
        }
    }
}
