package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent.HarnessReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewGenerated;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.codespire.orchestrator.llm.ReviewRuns.UNKNOWN_RUN;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.HEAD;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.RECONCILE_ONE;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.REVIEW_ID;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.ROUND_ONE;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.failed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What becomes a harness review's outcome (harness reviews, steps 5 and 6): a failed run fails the review once, on step
 * 5's path (plan call 11), and the worker's finished answer is handed to the saga under the review's round lock, only
 * for its current round, with no reconcile charge. Nothing of an ended round is ever said to the review. Split from
 * {@code HarnessReviewResultsTest}, which sends the answer to the worker.
 *
 * <p>A plain unit test over {@link HarnessResultsFixture}, whose fakes override every method the path reaches.
 */
class HarnessReviewOutcomesTest {

    private final HarnessResultsFixture fixture = new HarnessResultsFixture();

    /** Retry budget 1: a failed harness run is not retried as a full run; the operator re-runs the review. */
    @Test
    void aFailedRunFailsTheReviewWithoutRetry() {
        fixture.results().accept(failed("AGENT_FAILED"), ROUND_ONE);

        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, fixture.outcomes.getFirst());
        assertEquals(REVIEW_ID, failure.reviewId());
        assertEquals(HEAD, failure.commit());
        assertEquals("harness-review", failure.phase());
        assertFalse(failure.retryable());
        assertEquals(1, failure.attempt());
        assertTrue(fixture.finishes.isEmpty(), "a failure stays on step 5's path: " + fixture.finishes);
    }

    /** Call 11: a re-review's run failure stays on step 5's path too. */
    @Test
    void aFailedReconcileRunFailsTheReviewWithoutRetry() {
        fixture.results().accept(failed("AGENT_FAILED"), RECONCILE_ONE);

        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, fixture.outcomes.getFirst());
        assertEquals("harness-review", failure.phase());
        assertFalse(failure.retryable());
        assertTrue(fixture.finishes.isEmpty(), fixture.finishes.toString());
    }

    /** credentialRejected marks the REVIEWER's forge account; a refused seat is the harness pool's business. */
    @Test
    void aRejectedSeatNeverMarksTheReviewerAccount() {
        fixture.results().accept(failed("CREDENTIAL_REJECTED"), ROUND_ONE);

        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, fixture.outcomes.getFirst());
        assertFalse(failure.credentialRejected());
        assertFalse(failure.retryable());
    }

    /** A cancel from the Runs screen ends the current round's review, and says it was a cancel. */
    @Test
    void aCancelledRunOfTheCurrentRoundFailsTheReviewNamingTheCancel() {
        fixture.results().accept(failed("CANCELLED"), ROUND_ONE);

        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, fixture.outcomes.getFirst());
        assertEquals("Harness review run cancelled", failure.error());
        assertFalse(failure.retryable());
    }

    /** A supersede or a close cancels the old round's run; the review has moved on, so nothing is said to it. */
    @Test
    void aCancelledRunOfAnEndedRoundIsDropped() {
        fixture.currentRun = 2;

        fixture.results().accept(failed("CANCELLED"), ROUND_ONE);

        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    @Test
    void aFinishedReviewOfTheCurrentRoundBecomesTheOutcome() {
        List<FindingVerdict> verdicts = List.of(new FindingVerdict("TEST-thread", "src/TEST.java", 3,
                FindingVerdict.Status.RESOLVED, "TEST-note"));

        fixture.results().acceptGenerated(generated(1, verdicts));

        ReviewGenerated outcome = assertInstanceOf(ReviewGenerated.class, fixture.outcomes.getFirst());
        assertEquals(REVIEW_ID, outcome.reviewId());
        assertEquals(HEAD, outcome.commit());
        assertEquals(verdicts, outcome.verdicts());
        assertNull(outcome.reconcileUsage(), "the run was charged once, by ReviewRunCharges");
        assertEquals(List.of("lock " + REVIEW_ID, "round", "outcome", "unlock"), fixture.steps);
    }

    @Test
    void aFinishedReviewOfAnEndedRoundIsDropped() {
        fixture.currentRun = 2;

        fixture.results().acceptGenerated(generated(1, List.of()));

        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    /** Neither applied nor dropped: thrown, so the results consumer dead-letters it for a replay. */
    @Test
    void aFinishedReviewWhoseRoundCannotBeReadIsThrownForAReplay() {
        fixture.currentRun = UNKNOWN_RUN;

        HarnessReviewResults results = fixture.results();
        assertThrows(IllegalStateException.class, () -> results.acceptGenerated(generated(1, List.of())));
        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    /** The worker's failure takes the API path's handling, retry included, only for its own round, under the lock. */
    @Test
    void aWorkerFailureOfTheCurrentRoundIsAppliedUnderTheRoundLock() {
        fixture.results().acceptFailed(workerFailure(1));

        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, fixture.outcomes.getFirst());
        assertEquals(HEAD, failure.commit());
        assertEquals("harness-prepare", failure.phase());
        assertTrue(failure.retryable(), "a transient prepare failure keeps the worker's classification");
        assertEquals(1, failure.attempt());
        assertEquals(List.of("lock " + REVIEW_ID, "round", "outcome", "unlock"), fixture.steps);
    }

    /** A forced re-run on the same head started round 2: round 1's late failure fails nothing (code-quality/H-1). */
    @Test
    void aWorkerFailureOfAnEndedRoundIsDropped() {
        fixture.currentRun = 2;

        fixture.results().acceptFailed(workerFailure(1));

        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    @Test
    void aWorkerFailureWhoseRoundARerunEndedWhileItWaitedIsDropped() {
        fixture.rerunWhileWaiting = true;

        fixture.results().acceptFailed(workerFailure(1));

        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    /** As a finished answer: neither applied nor dropped, so a replay applies it once the round can be read. */
    @Test
    void aWorkerFailureWhoseRoundCannotBeReadIsThrownForAReplay() {
        fixture.currentRun = UNKNOWN_RUN;

        HarnessReviewResults results = fixture.results();
        assertThrows(IllegalStateException.class, () -> results.acceptFailed(workerFailure(1)));
        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    private static HarnessReviewFailed workerFailure(int round) {
        return new HarnessReviewFailed(REVIEW_ID, HEAD, round, "harness-prepare", "TEST-error", true, false);
    }

    private static HarnessReviewGenerated generated(int round, List<FindingVerdict> verdicts) {
        return new HarnessReviewGenerated(REVIEW_ID, 7L, HEAD, round,
                new ReviewResult(List.of(), "TEST-summary", ModelUsage.of("TEST-model", 1, 1)), verdicts);
    }
}
