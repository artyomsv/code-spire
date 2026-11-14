package dev.codespire.orchestrator.review.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which fork pull requests are reviewed on a harness (step 8): a GitHub or GitLab fork is, Bitbucket's is refused. */
class HarnessReviewStartForkTest extends HarnessReviewStartFixture {

    /** Step 8: a GitHub fork is reviewed, from the pull ref of the base repository. */
    @Test
    void aGitHubForkIsPrepared() {
        fromFork = true;

        assertEquals(new HarnessReviewStart.Preparing("TEST-harness"), start().start(EVENT));
        onlyPrepare();
    }

    @Test
    void aGitLabForkIsPrepared() {
        fromFork = true;
        providerType = "gitlab";

        assertEquals(new HarnessReviewStart.Preparing("TEST-harness"), start().start(EVENT));
        onlyPrepare();
    }

    /** Unknown is untrusted, not refused: a pull ref exists, so it is reviewed as a fork. */
    @Test
    void anUnknownForkStatusOnGitHubIsPrepared() {
        fromFork = null;

        assertEquals(new HarnessReviewStart.Preparing("TEST-harness"), start().start(EVENT));
        onlyPrepare();
    }

    @Test
    void aBitbucketForkIsRefusedNamingTheMissingRef() {
        fromFork = true;
        providerType = "bitbucket-cloud";

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "fork_pull_request_has_no_pull_ref"),
                start().start(EVENT));
        assertNothingSent();
    }

    @Test
    void aBitbucketDataCenterForkIsRefused() {
        fromFork = true;
        providerType = "bitbucket-dc";

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "fork_pull_request_has_no_pull_ref"),
                start().start(EVENT));
        assertNothingSent();
    }

    @Test
    void aBitbucketUnknownForkStatusIsRefused() {
        fromFork = null;
        providerType = "bitbucket-cloud";

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "fork_pull_request_has_no_pull_ref"),
                start().start(EVENT));
        assertNothingSent();
    }

    /** A same-repository Bitbucket pull request needs no pull ref: only an untrusted one does. */
    @Test
    void aSameRepositoryBitbucketPullRequestIsPrepared() {
        providerType = "bitbucket-cloud";

        assertEquals(new HarnessReviewStart.Preparing("TEST-harness"), start().start(EVENT));
        onlyPrepare();
    }
}
