package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.event.IntegrationEvent.ChecksFailedLabelled;
import dev.codespire.orchestrator.attention.LabelFailures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * What the worker did with a skipped run's {@code review: ci-failed} label (review waits for CI with Verdict labels
 * on): a history line, and the label failure the verdict label's attention row reads ({@code REVIEW_LABEL_FAILED}). A
 * set label clears that row and a failed one raises it, both only while the skipped run's header is the review's, so
 * a late report never speaks for a re-run's verdict label. A stale one wrote nothing on the forge and changes no row.
 */
@ApplicationScoped
public class ChecksFailedLabelOutcomes {

    @Inject
    ReviewProjection projection;

    @Inject
    LabelFailures labelFailures;

    public void record(ChecksFailedLabelled outcome) {
        projection.appendEvent(outcome.reviewId(), "result", typeOf(outcome), detailOf(outcome));
        if (outcome.outcome() != ChecksFailedLabelled.Outcome.STALE) {
            labelFailures.recordForRun(new LabelFailures.Run(outcome.reviewId(), outcome.commit(), outcome.round()),
                    outcome.labelFailure());
        }
        // appendEvent alone never bumps updated_at, which the detail page's live refresh watches.
        projection.touch(outcome.reviewId());
    }

    private static String typeOf(ChecksFailedLabelled outcome) {
        return switch (outcome.outcome()) {
            case LABELLED -> "ChecksFailedLabelled";
            case STALE -> "ChecksFailedLabelStale";
            case FAILED -> "ChecksFailedLabelFailed";
        };
    }

    private static String detailOf(ChecksFailedLabelled outcome) {
        return switch (outcome.outcome()) {
            case LABELLED -> "review: ci-failed set for the failed checks on " + outcome.commit();
            case STALE -> "review: ci-failed not set: the pull request moved past " + outcome.commit();
            case FAILED -> "review: ci-failed not set: " + outcome.labelFailure();
        };
    }
}
