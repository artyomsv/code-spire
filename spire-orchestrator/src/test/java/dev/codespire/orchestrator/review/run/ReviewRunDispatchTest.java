package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.port.ScmType;
import dev.codespire.orchestrator.factory.RunLaunch;
import dev.codespire.orchestrator.provider.ProviderRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a funded review call becomes one queued row and one command on the review lane, and a refusal costs nothing.
 * A plain unit test: every collaborator is faked, and a fake method this path must not reach throws.
 */
class ReviewRunDispatchTest extends ReviewRunDispatchFixture {

    @Test
    void aFundedReviewRunIsQueuedAndSentOnTheReviewLane() {
        ReviewRunDispatch.Outcome outcome = dispatch().dispatch(REQUEST);

        String runId = ReviewRunIds.of(REPO, ScmType.GITHUB, CALL);
        assertEquals(new ReviewRunDispatch.Dispatched(runId), outcome);
        assertEquals(1, rows.size());
        assertEquals("REVIEW", rows.getFirst().kind());
        assertEquals(CALL.reviewId(), rows.getFirst().reviewId());
        assertEquals(HEAD, rows.getFirst().baseCommit(), "the row's base commit is the head the review reads");
        assertTrue(buildLane.isEmpty(), "a review never rides the build lane");
        RunCommand.ExecuteReviewRun command = assertInstanceOf(RunCommand.ExecuteReviewRun.class, reviewLane.getFirst());
        assertEquals(CALL, command.review());
        assertEquals(HEAD, command.execution().baseCommit());
        assertEquals("TEST-source", command.execution().branch());
        assertEquals(600, command.execution().maxWallClockSeconds());
        assertEquals("TEST-packed-scm:" + runId + ":TEST-reviewer", command.execution().scmCredential(),
                "the clone credential is the reviewer account's");
        assertEquals(List.of(ProviderRole.REVIEWER), accountsAsked, "the factory account is never used");
    }

    @Test
    void aSpentPoolIsRefusedWithItsReason() {
        fundingRefusal = "subscription_unavailable";

        ReviewRunDispatch.Outcome outcome = dispatch().dispatch(REQUEST);

        assertEquals(new ReviewRunDispatch.Refused("subscription_unavailable"), outcome);
        assertTrue(rows.isEmpty(), "a refusal writes no row");
        assertTrue(reviewLane.isEmpty() && buildLane.isEmpty(), "and sends nothing");
    }

    /** The id is deterministic, so a redelivered dispatch matches the row the first one wrote and sends nothing. */
    @Test
    void aCallAlreadyQueuedIsNotSentAgain() {
        rowAccepted = false;

        ReviewRunDispatch.Outcome outcome = dispatch().dispatch(REQUEST);

        assertEquals(new ReviewRunDispatch.AlreadyQueued(ReviewRunIds.of(REPO, ScmType.GITHUB, CALL)), outcome);
        assertTrue(reviewLane.isEmpty());
    }

    @Test
    void anUnacknowledgedLaunchIsUncertain() {
        launchOutcome = new RunLaunch.Uncertain(new IllegalStateException("TEST-no-ack"));

        ReviewRunDispatch.Outcome outcome = dispatch().dispatch(REQUEST);

        assertEquals(new ReviewRunDispatch.Uncertain(ReviewRunIds.of(REPO, ScmType.GITHUB, CALL)), outcome);
    }

    /**
     * Step 5: the review's execution protects the pull request's target branch, which the run worker's init then
     * keeps under refs/spire-base for the agent to diff against.
     */
    @Test
    void theDispatchProtectsTheReviewsTargetBranch() {
        dispatch().dispatch(REQUEST);

        RunCommand.ExecuteReviewRun command = assertInstanceOf(RunCommand.ExecuteReviewRun.class, reviewLane.getFirst());
        assertEquals("TEST-main", command.execution().protectedBranch());
        assertFalse(command.execution().pushesToAnExistingBranch(), "a review still pushes nothing");
    }

    /** With no target branch the agent has nothing to diff against, so nothing is funded or sent. */
    @Test
    void aReviewWithNoTargetBranchIsRefused() {
        destBranch = "";

        ReviewRunDispatch.Outcome outcome = dispatch().dispatch(REQUEST);

        assertEquals(new ReviewRunDispatch.Refused("review_run_target_unavailable"), outcome);
        assertTrue(rows.isEmpty(), "a refusal writes no row");
        assertTrue(reviewLane.isEmpty(), "and sends nothing");
    }
}
