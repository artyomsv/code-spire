package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.ResultSaga;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A review run's answer becomes the review's outcome (harness reviews, step 5): findings are parsed with the API
 * review's own parser, an unreadable or over-cap answer is a degraded review rather than a lost paid run, a failure fails
 * the review once, and nothing of an ended round is ever posted.
 *
 * <p>A plain unit test: the saga, the round counter and the run rows are fakes overriding every method the path
 * reaches, because an un-overridden one opens a real database from a unit test (CLAUDE.md, saga test fakes).
 */
class HarnessReviewResultsTest {

    private static final String RUN_ID = "run::github:TEST-WS/TEST-REPO:review-7-aaaaaaaaaaaa-0123abcd:1";
    private static final String REVIEW_ID = ReviewIds.reviewId(new RepoRef("TEST-WS", "TEST-REPO"), 7L);
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final ReviewRunBinding ROUND_ONE =
            new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot", 1);

    private static final String ONE_FINDING = """
            {"summary": "TEST-summary", "findings": [{"path": "src/TEST.java", "line": 12, "endLine": 12,
             "severity": "MAJOR", "category": "CORRECTNESS", "message": "TEST-message", "suggestion": null}]}
            """;

    private static final ReviewRunBinding ROUND_TWO =
            new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot-2", 2);

    private int currentRun = 1;

    /** A forced re-run that held the review's round lock first: it ends the round before the outcome reads it. */
    private boolean rerunWhileWaiting;

    private final List<String> steps = new ArrayList<>();

    private final List<IntegrationEvent> outcomes = new ArrayList<>();

    @Test
    void aFindingsAnswerIsPostedThroughTheReviewOutcome() {
        results().accept(answer(ONE_FINDING), ROUND_ONE);

        ReviewGenerated generated = assertInstanceOf(ReviewGenerated.class, outcomes.getFirst());
        assertEquals(REVIEW_ID, generated.reviewId());
        assertEquals(7L, generated.prId());
        assertEquals(HEAD, generated.commit());
        Finding finding = generated.result().findings().getFirst();
        assertEquals("src/TEST.java", finding.path());
        assertEquals(12, finding.range().startLine());
        assertFalse(generated.result().degraded());
    }

    /** An answer that does not parse is degraded, as an unparsable API answer is: never a clean pass. */
    @Test
    void aProseAnswerIsADegradedReview() {
        results().accept(answer("I looked and it is fine."), ROUND_ONE);

        ReviewGenerated generated = assertInstanceOf(ReviewGenerated.class, outcomes.getFirst());
        assertTrue(generated.result().degraded());
        assertTrue(generated.result().findings().isEmpty());
    }

    /** The tokens were bought, so an answer over the cap is a degraded review with a summary, not a silent retry. */
    @Test
    void anOverCapAnswerIsADegradedReview() {
        RunReviewArtifact overCap = new RunReviewArtifact(RUN_ID, ROUND_ONE, null, true, Map.of("INPUT", 10L), 4, List.of());

        results().accept(overCap, ROUND_ONE);

        ReviewGenerated generated = assertInstanceOf(ReviewGenerated.class, outcomes.getFirst());
        assertTrue(generated.result().degraded());
        assertTrue(generated.result().summary().contains("256 KiB"), generated.result().summary());
    }

    /** A supersede ended round 1; its answer is never posted, whatever it says. */
    @Test
    void aResultOfAnEndedRoundIsDropped() {
        currentRun = 2;

        results().accept(answer(ONE_FINDING), ROUND_ONE);

        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    /**
     * A failed read of the round is not a stale round (review of PR #368, code-quality/M-1). The ledger's read would
     * answer run 1 and drop this paid round-2 answer as stale; the outcome is thrown instead, neither applied nor
     * dropped, so the run-results consumer dead-letters it for a replay.
     */
    @Test
    void anUnreadableRoundIsNeitherAppliedNorDropped() {
        currentRun = UNKNOWN_RUN;

        assertThrows(IllegalStateException.class, () -> results().accept(answer(ONE_FINDING), ROUND_TWO));
        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    /**
     * The round is read and the outcome applied under the review's round lock, which a forced re-run holds while it
     * appends its round and replaces the run token (review of PR #368, code-quality/H-1).
     */
    @Test
    void theRoundIsReadAndTheOutcomeAppliedUnderTheRoundLock() {
        results().accept(answer(ONE_FINDING), ROUND_ONE);

        assertEquals(List.of("lock " + REVIEW_ID, "round", "outcome", "unlock"), steps);
    }

    /**
     * A forced re-run on the same head lands while round 1's answer waits for the lock: round 1 was current when the
     * answer arrived, round 2 is current when it may act, so it is dropped rather than posted as round 2.
     */
    @Test
    void anAnswerWhoseRoundARerunEndedWhileItWaitedIsDropped() {
        rerunWhileWaiting = true;

        results().accept(answer(ONE_FINDING), ROUND_ONE);

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
    }

    /**
     * credentialRejected is the forge-401 signal and marks the REVIEWER's SCM account. A refused seat is the harness
     * pool's business (RunCredentialFeedback already marks it), so the review's failure never carries it.
     */
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
    }

    /** Re-reviews take the harness in step 6; a reconcile answer is only logged here. */
    @Test
    void aReconcileResultIsOnlyLogged() {
        ReviewRunBinding reconcile = new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.RECONCILE, 7, HEAD, "TEST-slot", 1);

        results().accept(new RunReviewArtifact(RUN_ID, reconcile, "TEST-verdicts", false, null, 4, List.of()), reconcile);

        assertTrue(outcomes.isEmpty(), outcomes.toString());
    }

    private static RunReviewArtifact answer(String text) {
        return new RunReviewArtifact(RUN_ID, ROUND_ONE, text, false, Map.of("INPUT", 1200L, "OUTPUT", 340L), 4, List.of());
    }

    private static RunResult.RunFailed failed(String cause) {
        return new RunResult.RunFailed(RUN_ID, cause, "TEST-detail", false, null, List.of());
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
