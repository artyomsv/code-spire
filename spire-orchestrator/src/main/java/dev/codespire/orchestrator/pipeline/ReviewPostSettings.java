package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.BlockApprovalAt;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.review.checks.ChecksWait;
import dev.codespire.orchestrator.review.checks.ChecksWaitStore;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What the repository's settings make of a generated review before it is posted: the verdict (ADR-047 D4, "Block
 * approval at", V115), whether the worker labels it (V120), and whether it started before its checks finished (V121).
 * Package-private, built by {@link ResultSaga} from its own collaborators, so the saga's hand-written test fakes reach
 * it unchanged; moved out of the saga, which already held more than a reader can.
 *
 * <p>Lambdas, not method references, throughout: a method reference dereferences its receiver when it is built, so
 * an empty repository id would still touch the registries (and a test fake that never set them would throw before the
 * decision ran).
 */
final class ReviewPostSettings {

    /** What a review started on the check timeout says before its own summary (review waits for CI). */
    static final String CHECKS_UNFINISHED_NOTICE = "**CI had not finished when this review started.** The checks on "
            + "this commit were still pending after 60 minutes, so this review does not approve.\n\n";

    /** The saga's collaborators this reads. */
    record Sources(ReviewProjection projection, RepositoryRegistry repositories, RepositoryAccounts repositoryAccounts,
                   ChecksWaitStore checksWaits, ReviewRuns runs) {
    }

    private final Sources sources;

    ReviewPostSettings(Sources sources) {
        this.sources = sources;
    }

    /**
     * ADR-047 D4: decided here because the projection's open count at the repository's "Block approval at"
     * (V115) is what the land gate reads, and it is already right at this point (recordOpenFindings ran first).
     * An unknown repository counts at BLOCKER. A review that started before its checks finished never approves.
     */
    ReviewVerdictDecision.Decision decide(ReviewGenerated generated, ReviewResult visible) {
        String reviewId = generated.reviewId();
        Optional<UUID> repositoryId = sources.projection().repositoryIdOf(reviewId);
        Boolean setting = repositoryId.flatMap(id -> sources.repositories().reviewerMayApprove(id)).orElse(null);
        BlockApprovalAt level = repositoryId.flatMap(id -> sources.repositories().blockApprovalAt(id))
                .orElse(BlockApprovalAt.DEFAULT);
        boolean identityKnown = repositoryId.map(id -> sources.repositoryAccounts().reviewerIdentityKnown(id))
                .orElse(false);
        ReviewVerdictDecision.Decision decision = ReviewVerdictDecision.decide(new ReviewVerdictDecision.VerdictFacts(
                visible.degraded(), sources.projection().openBlocking(reviewId, level), level, setting, identityKnown));
        return checksUnfinished(generated) ? ReviewVerdictDecision.holdForUnfinishedChecks(decision) : decision;
    }

    /** The result as posted: a review started before its checks finished says so ahead of its summary. */
    ReviewResult posted(ReviewGenerated generated, ReviewResult visible) {
        if (!checksUnfinished(generated)) return visible;
        return visible.withSummary(CHECKS_UNFINISHED_NOTICE + Objects.toString(visible.summary(), ""));
    }

    /**
     * The repository's "Verdict labels" (V120); off for an unknown repository. A review that started before its checks
     * finished sets no {@code review: approved}: an approval it never posted must not reach the label either. It keeps
     * {@code review: changes-requested}, which an unfinished build does not weaken. The labels of a review held by
     * "Reviewer may approve" still follow its assessment (ADR-047).
     */
    boolean verdictLabels(ReviewGenerated generated, ReviewVerdictDecision.Decision decision) {
        boolean on = sources.projection().repositoryIdOf(generated.reviewId())
                .flatMap(id -> sources.repositories().verdictLabels(id)).orElse(false);
        return on && !(decision.assessment() == ReviewVerdict.APPROVE && checksUnfinished(generated));
    }

    /**
     * Whether this very run (its head and round) started on the check timeout (V121); never for an unknown repository.
     * An earlier round's hold on the same head, left by a run before the repository switched to "Immediately", is not
     * this run's.
     */
    private boolean checksUnfinished(ReviewGenerated generated) {
        String reviewId = generated.reviewId();
        return sources.projection().repositoryIdOf(reviewId)
                .map(id -> sources.checksWaits().startedUnfinished(
                        new ChecksWait.Run(reviewId, generated.commit(), sources.runs().roundOrUnknown(reviewId))))
                .orElse(false);
    }
}
