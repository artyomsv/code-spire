package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;

/** Reads the review a launch wrapper carries, trusted or not. */
final class ReviewWrappers {

    private ReviewWrappers() {
    }

    /** The review a wrapper carries, or null when it is not a review run. */
    static RunCommand.ExecuteReviewRun reviewOf(RunCommand wrapper) {
        if (wrapper instanceof RunCommand.ExecuteReviewRun review) return review;
        if (wrapper instanceof RunCommand.ExecuteUntrustedReviewRun untrusted) return untrusted.asReview();
        return null;
    }
}
