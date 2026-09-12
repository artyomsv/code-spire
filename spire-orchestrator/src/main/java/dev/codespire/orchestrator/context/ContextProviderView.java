package dev.codespire.orchestrator.context;

import java.time.Instant;

/**
 * Source settings and the referenced account's status; no credential material.
 */
public record ContextProviderView(
        String id,
        String name,
        String type,
        String baseUrl,
        String accountId,
        String accountName,
        Boolean accountEnabled,
        String projectKeys,
        boolean enabled,
        Instant createdAt,
        Instant lastCheckAt,
        Boolean lastCheckOk,
        String lastCheckError) {
}
