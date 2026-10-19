package dev.codespire.orchestrator.repository;

import java.util.UUID;

/** Credentials stay on accounts; a repository input carries selected account ids only. */
public record RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                              Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId,
                              Boolean reviewerMayApprove) {
    /** Call sites from before the setting existed (ADR-047); null means true, like {@code enabled}. */
    public RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                           Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId) {
        this(scmType, forgeOrigin, workspace, slug, enabled, reviewerAccountId, factoryAccountId, null);
    }
}
