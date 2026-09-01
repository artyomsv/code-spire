package dev.codespire.orchestrator.provider;

import java.util.List;

/**
 * Create/update payload for a provider. {@code secret} is the API token/password
 * (write-only); on update, a blank/absent secret keeps the stored one.
 */
public record ProviderInput(
        String name,
        String type,
        String baseUrl,
        String workspace,
        String authKind,
        String authUsername,
        String secret,
        String botAccountId,
        Boolean enabled,
        List<String> authors,
        String botUsername,
        String conversationLevel,
        /** REVIEWER or FACTORY (ADR-037). Null means REVIEWER. */
        String role) {

    /**
     * The pre-role shape. Every existing caller — the settings resource and seventeen test
     * fixtures — builds a reviewer, and none of them should have to know a role exists.
     */
    public ProviderInput(String name, String type, String baseUrl, String workspace, String authKind,
                         String authUsername, String secret, String botAccountId, Boolean enabled,
                         List<String> authors, String botUsername, String conversationLevel) {
        this(name, type, baseUrl, workspace, authKind, authUsername, secret, botAccountId, enabled,
                authors, botUsername, conversationLevel, null);
    }
}
