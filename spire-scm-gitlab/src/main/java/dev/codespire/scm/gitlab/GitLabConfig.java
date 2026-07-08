package dev.codespire.scm.gitlab;

/**
 * GitLab adapter configuration (SCM-MAPPING.md §GitLab). Values come from the
 * encrypted provider registry — NO defaults for credentials, fail fast when
 * unset (SECURITY.md). The UI pre-fills the base URL for gitlab.com; a
 * self-managed instance overrides it (the baseUrl-driven model, mirroring the
 * GitHub Enterprise case).
 *
 * <p>GitLab authenticates with a Bearer token: a personal or project access
 * token sent as {@code Authorization: Bearer ...} (GitLab accepts PATs on the
 * OAuth-compliant header as well as {@code PRIVATE-TOKEN}). There is no
 * Basic-auth path.
 *
 * @param baseUrl  API base, {@code https://gitlab.com/api/v4} for gitlab.com;
 *                 {@code https://<host>/api/v4} for a self-managed instance
 * @param apiToken the bot's Bearer token
 */
public record GitLabConfig(String baseUrl, String apiToken) {

    public GitLabConfig {
        require(baseUrl, "baseUrl");
        require(apiToken, "apiToken");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GitLab config '" + name + "' is required");
        }
    }
}
