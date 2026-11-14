package dev.codespire.contract.review;

import dev.codespire.contract.port.ScmType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A fork's head is read from one of two forge refs, and nothing else passes for one. */
class PullRefsTest {

    @Test
    void githubRefNamesThePull() {
        assertEquals(Optional.of("refs/pull/42/head"), PullRefs.forType(ScmType.GITHUB, 42));
    }

    @Test
    void gitlabRefNamesTheMergeRequest() {
        assertEquals(Optional.of("refs/merge-requests/7/head"), PullRefs.forType(ScmType.GITLAB, 7));
    }

    @Test
    void bitbucketHasNoRef() {
        assertEquals(Optional.empty(), PullRefs.forType(ScmType.BITBUCKET_CLOUD, 3));
        assertEquals(Optional.empty(), PullRefs.forType(ScmType.BITBUCKET_DC, 3));
    }

    @Test
    void aPullRequestNumberBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PullRefs.forType(ScmType.GITHUB, 0));
        assertThrows(IllegalArgumentException.class, () -> PullRefs.forType(ScmType.GITLAB, -1));
    }

    @Test
    void theGeneratedRefsAreValid() {
        assertTrue(PullRefs.isValid(PullRefs.forType(ScmType.GITHUB, 42).orElseThrow()));
        assertTrue(PullRefs.isValid(PullRefs.forType(ScmType.GITLAB, 7).orElseThrow()));
    }

    @Test
    void aRefOutsideTheTwoShapesIsInvalid() {
        assertFalse(PullRefs.isValid(null));
        assertFalse(PullRefs.isValid("refs/heads/main"));
        assertFalse(PullRefs.isValid("refs/pull/0/head"));
        assertFalse(PullRefs.isValid("refs/pull/1/head;rm"));
        assertFalse(PullRefs.isValid("refs/pull/1/head\n"));
        assertFalse(PullRefs.isValid("refs/pull/01/head"));
        assertFalse(PullRefs.isValid("refs/pull/1/merge"));
    }

    @Test
    void aForkReviewNamesItsOwnPullRequestsRef() {
        PullRefs.requireForkReview("refs/pull/9/head", "spire/review-9", 9);
        PullRefs.requireForkReview("refs/merge-requests/9/head", "spire/review-9", 9);
        assertThrows(IllegalArgumentException.class,
                () -> PullRefs.requireForkReview("refs/pull/10/head", "spire/review-9", 9));
        assertThrows(IllegalArgumentException.class,
                () -> PullRefs.requireForkReview("refs/merge-requests/19/head", "spire/review-9", 9));
    }

    @Test
    void theLocalBranchNamesThePull() {
        assertEquals("spire/review-9", PullRefs.localBranch(9));
    }
}
