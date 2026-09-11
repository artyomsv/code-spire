package dev.codespire.orchestrator.context;

/**
 * A source references an account. Credentials are accepted only by the account API.
 */
public record ContextProviderInput(
        String name,
        String type,
        String baseUrl,
        String accountId,
        String projectKeys,
        Boolean enabled) {
}
