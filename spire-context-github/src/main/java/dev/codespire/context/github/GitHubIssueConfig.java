package dev.codespire.context.github;

import java.util.Set;

/**
 * GitHub issue-provider configuration, from the encrypted context-provider registry via the brokered
 * {@link dev.codespire.contract.context.ContextCredential} — NO defaults for credentials, fail fast
 * when unset (SECURITY.md).
 *
 * <p>{@code baseUrl} is the API root: {@code https://api.github.com} for github.com, or
 * {@code https://ghe.internal/api/v3} for GitHub Enterprise Server. Only {@code bearer} auth is
 * accepted — GitHub's basic auth is deprecated and a personal access token works on the bearer
 * header, so accepting {@code basic} would only offer a way to configure something that fails later.
 *
 * @param baseUrl        API root, no trailing slash required
 * @param authKind       must be {@code "bearer"}
 * @param secret         personal access token, classic or fine-grained
 * @param repoAllowList  optional owner or {@code owner/repo} entries; empty = any repository on this host
 */
public record GitHubIssueConfig(String baseUrl, String authKind, String secret,
                                Set<String> repoAllowList) {

    public GitHubIssueConfig {
        require(baseUrl, "baseUrl");
        require(authKind, "authKind");
        require(secret, "secret");
        if (!"bearer".equals(authKind)) {
            throw new IllegalArgumentException(
                    "GitHub issue context requires authKind 'bearer' (a personal access token), got '"
                            + authKind + "'. GitHub's basic auth is deprecated.");
        }
        repoAllowList = repoAllowList == null ? Set.of() : Set.copyOf(repoAllowList);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GitHub issue config '" + name + "' is required");
        }
    }
}
