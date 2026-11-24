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
 * {@code status} webhook for the head ({@link #wake}), and the fallback sweep ({@link ChecksWaitSweep}). The webhook
 * only wakes; the read decides, so a forged or out-of-order event cannot start or skip a review by itself.
 *
 * <p>A wait whose review moved on is dropped without a read: the aggregate no longer reviews that head (a close
 * cancelled it, a newer commit replaced it) or the review was archived.
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

    public void resolve(ChecksWait wait) {
        if (!stillWaiting(wait)) {
            waits.take(wait.reviewId(), wait.head());
            LOG.infof("review %s: dropped the wait on %s; the review no longer waits on that head", wait.reviewId(),
                    wait.head());
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

    private boolean stillWaiting(ChecksWait wait) {
        if (projection.archived(wait.reviewId())) return false;
        ReviewState state = lifecycle.currentState(wait.reviewId());
        return state.isReviewing() && wait.head().equals(state.currentCommit());
    }
}
