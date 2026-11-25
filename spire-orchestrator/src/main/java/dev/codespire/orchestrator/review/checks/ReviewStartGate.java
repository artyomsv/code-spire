package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.command.RecordCommand.RequestReview;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.StartReview;
import dev.codespire.orchestrator.review.checks.ChecksWait.Stage;
import dev.codespire.orchestrator.review.run.ReviewRoundLock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Admits a review run and starts it at once or after its checks finish (V121,
 * docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md).
 *
 * <p>{@link #admit} is one critical section under the review's round lock: the aggregate accepts the run, the round it
 * accepted (folded from the history the append was made on) is recorded as the review's admission row, waiting or
 * started, and every earlier run's open ci-failed receipt is closed. A failure there fails the admission, so its event
 * is redelivered, and a redelivery that finds the run accepted but unrecorded records it. {@link #begin} then only acts
 * on the run it was handed: it starts it, or shows it waiting while that very round still waits. Nothing re-reads a
 * "current" round, so a late caller never takes a newer run's identity.
 */
@ApplicationScoped
public class ReviewStartGate {

    private static final Logger LOG = Logger.getLogger(ReviewStartGate.class);

    @Inject
    ReviewProjection projection;

    @Inject
    StartReviewSetting setting;

    @Inject
    ChecksReader reader;

    @Inject
    ChecksWaitStore waits;

    @Inject
    CommandsEmitter commands;

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    SkippedRunReceipts receipts;

    @Inject
    ReviewRoundLock rounds;

    /** A run to admit: its request, and the fork flag its review's start sends. */
    public record Admission(String reviewId, RequestReview request, boolean untrusted) {
    }

    /** The run the aggregate accepted: its round, and whether it waits for its checks. */
    public record AcceptedRun(int round, boolean waits) {
    }

    /** Admits the run under the review's round lock; empty when the aggregate declined it. */
    public Optional<AcceptedRun> admit(Admission admission) {
        return rounds.holding(admission.reviewId(), () -> admitHolding(admission));
    }

    /** {@link #admit} for a caller that already holds the review's round lock (a re-run). */
    public Optional<AcceptedRun> admitHolding(Admission admission) {
        ReviewLifecycleService.Requested requested = lifecycle.request(admission.reviewId(), admission.request());
        if (!requested.accepted()) {
            recordIfUnrecorded(admission, requested.state());
            return Optional.empty();
        }
        return Optional.of(record(admission, requested.state()));
    }

    /** Starts the accepted run at once, or shows it waiting while that round still waits. */
    public void begin(FetchDiff fetch, AcceptedRun run) {
        if (!run.waits()) {
            commands.emit(fetch);
            return;
        }
        if (!waits.markWaiting(fetch.reviewId(), run.round())) {
            LOG.infof("review %s: round %d no longer waits; its status stays", fetch.reviewId(), run.round());
            return;
        }
        projection.touch(fetch.reviewId());
        projection.appendEvent(fetch.reviewId(), "integration", "ChecksWaitStarted", "waiting for checks on " + fetch.commit());
        LOG.infof("review %s: waiting for the checks on %s before reviewing", fetch.reviewId(), fetch.commit());
    }

    /** Under the round lock: the accepted round's admission row and the earlier runs' closed label receipts. */
    private AcceptedRun record(Admission admission, ReviewState state) {
        receipts.supersede(admission.reviewId());
        Optional<UUID> repository = projection.repositoryIdOf(admission.reviewId());
        boolean waitsForChecks = repository.filter(id -> waitsForChecks(admission.reviewId(), id)).isPresent();
        Stage stage = waitsForChecks ? Stage.WAITING : Stage.STARTED;
        repository.ifPresent(id -> waits.register(ChecksWait.admitted(new ChecksWait.Run(admission.reviewId(),
                state.currentCommit(), state.round()), id, stage).withUntrusted(admission.untrusted())));
        return new AcceptedRun(state.round(), waitsForChecks);
    }

    /**
     * A redelivered request whose first admission failed after the aggregate accepted it: the current run is this head's
     * and no row records it. A waiting run is recorded and shown waiting; a run that starts at once is left as before.
     */
    private void recordIfUnrecorded(Admission admission, ReviewState state) {
        boolean current = state.isReviewing() && admission.request().commit().equals(state.currentCommit());
        if (!current || waits.admittedRound(admission.reviewId()) >= state.round()) return;
        Optional<UUID> repository = projection.repositoryIdOf(admission.reviewId())
                .filter(id -> waitsForChecks(admission.reviewId(), id));
        if (repository.isEmpty()) return;
        record(admission, state);
        if (waits.markWaiting(admission.reviewId(), state.round())) projection.touch(admission.reviewId());
        LOG.infof("review %s: recorded round %d's wait, which its first admission missed", admission.reviewId(),
                state.round());
    }

    /** Whether this review waits: the setting asks for it and its forge can report its checks. */
    private boolean waitsForChecks(String reviewId, UUID repositoryId) {
        return setting.of(repositoryId) == StartReview.AFTER_CHECKS && reader.available(reviewId);
    }
}
