package dev.codespire.orchestrator.repository;

import java.util.UUID;

/**
 * Selected accounts are visible even when disabled; this view never decrypts a credential.
 *
 * @param reviewLlmProviderId the LLM provider this repository's reviews and replies use; null means the
 *                            global default
 * @param reviewHarness       the harness this repository's reviews run on (harness reviews, step 5); null means
 *                            the API
 */
public record RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace,
                             String slug, boolean enabled, long revision, Account reviewer, Account factory,
                             boolean reviewerMayApprove, UUID reviewLlmProviderId, ReviewHarness reviewHarness) {
    public record Account(UUID id, String name, String role, String handle, String state) { }

    /** Call sites from before the review harness existed; null means the API. */
    public RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace, String slug,
                          boolean enabled, long revision, Account reviewer, Account factory,
                          boolean reviewerMayApprove, UUID reviewLlmProviderId) {
        this(id, scmType, forgeOrigin, workspace, slug, enabled, revision, reviewer, factory,
                reviewerMayApprove, reviewLlmProviderId, null);
    }

    /** Call sites from before the review provider existed; null means the global default. */
    public RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace, String slug,
                          boolean enabled, long revision, Account reviewer, Account factory,
                          boolean reviewerMayApprove) {
        this(id, scmType, forgeOrigin, workspace, slug, enabled, revision, reviewer, factory,
                reviewerMayApprove, null);
    }

    /** Test fixtures from before the setting existed (ADR-047). */
    public RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace, String slug,
                          boolean enabled, long revision, Account reviewer, Account factory) {
        this(id, scmType, forgeOrigin, workspace, slug, enabled, revision, reviewer, factory, true);
    }
}
