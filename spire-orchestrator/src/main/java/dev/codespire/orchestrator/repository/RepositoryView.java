package dev.codespire.orchestrator.repository;

import java.util.UUID;

/**
 * Selected accounts are visible even when disabled; this view never decrypts a credential.
 *
 * @param reviewLlmProviderId the LLM provider this repository's reviews and replies use; null means the
 *                            global default
 * @param reviewLlmModel      the model used with {@code reviewLlmProviderId}; null exactly when that is null
 * @param reviewHarness       the harness this repository's reviews run on (harness reviews, step 5); null means
 *                            the API
 * @param blockApprovalAt     the lowest severity whose open finding blocks approval and a factory land (V115)
 * @param verdictLabels       whether each posted review sets one {@code review: } label from its decided verdict (V120)
 */
public record RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace,
                             String slug, boolean enabled, long revision, Account reviewer, Account factory,
                             boolean reviewerMayApprove, UUID reviewLlmProviderId, String reviewLlmModel,
                             ReviewHarness reviewHarness, BlockApprovalAt blockApprovalAt, boolean verdictLabels) {
    public record Account(UUID id, String name, String role, String handle, String state) { }

    /** Call sites from before "Verdict labels" existed (V120); labels are off. */
    public RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace,
                          String slug, boolean enabled, long revision, Account reviewer, Account factory,
                          boolean reviewerMayApprove, UUID reviewLlmProviderId, String reviewLlmModel,
                          ReviewHarness reviewHarness, BlockApprovalAt blockApprovalAt) {
        this(id, scmType, forgeOrigin, workspace, slug, enabled, revision, reviewer, factory,
                reviewerMayApprove, reviewLlmProviderId, reviewLlmModel, reviewHarness, blockApprovalAt, false);
    }

    /** Call sites from before the review model moved off the provider; no model of their own. */
    public RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace, String slug,
                          boolean enabled, long revision, Account reviewer, Account factory,
                          boolean reviewerMayApprove, UUID reviewLlmProviderId, ReviewHarness reviewHarness,
                          BlockApprovalAt blockApprovalAt) {
        this(id, scmType, forgeOrigin, workspace, slug, enabled, revision, reviewer, factory,
                reviewerMayApprove, reviewLlmProviderId, null, reviewHarness, blockApprovalAt);
    }

    /**
     * This view with another API choice, every other component carried over. The one place besides the canonical
     * constructor that lists every component, so a component added later is not dropped by a rebuild.
     */
    public RepositoryView withReviewLlm(UUID providerId, String model) {
        return new RepositoryView(id, scmType, forgeOrigin, workspace, slug, enabled, revision, reviewer, factory,
                reviewerMayApprove, providerId, model, reviewHarness, blockApprovalAt, verdictLabels);
    }

    /** Call sites from before "Block approval at" existed; they block at {@code BLOCKER}. */
    public RepositoryView(UUID id, String scmType, String forgeOrigin, String workspace, String slug,
                          boolean enabled, long revision, Account reviewer, Account factory,
                          boolean reviewerMayApprove, UUID reviewLlmProviderId, ReviewHarness reviewHarness) {
        this(id, scmType, forgeOrigin, workspace, slug, enabled, revision, reviewer, factory,
                reviewerMayApprove, reviewLlmProviderId, reviewHarness, BlockApprovalAt.DEFAULT);
    }

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
