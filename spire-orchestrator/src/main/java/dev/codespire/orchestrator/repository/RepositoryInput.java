package dev.codespire.orchestrator.repository;

import java.util.UUID;

/**
 * Credentials stay on accounts; a repository input carries selected account ids only.
 *
 * @param reviewLlmProviderId the LLM provider this repository's reviews and replies use; null means the
 *                            global default
 * @param reviewHarness       the harness this repository's reviews run on (harness reviews, step 5); null means
 *                            the API, on {@code reviewLlmProviderId} or the global default
 * @param blockApprovalAt     "Block approval at" (V115) as sent: {@code BLOCKER}, {@code MAJOR} or {@code MINOR};
 *                            null means {@code BLOCKER}, like a null {@code reviewerMayApprove} means on. A String,
 *                            not the enum, so an unknown value is refused with its reason (400) by
 *                            {@link BlockApprovalAt#parse}, where the JSON reader would refuse it without one
 */
public record RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                              Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId,
                              Boolean reviewerMayApprove, UUID reviewLlmProviderId, ReviewHarness reviewHarness,
                              String blockApprovalAt) {
    /** Call sites from before "Block approval at" existed; null means {@code BLOCKER}. */
    public RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                           Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId,
                           Boolean reviewerMayApprove, UUID reviewLlmProviderId, ReviewHarness reviewHarness) {
        this(scmType, forgeOrigin, workspace, slug, enabled, reviewerAccountId, factoryAccountId,
                reviewerMayApprove, reviewLlmProviderId, reviewHarness, null);
    }

    /** Call sites from before the review harness existed; null means the API. */
    public RepositoryInput(String scmType, String forgeOrigin, String workspace, String slug,
                           Boolean enabled, UUID reviewerAccountId, UUID factoryAccountId,
                           Boolean reviewerMayApprove, UUID reviewLlmProviderId) {
        this(scmType, forgeOrigin, workspace, slug, enabled, reviewerAccountId, factoryAccountId,
                reviewerMayApprove, reviewLlmProviderId, null);
    }

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
