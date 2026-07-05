package dev.codespire.orchestrator.provider;

import java.util.List;
import java.util.UUID;

/**
 * A resolved provider for internal use — carries the DECRYPTED secret, so it
 * never leaves the orchestrator. Used to build an SCM client for a matched PR.
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
        List<String> authors) {
}
