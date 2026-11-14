package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.RunCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An untrusted (fork) pull request's review run: its own command, the forge's pull ref and a fixed local branch. */
class ReviewRunDispatchForkTest extends ReviewRunDispatchFixture {

    /**
     * Step 8: an untrusted pull request is sent on its own command, with the forge's pull ref and a fixed local branch.
     */
    @Test
    void aForkIsSentUntrustedWithItsRefAndFixedBranch() {
        fromFork = true;

        dispatch().dispatch(REQUEST);

        RunCommand.ExecuteUntrustedReviewRun command =
                assertInstanceOf(RunCommand.ExecuteUntrustedReviewRun.class, reviewLane.getFirst());
        assertEquals("refs/pull/7/head", command.pullRef());
        assertEquals("spire/review-7", command.execution().branch());
        assertEquals("spire/review-7", command.execution().baseBranch());
        assertEquals(HEAD, command.execution().baseCommit());
        assertEquals("TEST-main", command.execution().protectedBranch());
        assertEquals(CALL, command.review());
        assertEquals("spire/review-7", rows.getFirst().branch(), "the row names the local branch too");
    }

    @Test
    void aSameRepositoryPullRequestIsSentAsBefore() {
        fromFork = false;

        dispatch().dispatch(REQUEST);

        RunCommand.ExecuteReviewRun command = assertInstanceOf(RunCommand.ExecuteReviewRun.class, reviewLane.getFirst());
        assertEquals("TEST-source", command.execution().branch());
        assertEquals("TEST-source", command.execution().baseBranch());
    }

    /** Unknown is untrusted. */
    @Test
    void anUnknownForkStatusIsSentUntrusted() {
        fromFork = null;

        dispatch().dispatch(REQUEST);

        assertInstanceOf(RunCommand.ExecuteUntrustedReviewRun.class, reviewLane.getFirst());
    }

    @Test
    void aGitLabForkNamesTheMergeRequestRef() {
        fromFork = true;
        providerType = "gitlab";

        dispatch().dispatch(REQUEST);

        RunCommand.ExecuteUntrustedReviewRun command =
                assertInstanceOf(RunCommand.ExecuteUntrustedReviewRun.class, reviewLane.getFirst());
        assertEquals("refs/merge-requests/7/head", command.pullRef());
    }

    /** The fork author chooses the branch name: it never reaches the unit, whatever it is called. */
    @Test
    void aForkBranchNamedMainNeverReachesTheCommand() {
        fromFork = true;
        sourceBranch = "main";

        dispatch().dispatch(REQUEST);

        RunCommand.ExecuteUntrustedReviewRun command =
                assertInstanceOf(RunCommand.ExecuteUntrustedReviewRun.class, reviewLane.getFirst());
        assertEquals("spire/review-7", command.execution().branch());
        assertEquals("spire/review-7", command.execution().baseBranch());
        assertEquals("spire/review-7", rows.getFirst().branch());
        assertEquals("spire/review-7", rows.getFirst().baseBranch());
    }

    /** Bitbucket has no pull ref: nothing is funded, written or sent. */
    @Test
    void anUntrustedBitbucketPullRequestIsRefusedBeforeFunding() {
        fromFork = true;
        providerType = "bitbucket-cloud";

        ReviewRunDispatch.Outcome outcome = dispatch().dispatch(REQUEST);

        assertEquals(new ReviewRunDispatch.Refused("review_run_target_unavailable"), outcome);
        assertTrue(rows.isEmpty() && reviewLane.isEmpty());
    }
}
