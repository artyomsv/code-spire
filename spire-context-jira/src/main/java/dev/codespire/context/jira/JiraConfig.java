package dev.codespire.context.jira;

import java.util.Set;

/**
 * Jira context-provider configuration. Values come from the encrypted
 * context-provider registry via the brokered {@link dev.codespire.contract.context.ContextCredential}
 * — NO defaults for credentials, fail fast when unset (SECURITY.md).
 *
 * <p>{@code baseUrl} is the site root (e.g. {@code https://acme.atlassian.net} for
 * Cloud, {@code https://jira.internal} for Data Center) — the client appends the
 * {@code /rest/api/2/...} paths and derives {@code /browse/<key>} links from it.
 * {@code authKind} is {@code "basic"} (Jira Cloud: {@code username} is the account
 * email, {@code secret} is an API token) or {@code "bearer"} (self-managed PAT —
 * {@code username} unused).
 *
 * @param baseUrl     site root, no trailing {@code /rest}
 * @param authKind    {@code "basic"} or {@code "bearer"}
 * @param username    account email (basic) — null/blank for bearer
 * @param secret      API token (basic) or personal access token (bearer)
 * @param projectKeys the instance's project keys (e.g. {@code ACME}) — candidate issue keys are
 *                    narrowed to these; empty = accept every well-formed key (the generic behavior)
 */
public record JiraConfig(String baseUrl, String authKind, String username, String secret,
                         Set<String> projectKeys) {

    public JiraConfig {
        require(baseUrl, "baseUrl");
        require(authKind, "authKind");
        require(secret, "secret");
        if ("basic".equals(authKind)) {
            require(username, "username");
        }
        projectKeys = projectKeys == null ? Set.of() : Set.copyOf(projectKeys);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Jira config '" + name + "' is required");
        }
    }
}
