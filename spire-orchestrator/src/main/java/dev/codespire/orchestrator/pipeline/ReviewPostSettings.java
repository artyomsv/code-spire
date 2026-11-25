package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.BlockApprovalAt;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.llm.ReviewRuns;
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
    /** What a review started on the timeout says when its checks could not be read at all (V121). */
    static final String CHECKS_UNREADABLE_NOTICE = "**The checks on this commit could not be read** for 60 minutes, so "
            + "whether CI passed is unknown and this review does not approve.

";
    /** What a review says when its run could not be identified, so whether its CI had finished is unknown. */
    static final String CHECKS_UNCONFIRMED_NOTICE = "**Whether CI had finished could not be confirmed for this review,** "
            + "so it does not approve.\n\n";

    /** The CI hold of the run being posted (V121), resolved once for the verdict, the notice and the label. */
    private enum CiHold { NONE, UNFINISHED, UNREADABLE, UNCONFIRMED }

    /** The saga's collaborators this reads. */
    record Sources(ReviewProjection projection, RepositoryRegistry repositories, RepositoryAccounts repositoryAccounts,
                   ChecksWaitStore checksWaits, ReviewRuns runs) {
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
            case UNFINISHED, UNREADABLE -> ReviewVerdictDecision.holdForUnfinishedChecks(decision);
            case UNCONFIRMED -> ReviewVerdictDecision.holdForUnconfirmedChecks(decision);
        };
    }

    /** The result as posted: a review started before its checks finished says so ahead of its summary. */
    ReviewResult posted(ReviewGenerated generated, ReviewResult visible) {
        CiHold held = ciHold(generated);
        if (held == CiHold.NONE) return visible;
        String notice = switch (held) {
            case UNFINISHED -> CHECKS_UNFINISHED_NOTICE;
            case UNREADABLE -> CHECKS_UNREADABLE_NOTICE;
            default -> CHECKS_UNCONFIRMED_NOTICE;
        };
        return visible.withSummary(notice + Objects.toString(visible.summary(), ""));
    }

    /**
     * The repository's "Verdict labels" (V120); off for an unknown repository. The worker labels from the assessment,
     * as before V121 (ADR-047: "Reviewer may approve" off still labels approved). An approving review that started
     * before its checks finished, or whose run cannot be identified, sends no label command, so no
     * {@code review: approved} reaches a review that does not approve; a blocking one still labels
     * {@code review: changes-requested}, which an unfinished build does not weaken.
     */
    boolean verdictLabels(ReviewGenerated generated, ReviewVerdictDecision.Decision decision) {
        boolean on = sources.projection().repositoryIdOf(generated.reviewId())
                .flatMap(id -> sources.repositories().verdictLabels(id)).orElse(false);
        return on && !(decision.assessment() == ReviewVerdict.APPROVE && ciHold(generated) != CiHold.NONE);
    }

    /**
     * Whether this very run (its head and round) started on the check timeout (V121); never for an unknown repository.
     * A review that never started a run on this head before its checks finished (every "Immediately" repository and
     * every forge with no check read) takes master's path with no further read. An earlier round's hold on the same
     * head is not this run's. A round that cannot be read leaves a hold on the head unresolved, which never approves.
     * Resolved once.
     */
    private CiHold ciHold(ReviewGenerated generated) {
        if (hold == null) {
            hold = sources.projection().repositoryIdOf(generated.reviewId()).map(id -> resolveHold(generated))
                    .orElse(CiHold.NONE);
        }
        return hold;
    }

    private CiHold resolveHold(ReviewGenerated generated) {
        Optional<ChecksWaitStore.Hold> held = sources.checksWaits().holdOn(generated.reviewId(), generated.commit());
        if (held.isEmpty()) return CiHold.NONE;
        int round = sources.runs().roundOrUnknown(generated.reviewId());
        if (round == ReviewRuns.UNKNOWN_RUN) return CiHold.UNCONFIRMED;
        if (held.orElseThrow().round() != round) return CiHold.NONE;
        return held.orElseThrow().readFailure() == null ? CiHold.UNFINISHED : CiHold.UNREADABLE;
    }
}
