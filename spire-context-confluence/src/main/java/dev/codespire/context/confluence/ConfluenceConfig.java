package dev.codespire.context.confluence;

import java.util.Set;

/**
 * Confluence context-provider configuration. Values come from the encrypted
 * context-provider registry via the brokered {@link dev.codespire.contract.context.ContextCredential}
 * — NO defaults for credentials, fail fast when unset (SECURITY.md).
 *
 * <p>{@code baseUrl} is the wiki root — {@code https://acme.atlassian.net/wiki} for
 * Cloud, {@code https://confluence.internal} for Data Center — and the client
 * appends the {@code /rest/api/content/...} paths. {@code authKind} is
 * {@code "basic"} (Cloud: {@code username} is the account email, {@code secret} is
 * an API token) or {@code "bearer"} (self-managed PAT — {@code username} unused),
 * exactly as for Jira.
 *
 * <p>{@code spaceKeys} is an OPTIONAL post-fetch allow-list (e.g. {@code ENG, DOC}):
 * a resolved page whose space key is not in the set is dropped. Empty = accept
 * every page reachable from the PR's links (the generic behavior). It reuses the
 * registry's generic {@code projectKeys} column, so no schema change is needed.
 *
 * @param baseUrl   wiki root, no trailing {@code /rest}
 * @param authKind  {@code "basic"} or {@code "bearer"}
 * @param username  account email (basic) — null/blank for bearer
 * @param secret    API token (basic) or personal access token (bearer)
 * @param spaceKeys optional space-key allow-list; empty = accept every space
 */
public record ConfluenceConfig(String baseUrl, String authKind, String username, String secret,
                               Set<String> spaceKeys) {

    public ConfluenceConfig {
        require(baseUrl, "baseUrl");
        require(authKind, "authKind");
        require(secret, "secret");
        if ("basic".equals(authKind)) {
            require(username, "username");
        }
        spaceKeys = spaceKeys == null ? Set.of() : Set.copyOf(spaceKeys);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Confluence config '" + name + "' is required");
        }
    }
}
