package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.orchestrator.readmodel.ReviewLimitDeferrals;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Optional;

/**
 * A review whose Review diff limit could not be read on ContextAssembled (PR 456, code-quality/M-4). It is neither a
 * failure nor a retry attempt: it is deferred ({@link ReviewLimitDeferrals}), so the retry sweeper re-runs it a little
 * later with the attempt counter unchanged. Redelivery of the same event can only write the same or an earlier due
 * time, so a duplicate, stale or concurrent delivery spends nothing and ends nothing. The bound is time: a failed read
 * more than {@link ReviewLimitDeferrals#WINDOW} after the head's first one fails the review.
 */
@ApplicationScoped
class LimitReadDeferral {

    private static final Logger LOG = Logger.getLogger(LimitReadDeferral.class);

    @Inject
    ReviewLimitDeferrals deferrals;

    @Inject
    ReviewProjection projection;

    @Inject
    TimelineBroadcaster timeline;

    /**
     * Defer the review, or close it when the window is spent.
     *
     * @return the terminal failure the saga records ({@code retryable=false}) once the setting has been unreadable for
     *         longer than the window; empty while the review waits, or when it is no longer running
     */
    Optional<ReviewFailed> defer(ContextAssembled e, AppSettingRepository.ReadFailed fault) {
        ReviewLimitDeferrals.Outcome outcome = deferrals.defer(
                new ReviewLimitDeferrals.Deferral(e.reviewId(), e.commit(), Instant.now()));
        switch (outcome) {
            case DEFERRED -> recordDeferred(e.reviewId(), fault);
            case NOT_RUNNING -> timeline.record("result", "dropped:ContextAssembled", e.reviewId(),
                    "the Review diff limit could not be read, and the review is no longer running");
            case EXPIRED -> {
                return Optional.of(new ReviewFailed(e.reviewId(), e.commit(), ResultSaga.GENERATE_REVIEW,
                        "The Review diff limit setting could not be read for "
                                + ReviewLimitDeferrals.WINDOW.toMinutes() + " minutes: " + fault.getMessage(),
                        false, 0));
            }
        }
        return Optional.empty();
    }

    /** The setting was read: a later failure on this review starts a new window. */
    void recovered(String reviewId) {
        deferrals.clear(reviewId);
    }

    private void recordDeferred(String reviewId, AppSettingRepository.ReadFailed fault) {
        LOG.warnf("Review %s could not read the Review diff limit — deferred %ds (error: %s)",
                reviewId, ReviewLimitDeferrals.DELAY.toSeconds(), fault.getMessage());
        timeline.record("result", "deferred:" + ResultSaga.GENERATE_REVIEW, reviewId,
                "the Review diff limit could not be read — retrying in "
                        + RetryNotes.humanDelay(ReviewLimitDeferrals.DELAY));
        projection.touch(reviewId);
    }
}
