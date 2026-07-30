package dev.codespire.context.gitlab;

import java.util.Set;

/**
 * GitLab issue-provider configuration, from the encrypted context-provider registry via the brokered
 * {@link dev.codespire.contract.context.ContextCredential} — NO defaults for credentials, fail fast
 * when unset (SECURITY.md).
 *
 * <p>{@code baseUrl} is the instance root ({@code https://gitlab.com}, or a self-managed host); the
 * client appends the {@code /api/v4/...} paths. Only {@code bearer} auth is accepted: a GitLab
 * personal access token works on the OAuth-compliant {@code Authorization} header, the same choice
 * {@code GitLabConfig} already documents for the SCM adapter.
 *
 * @param baseUrl            instance root, no {@code /api/v4} suffix
 * @param authKind           must be {@code "bearer"}
 * @param secret             personal access token
 * @param projectAllowList   optional group or {@code group/project} entries; empty = any project here
 */
public record GitLabIssueConfig(String baseUrl, String authKind, String secret,
                                Set<String> projectAllowList) {

    public GitLabIssueConfig {
        require(baseUrl, "baseUrl");
        require(authKind, "authKind");
        require(secret, "secret");
        if (!"bearer".equals(authKind)) {
            throw new IllegalArgumentException(
                    "GitLab issue context requires authKind 'bearer' (a personal access token), got '"
                            + authKind + "'.");
        }
        projectAllowList = projectAllowList == null ? Set.of() : Set.copyOf(projectAllowList);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GitLab issue config '" + name + "' is required");
        }
    }
}
