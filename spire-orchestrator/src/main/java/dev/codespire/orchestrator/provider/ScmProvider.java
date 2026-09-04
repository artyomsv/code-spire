package dev.codespire.orchestrator.provider;

import java.util.List;
import java.util.UUID;

/**
 * A resolved provider for internal use — carries the DECRYPTED secret, so it
 * never leaves the orchestrator. Used to build an SCM client for a matched PR.
 *
 * <p><b>{@code role} is carried because a caller needs to ASSERT it, not merely to have keyed on
 * it.</b> {@code ProviderRegistry.resolve} has always filtered {@code WHERE role = ?}, so the row
 * knew — the mapper just dropped the column, which left every consumer trusting that whoever
 * resolved the provider asked for the right role. That is fine while one call site does the
 * resolving and becomes a silent misattribution the moment two do: a branch pushed as the factory
 * account with a pull request opened as the reviewer belongs to neither.
 */
public record ScmProvider(
        UUID id,
        String name,
        String type,
        String baseUrl,
        String workspace,
        String authKind,
        String authUsername,
        String secret,
        String botAccountId,
        boolean enabled,
        List<String> authors,
        String botUsername,
        String conversationLevel,
        ProviderRole role) {
}
