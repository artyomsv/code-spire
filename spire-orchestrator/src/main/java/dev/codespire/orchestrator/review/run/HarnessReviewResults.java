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
 * {@link ResultSaga#acceptHarnessOutcome} by {@link #acceptGenerated}; the worker's {@code HarnessReviewFailed} by
 * {@link #acceptFailed}. A failed run fails the review once (plan call 11). Every path reads the round and acts under
 * the review's {@link ReviewRoundLock}: a result of an ended round is dropped, and a round that cannot be read is
 * thrown, never guessed (reviews of PR #368 and PR #370).
 *
 * <p>Never charged here: {@code ReviewRunCharges} already charged the run under the review's call slot. A reply run's
 * result goes to {@link HarnessReplyResults} with no round check: a reply answers a thread, not a round (step 7). The
 * artifact's text is never logged: it is model output about the reviewed code and may quote it (SECURITY.md).
 */
@ApplicationScoped
public class HarnessReviewResults implements ReviewRunResults {

    private static final Logger LOG = Logger.getLogger(HarnessReviewResults.class);

    /** How the run plane names a cancel; one that arrives before the run started is a failure with this cause. */
    static final String CANCELLED = "CANCELLED";

    static final String CANCELLED_ERROR = "Harness review run cancelled";

    /**
     * The run worker's own refusal to start an untrusted run with no credential proxy (harness reviews, step 8): its
     * cause and the text its detail carries. Duplicated from the run worker, which the orchestrator does not depend on.
     */
    static final String BAD_COMMAND = "BAD_COMMAND";

    static final String FORK_PROXY_REQUIRED = "fork_requires_credential_proxy";

    /**
     * True when the run worker refused to start the run because a fork review would have run unproxied. The detail is
     * the exception's text, so it is the constant alone or {@code <ExceptionType>: <constant>}: matched at the end, never
     * anywhere in the text, so a message that merely quotes the constant is still a failure.
     */
    static boolean isUnproxiedFork(RunResult.RunFailed failed) {
        String detail = failed.detail();
        return BAD_COMMAND.equals(failed.cause()) && detail != null
                && (detail.equals(FORK_PROXY_REQUIRED) || detail.endsWith(": " + FORK_PROXY_REQUIRED));
    }

    /** The phase a failure to send the answer to the worker is recorded under, as the worker's own finish failure. */
    static final String FINISH_PHASE = "harness-finish";

    static final String CREDENTIAL_UNAVAILABLE = "The reviewer's forge account is unavailable, so the harness answer"
            + " could not be finished";

    /** The model named when the run's row cannot be read, as {@code ReviewRunCharges} names it. */
    private static final String UNRECORDED_MODEL = "UNRECORDED";

    /** The harness named in a refusal when the run's row cannot be read. */
    private static final String UNRECORDED_HARNESS = "UNRECORDED";

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

    /** Where a reply run's result goes (harness reviews, step 7). */
    @Inject
    HarnessReplyResults replies;

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

    /**
     * The worker could not prepare or finish the review: applied as the API path's failure, retry schedule included,
     * under the round lock and only for its own round. A plain {@code ReviewFailed} skipped both, so a late failure of
     * round 1, or its replayed dead letter, failed round 2 on the same head or scheduled a retry for it (review of
     * PR #370, code-quality/H-1). A round that cannot be read is thrown for a replay, as {@link #acceptGenerated}'s is.
     */
    public void acceptFailed(IntegrationEvent.HarnessReviewFailed failed) {
        rounds.holding(failed.reviewId(), () -> {
            if (!isCurrentRound(failed.reviewId(), failed.round(), "the harness review's " + failed.phase()
                    + " failure")) {
                return false;
            }
            saga.acceptHarnessOutcome(failed.toReviewFailed());
            return true;
        });
    }

    @Override
    public void accept(RunResult result, ReviewRunBinding binding) {
        log(result, binding);
        if (binding.kind() == ReviewRunBinding.Kind.FOLLOWUP) {
            replies.accept(result, binding);
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
            case RunResult.RunFailed failed when isUnproxiedFork(failed) -> saga.acceptHarnessRefusal(binding.reviewId(),
                    binding.commit(), new HarnessReviewStart.Refused(
                            runRows.harnessOf(failed.runId()).orElse(UNRECORDED_HARNESS), FORK_PROXY_REQUIRED));
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
