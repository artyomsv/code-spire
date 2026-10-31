package dev.codespire.orchestrator.repository;

import java.util.UUID;

/**
 * Credentials stay on accounts; a repository input carries selected account ids only.
 *
 * @param reviewLlmProviderId the LLM provider this repository's reviews and replies use; null means the
 *                            global default
 */
public record RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                              Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId,
                              Boolean reviewerMayApprove, UUID reviewLlmProviderId) {
    /** Call sites from before the review provider existed; null means the global default. */
    public RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                           Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId,
                           Boolean reviewerMayApprove) {
        this(scmType, forgeOrigin, workspace, slug, enabled, reviewerAccountId, factoryAccountId,
                reviewerMayApprove, null);
    }

    /** Call sites from before the setting existed (ADR-047); null means true, like {@code enabled}. */
    public RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                           Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId) {
        this(scmType, forgeOrigin, workspace, slug, enabled, reviewerAccountId, factoryAccountId, null);
    }
}
