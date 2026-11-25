package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.BlockApprovalAt;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.review.labels.DesiredLabels;
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
    /** What a review says when its run could not be identified, so whether its CI had finished is unknown. */
    static final String CHECKS_UNCONFIRMED_NOTICE = "**Whether CI had finished could not be confirmed for this review,** "
            + "so it does not approve.\n\n";

    /** The CI hold of the run being posted (V121), resolved once for the verdict, the notice and the label. */
    private enum CiHold { NONE, UNFINISHED, UNCONFIRMED }

    /** The saga's collaborators this reads. */
    record Sources(ReviewProjection projection, RepositoryRegistry repositories, RepositoryAccounts repositoryAccounts,
                   ChecksWaitStore checksWaits, ReviewRuns runs, DesiredLabels desiredLabels) {
    }

    private final Sources sources;
    private CiHold hold;

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
        return switch (ciHold(generated)) {
            case NONE -> decision;
            case UNFINISHED -> ReviewVerdictDecision.holdForUnfinishedChecks(decision);
            case UNCONFIRMED -> ReviewVerdictDecision.holdForUnconfirmedChecks(decision);
        };
    }

    /** The result as posted: a review started before its checks finished says so ahead of its summary. */
    ReviewResult posted(ReviewGenerated generated, ReviewResult visible) {
        CiHold held = ciHold(generated);
        if (held == CiHold.NONE) return visible;
        String notice = held == CiHold.UNFINISHED ? CHECKS_UNFINISHED_NOTICE : CHECKS_UNCONFIRMED_NOTICE;
        return visible.withSummary(notice + Objects.toString(visible.summary(), ""));
    }

    /**
     * Whether this run's PostComments carries a label command (V120); off for an unknown repository. When on, the run's
     * desired verdict label is recorded first, under its round, and the worker converges the pull request to it. The
     * label follows the assessment (ADR-047: "Reviewer may approve" off still labels approved). A review that started
     * before its checks finished, or whose run cannot be identified, wants no {@code review: approved}; a blocking one
     * keeps {@code review: changes-requested}; a degraded one wants no verdict label. A run with no readable round
     * sends no label command.
     */
    boolean verdictLabels(ReviewGenerated generated, ReviewVerdictDecision.Decision decision) {
        String reviewId = generated.reviewId();
        boolean on = sources.projection().repositoryIdOf(reviewId)
                .flatMap(id -> sources.repositories().verdictLabels(id)).orElse(false);
        int round = on ? sources.runs().roundOrUnknown(reviewId) : ReviewRuns.UNKNOWN_RUN;
        if (round == ReviewRuns.UNKNOWN_RUN) return false;
        sources.desiredLabels().record(new DesiredLabels.DesiredLabel(reviewId, round, labelFor(generated, decision)));
        return true;
    }

    private String labelFor(ReviewGenerated generated, ReviewVerdictDecision.Decision decision) {
        if (decision.assessment() == ReviewVerdict.REQUEST_CHANGES) return VerdictLabel.CHANGES_REQUESTED.name();
        boolean approves = decision.assessment() == ReviewVerdict.APPROVE && ciHold(generated) == CiHold.NONE;
        return approves ? VerdictLabel.APPROVED.name() : null;
    }

    /**
     * Whether this very run (its head and round) started on the check timeout (V121); never for an unknown repository.
     * An earlier round's hold on the same head, left by a run before the repository switched to "Immediately", is not
     * this run's. A round that cannot be read leaves the hold unresolved, which never approves. Resolved once.
     */
    private CiHold ciHold(ReviewGenerated generated) {
        if (hold == null) {
            hold = sources.projection().repositoryIdOf(generated.reviewId()).map(id -> resolveHold(generated))
                    .orElse(CiHold.NONE);
        }
        return hold;
    }

    private CiHold resolveHold(ReviewGenerated generated) {
        int round = sources.runs().roundOrUnknown(generated.reviewId());
        if (round == ReviewRuns.UNKNOWN_RUN) return CiHold.UNCONFIRMED;
        ChecksWait.Run run = new ChecksWait.Run(generated.reviewId(), generated.commit(), round);
        return sources.checksWaits().startedUnfinished(run) ? CiHold.UNFINISHED : CiHold.NONE;
    }
}
