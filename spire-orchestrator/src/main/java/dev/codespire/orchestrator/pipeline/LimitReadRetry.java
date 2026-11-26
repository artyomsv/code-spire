package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

/**
 * The bounded retry (C8) of a review whose Review diff limit could not be read on ContextAssembled, scheduled once
 * per failed run (PR 456, code-quality/M-4). The failure is the orchestrator's own, so Kafka may deliver the same
 * ContextAssembled again before the retry runs; the schedule is a compare-and-set on the persisted attempt, and a
 * duplicate that finds the attempt advanced or a retry pending does nothing, keeping the due time and the budget.
 */
@ApplicationScoped
class LimitReadRetry {

    private static final Logger LOG = Logger.getLogger(LimitReadRetry.class);

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewPolicy reviewPolicy;

    @Inject
    WorkerCredentials workerCredentials;

    @Inject
    TimelineBroadcaster timeline;

    /**
     * Schedule the next attempt, or recognise a duplicate delivery.
     *
     * @return false when the review must fail terminally instead (budget spent, or its provider gone), which the
     *         saga's {@code onReviewFailed} does with the same note and {@code RecordFailure} as any other failure
     */
    boolean scheduleOnce(ReviewFailed failed) {
        String reviewId = failed.reviewId();
        if (projection.retryPending(reviewId)) {
            recordDuplicate(reviewId);
            return true;
        }
        int attempt = projection.currentAttempt(reviewId);
        int budget = reviewPolicy.maxAttempts();
        if (attempt >= budget || workerCredentials.packForReview(reviewId).isEmpty()) {
            return false;
        }
        int next = attempt + 1;
        Duration delay = reviewPolicy.retryDelay(next);
        String note = "Transient failure at " + failed.phase() + " — retrying in " + RetryNotes.humanDelay(delay)
                + " (attempt " + next + "/" + budget + ").";
        if (!projection.scheduleRetryFrom(reviewId, attempt,
                new ReviewProjection.RetryPlan(next, note, Instant.now().plus(delay)))) {
            recordDuplicate(reviewId);
            return true;
        }
        LOG.warnf("Review %s could not read the Review diff limit at %s — auto-retry %d/%d in %ds (error: %s)",
                reviewId, failed.phase(), next, budget, delay.toSeconds(), failed.error());
        timeline.record("result", "retry:" + failed.phase(), reviewId,
                "retryable failure — auto-retry " + next + "/" + budget + " in " + RetryNotes.humanDelay(delay));
        return true;
    }

    private void recordDuplicate(String reviewId) {
        timeline.record("result", "dropped:ContextAssembled", reviewId,
                "duplicate delivery — the retry is already scheduled");
    }
}
