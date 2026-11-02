package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewGenerated;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.pipeline.ResultSaga;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static dev.codespire.orchestrator.llm.ReviewRuns.FIRST_RUN;
import static dev.codespire.orchestrator.llm.ReviewRuns.UNKNOWN_RUN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A review run's answer is sent to the review worker to finish, and the worker's finished answer becomes the review's
 * outcome (harness reviews, step 6). A failure fails the review once, and nothing of an ended round is ever finished or
 * posted. The answer's parsing moved to the worker ({@code HarnessReviewWorkerTest}).
 *
 * <p>A plain unit test: the saga, the round counter, the run rows, the credentials and the emitter are fakes overriding
 * every method the path reaches, because an un-overridden one opens a real database or a broker from a unit test
 * (CLAUDE.md, saga test fakes).
 */
class HarnessReviewResultsTest {

    private static final String RUN_ID = "run::github:TEST-WS/TEST-REPO:review-7-aaaaaaaaaaaa-0123abcd:1";
    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, 7L);
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final ReviewRunBinding ROUND_ONE =
            new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot", 1);
    private static final ReviewRunBinding RECONCILE_ONE =
            new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.RECONCILE, 7, HEAD, "TEST-slot", 1);

    private static final String ONE_FINDING = """
            {"summary": "TEST-summary", "findings": [{"path": "src/TEST.java", "line": 12, "endLine": 12,
             "severity": "MAJOR", "category": "CORRECTNESS", "message": "TEST-message", "suggestion": null}]}
            """;

    private static final ReviewRunBinding ROUND_TWO =
            new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot-2", 2);

    private int currentRun = 1;

    /** A forced re-run that held the review's round lock first: it ends the round before the outcome reads it. */
    private boolean rerunWhileWaiting;

    private Optional<String> reviewerCredential = Optional.of("TEST-packed-scm");

    private final List<String> steps = new ArrayList<>();

    private final List<IntegrationEvent> outcomes = new ArrayList<>();

    private final List<ActionCommand> finishes = new ArrayList<>();

    @Test
    void aReviewAnswerIsSentToTheWorkerToFinish() {
        results().accept(answer(ONE_FINDING, ROUND_ONE), ROUND_ONE);

        ActionCommand.FinishHarnessReview finish = onlyFinish();
        assertEquals(REVIEW_ID, finish.reviewId());
        assertEquals(REPO, finish.repo());
        assertEquals(7L, finish.prId());
        assertEquals(HEAD, finish.commit());
        assertEquals(1, finish.round());
        assertEquals(ReviewRunBinding.Kind.REVIEW, finish.kind());
        assertEquals(ONE_FINDING, finish.answerText());
        assertEquals("TEST-model", finish.usage().model(), "the usage names the run row's model");
        assertEquals("TEST-packed-scm", finish.scmCredential());
        assertTrue(outcomes.isEmpty(), "the outcome waits for the worker's finish: " + outcomes);
    }

    /** Step 6 replaces "a reconcile result is only logged": a re-review's answer is finished like a review's. */
    @Test
    void aReconcileAnswerIsSentToTheWorkerToFinish() {
        results().accept(answer(ONE_FINDING, RECONCILE_ONE), RECONCILE_ONE);

        ActionCommand.FinishHarnessReview finish = onlyFinish();
        assertEquals(ReviewRunBinding.Kind.RECONCILE, finish.kind());
        assertEquals(ONE_FINDING, finish.answerText());
        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    /** The tokens were bought, so an answer over the cap is still finished: with no text, as a degraded review. */
    @Test
    void anOverCapAnswerIsSentWithNoText() {
        RunReviewArtifact overCap = new RunReviewArtifact(RUN_ID, ROUND_ONE, null, true, Map.of("INPUT", 10L), 4, List.of());

        results().accept(overCap, ROUND_ONE);

        ActionCommand.FinishHarnessReview finish = onlyFinish();
        assertNull(finish.answerText());
        assertEquals("TEST-model", finish.usage().model(), "the bought tokens' usage is still carried");
    }

    /** A supersede ended round 1; its answer is never finished, whatever it says. */
    @Test
    void aResultOfAnEndedRoundIsDropped() {
        currentRun = 2;

        results().accept(answer(ONE_FINDING, ROUND_ONE), ROUND_ONE);

        assertTrue(finishes.isEmpty(), finishes.toString());
        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    /** A failed read of the round is not a stale round (review of PR #368, code-quality/M-1): thrown for a replay. */
    @Test
    void anUnreadableRoundIsNeitherAppliedNorDropped() {
        currentRun = UNKNOWN_RUN;

        assertThrows(IllegalStateException.class, () -> results().accept(answer(ONE_FINDING, ROUND_TWO), ROUND_TWO));
        assertTrue(finishes.isEmpty(), finishes.toString());
        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    /** The round is read and the finish sent under the review's round lock (review of PR #368, code-quality/H-1). */
    @Test
    void theRoundIsReadAndTheFinishSentUnderTheRoundLock() {
        results().accept(answer(ONE_FINDING, ROUND_ONE), ROUND_ONE);

        assertEquals(List.of("lock " + REVIEW_ID, "round", "finish", "unlock"), steps);
    }

    @Test
    void anAnswerWhoseRoundARerunEndedWhileItWaitedIsDropped() {
        rerunWhileWaiting = true;

        results().accept(answer(ONE_FINDING, ROUND_ONE), ROUND_ONE);

        assertTrue(finishes.isEmpty(), finishes.toString());
        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    /** Retry budget 1: a failed harness run is not retried as a full run; the operator re-runs the review. */
    @Test
    void aFailedRunFailsTheReviewWithoutRetry() {
        results().accept(failed("AGENT_FAILED"), ROUND_ONE);

        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, outcomes.getFirst());
        assertEquals(REVIEW_ID, failure.reviewId());
        assertEquals(HEAD, failure.commit());
        assertEquals("harness-review", failure.phase());
        assertFalse(failure.retryable());
        assertEquals(1, failure.attempt());
        assertTrue(finishes.isEmpty(), "a failure stays on step 5's path: " + finishes);
    }

    /** Call 11: a re-review's run failure stays on step 5's path too. */
    @Test
    void aFailedReconcileRunFailsTheReviewWithoutRetry() {
        results().accept(failed("AGENT_FAILED"), RECONCILE_ONE);

        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, outcomes.getFirst());
        assertEquals("harness-review", failure.phase());
        assertFalse(failure.retryable());
        assertTrue(finishes.isEmpty(), finishes.toString());
    }

    /** credentialRejected marks the REVIEWER's forge account; a refused seat is the harness pool's business. */
    @Test
    void aRejectedSeatNeverMarksTheReviewerAccount() {
        results().accept(failed("CREDENTIAL_REJECTED"), ROUND_ONE);

        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, outcomes.getFirst());
        assertFalse(failure.credentialRejected());
        assertFalse(failure.retryable());
    }

    /** A cancel from the Runs screen ends the current round's review, and says it was a cancel. */
    @Test
    void aCancelledRunOfTheCurrentRoundFailsTheReviewNamingTheCancel() {
        results().accept(failed("CANCELLED"), ROUND_ONE);

        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, outcomes.getFirst());
        assertEquals("Harness review run cancelled", failure.error());
        assertFalse(failure.retryable());
    }

    /** A supersede or a close cancels the old round's run; the review has moved on, so nothing is said to it. */
    @Test
    void aCancelledRunOfAnEndedRoundIsDropped() {
        currentRun = 2;

        results().accept(failed("CANCELLED"), ROUND_ONE);

        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    @Test
    void aStartedRunDoesNothing() {
        results().accept(new RunResult.RunStarted(RUN_ID, "TEST-provider-run"), ROUND_ONE);

        assertTrue(outcomes.isEmpty(), outcomes.toString());
        assertTrue(finishes.isEmpty(), finishes.toString());
    }

    /** Replies take the harness in step 7; a follow-up answer is only logged here. */
    @Test
    void aFollowupResultIsOnlyLogged() {
        ReviewRunBinding followup = new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.FOLLOWUP, 7, HEAD,
                "TEST-thread|TEST-comment", 1);

        results().accept(answer("TEST-reply", followup), followup);
        results().accept(failed("AGENT_FAILED"), followup);

        assertTrue(finishes.isEmpty(), finishes.toString());
        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    /** No reviewer account means no finish can be sent; the paid answer fails the review terminally, never silently. */
    @Test
    void aMissingReviewerCredentialFailsTheReview() {
        reviewerCredential = Optional.empty();

        results().accept(answer(ONE_FINDING, ROUND_ONE), ROUND_ONE);

        assertTrue(finishes.isEmpty(), finishes.toString());
        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, outcomes.getFirst());
        assertEquals("harness-finish", failure.phase());
        assertEquals("The reviewer's forge account is unavailable, so the harness answer could not be finished",
                failure.error());
        assertFalse(failure.retryable());
        assertEquals(1, failure.attempt());
        assertFalse(failure.credentialRejected());
    }

    @Test
    void aFinishedReviewOfTheCurrentRoundBecomesTheOutcome() {
        List<FindingVerdict> verdicts = List.of(new FindingVerdict("TEST-thread", "src/TEST.java", 3,
                FindingVerdict.Status.RESOLVED, "TEST-note"));

        results().acceptGenerated(generated(1, verdicts));

        ReviewGenerated outcome = assertInstanceOf(ReviewGenerated.class, outcomes.getFirst());
        assertEquals(REVIEW_ID, outcome.reviewId());
        assertEquals(HEAD, outcome.commit());
        assertEquals(verdicts, outcome.verdicts());
        assertNull(outcome.reconcileUsage(), "the run was charged once, by ReviewRunCharges");
        assertEquals(List.of("lock " + REVIEW_ID, "round", "outcome", "unlock"), steps);
    }

    @Test
    void aFinishedReviewOfAnEndedRoundIsDropped() {
        currentRun = 2;

        results().acceptGenerated(generated(1, List.of()));

        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    /** Neither applied nor dropped: thrown, so the results consumer dead-letters it for a replay. */
    @Test
    void aFinishedReviewWhoseRoundCannotBeReadIsThrownForAReplay() {
        currentRun = UNKNOWN_RUN;

        HarnessReviewResults results = results();
        assertThrows(IllegalStateException.class, () -> results.acceptGenerated(generated(1, List.of())));
        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    private static RunReviewArtifact answer(String text, ReviewRunBinding binding) {
        return new RunReviewArtifact(RUN_ID, binding, text, false, Map.of("INPUT", 1200L, "OUTPUT", 340L), 4, List.of());
    }

    private static RunResult.RunFailed failed(String cause) {
        return new RunResult.RunFailed(RUN_ID, cause, "TEST-detail", false, null, List.of());
    }

    private static HarnessReviewGenerated generated(int round, List<FindingVerdict> verdicts) {
        return new HarnessReviewGenerated(REVIEW_ID, 7L, HEAD, round,
                new ReviewResult(List.of(), "TEST-summary", ModelUsage.of("TEST-model", 1, 1)), verdicts);
    }

    private ActionCommand.FinishHarnessReview onlyFinish() {
        assertEquals(1, finishes.size(), "one finish is sent: " + finishes);
        return assertInstanceOf(ActionCommand.FinishHarnessReview.class, finishes.getFirst());
    }

    private HarnessReviewResults results() {
        HarnessReviewResults results = new HarnessReviewResults();
        results.saga = saga();
        results.runs = runs();
        results.rounds = roundLock();
        results.runRows = new FactoryRunProjection() {
            @Override public Optional<String> modelOf(String runId) { return Optional.of("TEST-model"); }
            @Override public Optional<String> paidByOf(String runId) { return Optional.of("API_KEY"); }
            @Override protected void push(String runId) { /* nothing: no live feed from a unit test */ }
        };
        results.maxReportedTokens = -1;
        results.credentials = credentials();
        results.commands = commands();
        return results;
    }

    private ResultSaga saga() {
        return new ResultSaga() {
            @Override
            public void acceptHarnessOutcome(IntegrationEvent outcome) {
                steps.add("outcome");
                outcomes.add(outcome);
            }
        };
    }

    /** The real one resolves the reviewer account from the database and packs it with Tink. */
    private WorkerCredentials credentials() {
        return new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return reviewerCredential;
            }
        };
    }

    /** The real one awaits a Kafka ack. */
    private CommandsEmitter commands() {
        return new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                steps.add("finish");
                finishes.add(command);
            }
        };
    }

    /** The real ones read the event log; the ledger's read answers run 1 when the log cannot be read. */
    private ReviewRuns runs() {
        return new ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return currentRun == UNKNOWN_RUN ? FIRST_RUN : currentRun;
            }

            @Override
            public int roundOrUnknown(String reviewId) {
                steps.add("round");
                return currentRun;
            }
        };
    }

    /** The real lock is a Postgres advisory lock (ReviewRoundLockTest); this one records what ran inside it. */
    private ReviewRoundLock roundLock() {
        return new ReviewRoundLock() {
            @Override
            public <T> T holding(String reviewId, Supplier<T> work) {
                steps.add("lock " + reviewId);
                if (rerunWhileWaiting) {
                    currentRun++;
                }
                T done = work.get();
                steps.add("unlock");
                return done;
            }
        };
    }
}
