package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.event.IntegrationEvent.ChecksChanged;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

/**
 * Reads a waiting head's checks and acts on {@link ChecksWaitDecision}. Reached two ways: a {@code check_suite} or
 * {@code status} webhook for the head ({@link #wake}), and the fallback sweep ({@link #resume}), which also finishes an
 * outcome a process claimed and did not complete. The webhook only wakes; the read decides, so a forged or
 * out-of-order event cannot start or skip a review by itself.
 *
 * <p>A wait whose run is no longer the review's is dropped without a read: the aggregate no longer reviews that head
 * in that round (a close cancelled it, a newer commit or a re-run replaced it) or the review was archived.
 */
@ApplicationScoped
public class ChecksWaitResolver {

    private static final Logger LOG = Logger.getLogger(ChecksWaitResolver.class);

    @Inject
    ChecksWaitStore waits;

    @Inject
    ChecksReader reader;

    @Inject
    ChecksWaitOutcomes outcomes;

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    ReviewProjection projection;

    /** A webhook said the checks on {@code event.headCommit()} changed; every review waiting on it reads them now. */
    public void wake(UUID repositoryId, ChecksChanged event) {
        for (ChecksWait wait : waits.waitingOn(repositoryId, event.headCommit())) {
            resolve(wait);
        }
    }

    /** A row the sweep claimed: a wait to read, or a claimed outcome to finish. */
    public void resume(ChecksWait wait) {
        switch (wait.stage()) {
            case ADMITTED -> readyAdmitted(wait);
            case WAITING -> resolve(wait);
            // Fenced by the run's round under the round lock in dispatch: an earlier run's claim never starts a later one.
            case STARTING, STARTING_UNFINISHED -> outcomes.dispatch(wait);
            case SKIPPING -> outcomes.finishSkip(wait);
            case STARTED, SKIPPED, TIMED_OUT -> LOG.debugf("review %s: run %d on %s is done", wait.reviewId(), wait.round(),
                    wait.head());
        }
    }

    public void resolve(ChecksWait wait) {
        if (!stillCurrent(wait)) {
            drop(wait);
            return;
        }
        ChecksVerdict verdict = reader.read(wait);
        switch (ChecksWaitDecision.decide(wait, verdict, Instant.now())) {
            case KEEP_WAITING -> LOG.debugf("review %s: checks on %s are %s", wait.reviewId(), wait.head(), verdict.kind());
            case START -> outcomes.start(wait);
            case START_UNFINISHED -> outcomes.startUnfinished(wait);
            case SKIP -> outcomes.skip(wait, verdict.failed());
        }
    }

    /**
     * An admitted run whose caller died before it began (its grace has passed: the sweep reads no younger one): made
     * readable and shown waiting while it is still the review's run, else dropped as it was read.
     */
    private void readyAdmitted(ChecksWait wait) {
        if (!stillCurrent(wait)) {
            drop(wait);
            return;
        }
        if (waits.ready(wait.reviewId(), wait.round()) && waits.markWaiting(wait.reviewId(), wait.round())) {
            projection.touch(wait.reviewId());
        }
    }

    /** Drops the row only as it was read: a claim another resolver made since is its recovery intent and stays. */
    private void drop(ChecksWait wait) {
        waits.drop(wait);
        LOG.infof("review %s: dropped the wait on %s; the review no longer waits on that head", wait.reviewId(),
                wait.head());
    }

    private boolean stillCurrent(ChecksWait wait) {
        if (projection.archived(wait.reviewId())) return false;
        ReviewState state = lifecycle.currentState(wait.reviewId());
        return state.isReviewing() && wait.head().equals(state.currentCommit()) && state.round() == wait.round();
    }
}
