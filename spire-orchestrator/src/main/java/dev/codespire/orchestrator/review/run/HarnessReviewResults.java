package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.llm.FindingsParser;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunTokenUsage;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.ResultSaga;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * A harness review run's answer becomes the review's outcome (harness reviews, step 5): the artifact is parsed into
 * findings with the API review's own parser and handed to {@link ResultSaga#acceptHarnessOutcome}, a failed run fails
 * the review once, and a result of an ended round is dropped. The round is read and the outcome applied under the
 * review's {@link ReviewRoundLock}; a round that cannot be read is thrown, never guessed (review of PR #368).
 *
 * <p>Never charged here: {@code ReviewRunCharges} already charged the run under the review's call slot. Reconcile and
 * follow-up answers are only logged until steps 6 and 7. The artifact's text is never logged: it is model output about
 * the reviewed code and may quote it (SECURITY.md).
 */
@ApplicationScoped
public class HarnessReviewResults implements ReviewRunResults {

    private static final Logger LOG = Logger.getLogger(HarnessReviewResults.class);

    /** How the run plane names a cancel; one that arrives before the run started is a failure with this cause. */
    static final String CANCELLED = "CANCELLED";

    static final String CANCELLED_ERROR = "Harness review run cancelled";

    static final String OVER_CAP_SUMMARY = "The harness answer was larger than 256 KiB and was not read.";

    /** The model named when the run's row cannot be read, as {@code ReviewRunCharges} names it. */
    private static final String UNRECORDED_MODEL = "UNRECORDED";

    @Inject
    ResultSaga saga;

    /** The fail-closed round read: a failed read is {@code UNKNOWN_RUN}, never the ledger's run 1. */
    @Inject
    ReviewRuns runs;

    @Inject
    ReviewRoundLock rounds;

    @Inject
    FactoryRunProjection runRows;

    /** The ceiling {@code ReviewRunCharges} reads usage under, so the review's usage and its charge agree. */
    @ConfigProperty(name = "spire.run.max-reported-tokens", defaultValue = "-1")
    long maxReportedTokens;

    @Override
    public void accept(RunResult result, ReviewRunBinding binding) {
        log(result, binding);
        if (binding.kind() != ReviewRunBinding.Kind.REVIEW) {
            return;
        }
        if (!(result instanceof RunReviewArtifact) && !(result instanceof RunResult.RunFailed)) {
            return;
        }
        // Read and acted on under the lock a forced re-run holds while it appends its round and replaces the run token:
        // a re-run keeps the commit, so the outcome path's commit checks cannot tell round 1's answer from round 2's.
        rounds.holding(binding.reviewId(), () -> applyIfCurrent(result, binding));
    }

    /** True when the answer was handed to the review; false when its round has ended. */
    private boolean applyIfCurrent(RunResult result, ReviewRunBinding binding) {
        int current = runs.roundOrUnknown(binding.reviewId());
        if (current == ReviewRuns.UNKNOWN_RUN) {
            // Neither applied nor dropped: thrown, so the run-results consumer dead-letters the result for a replay. The
            // steps before this one are idempotent on a replay (status-guarded projection, deduplicated charge).
            throw new IllegalStateException("The current round of " + binding.reviewId() + " could not be read, so"
                    + " review run " + result.runId() + "'s outcome was neither applied nor dropped; replay it from the"
                    + " dead-letter queue once the database answers");
        }
        if (binding.round() != current) {
            // A supersede, a close or a re-run ended this round: its answer or its cancel is never said to the review.
            LOG.infof("review run %s answered round %d of %s, whose current round is %d: dropped", result.runId(),
                    binding.round(), binding.reviewId(), current);
            return false;
        }
        saga.acceptHarnessOutcome(outcome(result, binding));
        return true;
    }

    private IntegrationEvent outcome(RunResult result, ReviewRunBinding binding) {
        return switch (result) {
            case RunReviewArtifact answer -> new IntegrationEvent.ReviewGenerated(binding.reviewId(), binding.prId(),
                    binding.commit(), resultOf(answer));
            case RunResult.RunFailed failed -> failure(failed, binding);
            default -> throw new IllegalStateException("A review run's outcome is an answer or a failure, not "
                    + result.getClass().getSimpleName());
        };
    }

    /** An unparsable answer is degraded inside the parser; one over the cap is degraded here, never a lost paid run. */
    private ReviewResult resultOf(RunReviewArtifact answer) {
        String model = runRows.modelOf(answer.runId()).orElse(UNRECORDED_MODEL);
        ModelUsage usage = RunTokenUsage.of(answer, model, maxReportedTokens);
        if (answer.overCap()) {
            return ReviewResult.degraded(OVER_CAP_SUMMARY, usage);
        }
        return FindingsParser.parse(new Completion(answer.text(), usage, false));
    }

    /**
     * Retry budget 1: the operator re-runs the review. Never credential-rejected: that flag marks the REVIEWER's forge
     * account, and a refused seat is already marked by the run plane's credential feedback.
     */
    private static IntegrationEvent failure(RunResult.RunFailed failed, ReviewRunBinding binding) {
        String error = CANCELLED.equals(failed.cause()) ? CANCELLED_ERROR
                : "Harness review run failed: " + failed.cause();
        return new IntegrationEvent.ReviewFailed(binding.reviewId(), binding.commit(), ResultSaga.HARNESS_PHASE, error,
                false, 1, false);
    }

    private static void log(RunResult result, ReviewRunBinding binding) {
        switch (result) {
            case RunReviewArtifact answer -> LOG.infof("review run %s answered %s %s on %s: %s",
                    answer.runId(), binding.kind(), binding.reviewId(), binding.commit(), describe(answer));
            case RunResult.RunFailed failed -> LOG.infof("review run %s for %s %s failed: %s",
                    failed.runId(), binding.kind(), binding.reviewId(), failed.cause());
            default -> LOG.debugf("review run %s for %s: %s", result.runId(), binding.reviewId(),
                    result.getClass().getSimpleName());
        }
    }

    private static String describe(RunReviewArtifact answer) {
        return answer.overCap() ? "over the " + RunReviewArtifact.MAX_TEXT_BYTES + " byte cap, no text"
                : answer.text().length() + " characters";
    }
}
