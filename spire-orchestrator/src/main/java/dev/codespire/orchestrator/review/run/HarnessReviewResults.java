package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunTokenUsage;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.pipeline.ResultSaga;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * A harness review run's answer is finished by the review worker and becomes the review's outcome (harness reviews,
 * step 6). A {@code REVIEW} or {@code RECONCILE} answer is sent to the worker as {@code FinishHarnessReview}; the
 * worker's {@code HarnessReviewGenerated} comes back on the saga's per-review ordered consumer and is handed to
 * {@link ResultSaga#acceptHarnessOutcome} by {@link #acceptGenerated}. A failed run fails the review once (plan call
 * 11). Both halves read the round and act under the review's {@link ReviewRoundLock}: a result of an ended round is
 * dropped, and a round that cannot be read is thrown, never guessed (review of PR #368).
 *
 * <p>Never charged here: {@code ReviewRunCharges} already charged the run under the review's call slot. Follow-up
 * answers are only logged until step 7. The artifact's text is never logged: it is model output about the reviewed code
 * and may quote it (SECURITY.md).
 */
@ApplicationScoped
public class HarnessReviewResults implements ReviewRunResults {

    private static final Logger LOG = Logger.getLogger(HarnessReviewResults.class);

    /** How the run plane names a cancel; one that arrives before the run started is a failure with this cause. */
    static final String CANCELLED = "CANCELLED";

    static final String CANCELLED_ERROR = "Harness review run cancelled";

    /** The phase a failure to send the answer to the worker is recorded under, as the worker's own finish failure. */
    static final String FINISH_PHASE = "harness-finish";

    static final String CREDENTIAL_UNAVAILABLE = "The reviewer's forge account is unavailable, so the harness answer"
            + " could not be finished";

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

    /** The reviewer's packed forge credential a {@code FinishHarnessReview} carries. */
    @Inject
    WorkerCredentials credentials;

    /** Where a {@code FinishHarnessReview} is sent. */
    @Inject
    CommandsEmitter commands;

    /**
     * The worker's finished answer, applied under the round lock on the saga's ordered consumer. The saga's commit guard
     * cannot tell a forced re-run's round from the one before it on the same head; the round check here does.
     */
    public void acceptGenerated(IntegrationEvent.HarnessReviewGenerated generated) {
        rounds.holding(generated.reviewId(), () -> {
            if (!isCurrentRound(generated.reviewId(), generated.round(), "the finished harness review")) {
                return false;
            }
            saga.acceptHarnessOutcome(generated.toReviewGenerated());
            return true;
        });
    }

    public void acceptFailed(IntegrationEvent.HarnessReviewFailed failed) {
        // Compile stub for the red tests (review of PR #370, code-quality/H-1).
    }

    @Override
    public void accept(RunResult result, ReviewRunBinding binding) {
        log(result, binding);
        if (binding.kind() == ReviewRunBinding.Kind.FOLLOWUP) {
            return;
        }
        if (!(result instanceof RunReviewArtifact) && !(result instanceof RunResult.RunFailed)) {
            return;
        }
        // Read and acted on under the lock a forced re-run holds while it appends its round and replaces the run token:
        // a re-run keeps the commit, so the outcome path's commit checks cannot tell round 1's answer from round 2's.
        rounds.holding(binding.reviewId(), () -> {
            if (!isCurrentRound(binding.reviewId(), binding.round(), "review run " + result.runId())) {
                return false;
            }
            act(result, binding);
            return true;
        });
    }

    /**
     * True when {@code round} is the review's current one; false, logged, when it has ended. A round that cannot be read
     * is neither: thrown, so the consumer dead-letters the record for a replay. The steps before this one are idempotent
     * on a replay (status-guarded projection, deduplicated charge).
     */
    private boolean isCurrentRound(String reviewId, int round, String what) {
        int current = runs.roundOrUnknown(reviewId);
        if (current == ReviewRuns.UNKNOWN_RUN) {
            throw new IllegalStateException("The current round of " + reviewId + " could not be read, so " + what
                    + " was neither applied nor dropped; replay it from the dead-letter queue once the database answers");
        }
        if (round != current) {
            // A supersede, a close or a re-run ended this round: nothing of it is ever said to the review.
            LOG.infof("%s answered round %d of %s, whose current round is %d: dropped", what, round, reviewId, current);
            return false;
        }
        return true;
    }

    private void act(RunResult result, ReviewRunBinding binding) {
        switch (result) {
            case RunReviewArtifact answer -> finish(answer, binding);
            case RunResult.RunFailed failed -> saga.acceptHarnessOutcome(failure(failed, binding));
            default -> throw new IllegalStateException("A review run's outcome is an answer or a failure, not "
                    + result.getClass().getSimpleName());
        }
    }

    /**
     * The worker parses the answer with the API path's own parsers (plan, Task 4). An answer over the cap is sent with no
     * text and becomes a degraded review there, never a lost paid run. With no reviewer account no finish can be sent,
     * so the review fails terminally rather than staying "reviewing".
     */
    private void finish(RunReviewArtifact answer, ReviewRunBinding binding) {
        Optional<String> credential = credentials.packForReview(binding.reviewId());
        if (credential.isEmpty()) {
            saga.acceptHarnessOutcome(new IntegrationEvent.ReviewFailed(binding.reviewId(), binding.commit(),
                    FINISH_PHASE, CREDENTIAL_UNAVAILABLE, false, 1, false));
            return;
        }
        String model = runRows.modelOf(answer.runId()).orElse(UNRECORDED_MODEL);
        ModelUsage usage = RunTokenUsage.of(answer, model, maxReportedTokens);
        commands.emit(new ActionCommand.FinishHarnessReview(binding.reviewId(),
                ReviewIds.parse(binding.reviewId()).repo(), binding.prId(), binding.commit(), binding.round(),
                binding.kind(), answer.overCap() ? null : answer.text(), usage, credential.orElseThrow()));
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
