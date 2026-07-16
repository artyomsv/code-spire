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
        String conversationLevel) {
}
