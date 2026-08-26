package dev.codespire.context.code;

import java.util.LinkedHashSet;
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

    /**
     * Parse the operator's optional path allow-list ("src/main/, src/allowed/") into trimmed
     * entries — split on comma or newline only, unlike the ticket/issue allow-lists
     * ({@code JiraTicketKeys.parseProjectKeys} and siblings), which also split on any whitespace: a
     * repository name never contains a space, but a file path plausibly can, so splitting on bare
     * whitespace here would silently sever such a path into two useless entries. Entries are also
     * kept exactly as typed rather than lower-cased — a file path is case-sensitive on every one of
     * the three platforms this reads from, so folding case would defeat the prefix match
     * {@link CodeContextProvider}'s enforcement depends on.
     */
    public static Set<String> parsePathAllowList(String raw) {
        Set<String> entries = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return entries;
        }
        for (String token : raw.split("[,\\n]+")) {
            String entry = token.strip();
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }
}
