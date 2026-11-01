package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;

/**
 * The run id of a harness review run (harness reviews, step 4): deterministic in the review's pull request, the head it
 * reads, the kind of call and the call slot, so a redelivered dispatch derives the same id and the worker's
 * {@code run_claim} drops the second command.
 */
public final class ReviewRunIds {

    private ReviewRunIds() {
    }

    /** {@code review-<prId>-<commit12>-<slotDigest>}, attempt 1, where the digest is 8 hex of SHA-256 over kind|slot. */
    public static String of(RepoRef repo, ScmType type, ReviewRunBinding binding) {
        throw new UnsupportedOperationException("review run ids are not derived yet: " + binding.reviewId());
    }
}
