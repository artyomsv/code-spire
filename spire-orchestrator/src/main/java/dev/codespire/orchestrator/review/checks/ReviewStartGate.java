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
 * <p>A repository that starts at once is admitted as before V121. For one that waits, {@link #admit} is one
 * critical section under the review's round lock: the aggregate accepts the run, the round it accepted (folded from the
 * history the append was made on) is recorded ADMITTED. A failure there fails the admission, so its event is
 * redelivered, and a redelivery that finds the run accepted but unrecorded records it. The caller then sets up the
 * review header for that round (status, run token, {@code run_round}), and {@link #begin} makes the round readable and
 * shows it waiting, so no start or skip lands before the header write that would undo it. Every accepted run, one
 * started at once included, carries its round to its header write, which never replaces a later round's header.
 */
@ApplicationScoped
public class ReviewStartGate {

    private static final Logger LOG = Logger.getLogger(ReviewStartGate.class);

    @Inject ReviewProjection projection;
    @Inject StartReviewSetting setting;
    @Inject ChecksReader reader;
    @Inject ChecksWaitStore waits;
    @Inject CommandsEmitter commands;
    @Inject ReviewLifecycleService lifecycle;
    @Inject ReviewRoundLock rounds;

    /** A run to admit: its request, and the fork flag its review's start sends. */
    public record Admission(String reviewId, RequestReview request, boolean untrusted) {
    }

    /** The run the aggregate accepted: its round, and whether it waits for its checks. */
    public record AcceptedRun(int round, boolean waits) {
    }

    /**
     * Admits the run; empty when the aggregate declined it. A repository that starts reviews at once (or a forge with no
     * check read) takes the path it took before V121: the aggregate alone, no lock and no row.
     */
    public Optional<AcceptedRun> admit(Admission admission) {
        Optional<UUID> waiting = waitingRepository(admission.reviewId());
        if (waiting.isEmpty()) return startsAtOnce(admission);
        return rounds.holding(admission.reviewId(), () -> admitWaiting(admission, waiting.orElseThrow()));
    }

    /** {@link #admit} for a caller that already holds the review's round lock (a re-run). */
    public Optional<AcceptedRun> admitHolding(Admission admission) {
        Optional<UUID> waiting = waitingRepository(admission.reviewId());
        return waiting.isEmpty() ? startsAtOnce(admission) : admitWaiting(admission, waiting.orElseThrow());
    }

    /**
     * Starts the accepted run at once, or, for a waiting run, makes it readable now that its review header is set up and
     * shows it waiting, while that very round is still the review's admitted one.
     */
    public void begin(FetchDiff fetch, AcceptedRun run) {
        if (!run.waits()) {
            commands.emit(fetch);
            return;
        }
        if (!waits.ready(new ChecksWait.Run(fetch.reviewId(), fetch.commit(), run.round()))) {
            LOG.infof("review %s: round %d no longer waits; its status stays", fetch.reviewId(), run.round());
            return;
        }
        projection.touch(fetch.reviewId());
        projection.appendEvent(fetch.reviewId(), "integration", "ChecksWaitStarted", "waiting for checks on " + fetch.commit());
        LOG.infof("review %s: waiting for the checks on %s before reviewing", fetch.reviewId(), fetch.commit());
    }

    /** The pre-V121 admission: the aggregate accepts the run, and the caller writes its header and starts it. */
    private Optional<AcceptedRun> startsAtOnce(Admission admission) {
        ReviewLifecycleService.Requested requested = lifecycle.request(admission.reviewId(), admission.request());
        return requested.accepted() ? Optional.of(new AcceptedRun(requested.state().round(), false)) : Optional.empty();
    }

    /**
     * Under the round lock: the aggregate accepts the run, its accepted round is recorded ADMITTED (no reader acts on it
     * until {@link #begin} makes it ready). A failure after the acceptance fails the admission; the redelivered event
     * then records the accepted but unrecorded run.
     */
    private Optional<AcceptedRun> admitWaiting(Admission admission, UUID repositoryId) {
        ReviewLifecycleService.Requested requested = lifecycle.request(admission.reviewId(), admission.request());
        if (!requested.accepted()) {
            recordIfUnrecorded(admission, requested.state(), repositoryId);
            return Optional.empty();
        }
        record(admitted(admission, requested.state(), repositoryId));
        return Optional.of(new AcceptedRun(requested.state().round(), true));
    }

    private void record(ChecksWait row) {
        waits.register(row);
    }

    private static ChecksWait admitted(Admission admission, ReviewState state, UUID repositoryId) {
        return ChecksWait.admitted(new ChecksWait.Run(admission.reviewId(), state.currentCommit(), state.round()),
                repositoryId).withUntrusted(admission.untrusted());
    }

    /**
     * A redelivered request whose first admission failed after the aggregate accepted it: the current run is this head's
     * and no row records it. It is recorded waiting at once: the redelivery's header write keeps status and token.
     */
    private void recordIfUnrecorded(Admission admission, ReviewState state, UUID repositoryId) {
        boolean current = state.isReviewing() && admission.request().commit().equals(state.currentCommit());
        if (!current || waits.admittedRound(admission.reviewId()) >= state.round()) return;
        record(admitted(admission, state, repositoryId).at(Stage.WAITING));
        if (waits.markWaiting(admission.reviewId(), state.round())) projection.touch(admission.reviewId());
        LOG.infof("review %s: recorded round %d's wait, which its first admission missed", admission.reviewId(),
                state.round());
    }

    /** The repository when this review waits: the setting asks for it and its forge can report its checks. */
    private Optional<UUID> waitingRepository(String reviewId) {
        return projection.repositoryIdOf(reviewId)
                .filter(id -> setting.of(id) == StartReview.AFTER_CHECKS && reader.available(reviewId));
    }
}
