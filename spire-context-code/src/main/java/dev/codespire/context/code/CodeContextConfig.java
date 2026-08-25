package dev.codespire.context.code;

import java.util.Set;

/**
 * Configuration for one {@link SourceFileReader}, shared across the GitHub, GitLab and Bitbucket
 * implementations. From the encrypted context-provider registry via the brokered
 * {@code ContextCredential} — NO defaults for credentials, fail fast when unset (SECURITY.md).
 *
 * <p>Only {@code "bearer"} auth is accepted. This record carries a single opaque {@code secret}, which
 * fits a bearer token on every one of the three platforms (a GitHub/GitLab personal access token, or a
 * Bitbucket API access token — the recommended app-password replacement); it has no room for a
 * separate username, so Basic auth is out of scope here.
 *
 * @param baseUrl        API root, no trailing slash required
 * @param authKind       must be {@code "bearer"}
 * @param secret         the bearer token
 * @param pathAllowList  optional path prefixes a provider built on this reader may resolve; empty =
 *                       any path. Reserved for that provider to enforce — a reader has no notion of
 *                       "this repository's own files" versus "somewhere else in the tree".
 */
public record CodeContextConfig(String baseUrl, String authKind, String secret, Set<String> pathAllowList) {

    public CodeContextConfig {
        require(baseUrl, "baseUrl");
        require(authKind, "authKind");
        require(secret, "secret");
        if (!"bearer".equals(authKind)) {
            throw new IllegalArgumentException(
                    "Code context reader requires authKind 'bearer' (a personal access token), got '"
                            + authKind + "'.");
        }
        pathAllowList = pathAllowList == null ? Set.of() : Set.copyOf(pathAllowList);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CodeContextConfig '" + name + "' is required");
        }
    }
}
