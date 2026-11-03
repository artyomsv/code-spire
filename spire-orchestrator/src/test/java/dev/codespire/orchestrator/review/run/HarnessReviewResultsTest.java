package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.codespire.orchestrator.llm.ReviewRuns.UNKNOWN_RUN;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.HEAD;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.ONE_FINDING;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.RECONCILE_ONE;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.REPO;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.REVIEW_ID;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.ROUND_ONE;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.ROUND_TWO;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.RUN_ID;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.answer;
import static dev.codespire.orchestrator.review.run.HarnessResultsFixture.failed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A review run's answer is sent to the review worker to finish (harness reviews, step 6), under the review's round
 * lock and only for its current round. The answer's parsing moved to the worker
 * ({@code HarnessReviewWorkerFinishTest}); a failed run and the worker's finished answer are
 * {@code HarnessReviewOutcomesTest}'s.
 *
 * <p>A plain unit test over {@link HarnessResultsFixture}, whose fakes override every method the path reaches.
 */
class HarnessReviewResultsTest {

    private final HarnessResultsFixture fixture = new HarnessResultsFixture();

    @Test
    void aReviewAnswerIsSentToTheWorkerToFinish() {
        fixture.results().accept(answer(ONE_FINDING, ROUND_ONE), ROUND_ONE);

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
        assertTrue(fixture.outcomes.isEmpty(), "the outcome waits for the worker's finish: " + fixture.outcomes);
    }

    /** Step 6 replaces "a reconcile result is only logged": a re-review's answer is finished like a review's. */
    @Test
    void aReconcileAnswerIsSentToTheWorkerToFinish() {
        fixture.results().accept(answer(ONE_FINDING, RECONCILE_ONE), RECONCILE_ONE);

        ActionCommand.FinishHarnessReview finish = onlyFinish();
        assertEquals(ReviewRunBinding.Kind.RECONCILE, finish.kind());
        assertEquals(ONE_FINDING, finish.answerText());
        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    /** The tokens were bought, so an answer over the cap is still finished: with no text, as a degraded review. */
    @Test
    void anOverCapAnswerIsSentWithNoText() {
        RunReviewArtifact overCap = new RunReviewArtifact(RUN_ID, ROUND_ONE, null, true, Map.of("INPUT", 10L), 4, List.of());

        fixture.results().accept(overCap, ROUND_ONE);

        ActionCommand.FinishHarnessReview finish = onlyFinish();
        assertNull(finish.answerText());
        assertEquals("TEST-model", finish.usage().model(), "the bought tokens' usage is still carried");
    }

    /** A supersede ended round 1; its answer is never finished, whatever it says. */
    @Test
    void aResultOfAnEndedRoundIsDropped() {
        fixture.currentRun = 2;

        fixture.results().accept(answer(ONE_FINDING, ROUND_ONE), ROUND_ONE);

        assertTrue(fixture.finishes.isEmpty(), fixture.finishes.toString());
        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    /** A failed read of the round is not a stale round (review of PR #368, code-quality/M-1): thrown for a replay. */
    @Test
    void anUnreadableRoundIsNeitherAppliedNorDropped() {
        fixture.currentRun = UNKNOWN_RUN;

        HarnessReviewResults results = fixture.results();
        assertThrows(IllegalStateException.class, () -> results.accept(answer(ONE_FINDING, ROUND_TWO), ROUND_TWO));
        assertTrue(fixture.finishes.isEmpty(), fixture.finishes.toString());
        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    /** The round is read and the finish sent under the review's round lock (review of PR #368, code-quality/H-1). */
    @Test
    void theRoundIsReadAndTheFinishSentUnderTheRoundLock() {
        fixture.results().accept(answer(ONE_FINDING, ROUND_ONE), ROUND_ONE);

        assertEquals(List.of("lock " + REVIEW_ID, "round", "finish", "unlock"), fixture.steps);
    }

    @Test
    void anAnswerWhoseRoundARerunEndedWhileItWaitedIsDropped() {
        fixture.rerunWhileWaiting = true;

        fixture.results().accept(answer(ONE_FINDING, ROUND_ONE), ROUND_ONE);

        assertTrue(fixture.finishes.isEmpty(), fixture.finishes.toString());
        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    @Test
    void aStartedRunDoesNothing() {
        fixture.results().accept(new RunResult.RunStarted(RUN_ID, "TEST-provider-run"), ROUND_ONE);

        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
        assertTrue(fixture.finishes.isEmpty(), fixture.finishes.toString());
    }

    /** Replies take the harness in step 7; a follow-up answer is only logged here. */
    @Test
    void aFollowupResultIsOnlyLogged() {
        ReviewRunBinding followup = new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.FOLLOWUP, 7, HEAD,
                "TEST-thread|TEST-comment", 1);

        HarnessReviewResults results = fixture.results();
        results.accept(answer("TEST-reply", followup), followup);
        results.accept(failed("AGENT_FAILED"), followup);

        assertTrue(fixture.finishes.isEmpty(), fixture.finishes.toString());
        assertTrue(fixture.outcomes.isEmpty(), fixture.outcomes.toString());
    }

    /** No reviewer account means no finish can be sent; the paid answer fails the review terminally, never silently. */
    @Test
    void aMissingReviewerCredentialFailsTheReview() {
        fixture.reviewerCredential = Optional.empty();

        fixture.results().accept(answer(ONE_FINDING, ROUND_ONE), ROUND_ONE);

        assertTrue(fixture.finishes.isEmpty(), fixture.finishes.toString());
        ReviewFailed failure = assertInstanceOf(ReviewFailed.class, fixture.outcomes.getFirst());
        assertEquals("harness-finish", failure.phase());
        assertEquals("The reviewer's forge account is unavailable, so the harness answer could not be finished",
                failure.error());
        assertFalse(failure.retryable());
        assertEquals(1, failure.attempt());
        assertFalse(failure.credentialRejected());
    }

    private ActionCommand.FinishHarnessReview onlyFinish() {
        assertEquals(1, fixture.finishes.size(), "one finish is sent: " + fixture.finishes);
        return assertInstanceOf(ActionCommand.FinishHarnessReview.class, fixture.finishes.getFirst());
    }
}
