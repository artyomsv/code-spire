package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.ReviewResult;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.BlockApprovalAt;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import dev.codespire.orchestrator.repository.RepositoryRegistry;

import java.util.Optional;
import java.util.UUID;

/**
 * What the repository's settings make of a generated review before it is posted: the verdict (ADR-047 D4, "Block
 * approval at", V115) and whether the worker labels it (V120). Package-private, built by {@link ResultSaga} from its
 * own collaborators, so the saga's hand-written test fakes reach it unchanged; moved out of the saga, which already
 * held more than a reader can.
 *
 * <p>Lambdas, not method references, throughout: a method reference dereferences its receiver when it is built, so
 * an empty repository id would still touch the registries (and a test fake that never set them would throw before the
 * decision ran).
 */
final class ReviewPostSettings {

    private final ReviewProjection projection;
    private final RepositoryRegistry repositories;
    private final RepositoryAccounts repositoryAccounts;

    ReviewPostSettings(ReviewProjection projection, RepositoryRegistry repositories, RepositoryAccounts repositoryAccounts) {
        this.projection = projection;
        this.repositories = repositories;
        this.repositoryAccounts = repositoryAccounts;
    }

    /**
     * ADR-047 D4: decided here because the projection's open count at the repository's "Block approval at"
     * (V115) is what the land gate reads, and it is already right at this point (recordOpenFindings ran first).
     * An unknown repository counts at BLOCKER.
     */
    ReviewVerdictDecision.Decision decide(String reviewId, ReviewResult visible) {
        Optional<UUID> repositoryId = projection.repositoryIdOf(reviewId);
        Boolean setting = repositoryId.flatMap(id -> repositories.reviewerMayApprove(id)).orElse(null);
        BlockApprovalAt level = repositoryId.flatMap(id -> repositories.blockApprovalAt(id))
                .orElse(BlockApprovalAt.DEFAULT);
        boolean identityKnown = repositoryId.map(id -> repositoryAccounts.reviewerIdentityKnown(id)).orElse(false);
        return ReviewVerdictDecision.decide(new ReviewVerdictDecision.VerdictFacts(visible.degraded(),
                projection.openBlocking(reviewId, level), level, setting, identityKnown));
    }

    /** The repository's "Verdict labels" (V120); off for an unknown repository. */
    boolean verdictLabels(String reviewId) {
        return projection.repositoryIdOf(reviewId).flatMap(id -> repositories.verdictLabels(id)).orElse(false);
    }
}
