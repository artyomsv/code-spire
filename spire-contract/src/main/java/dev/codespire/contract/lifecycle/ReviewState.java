package dev.codespire.contract.lifecycle;

import dev.codespire.contract.scm.RepoRef;

import java.util.Map;
import java.util.Set;

/**
 * ReviewLifecycle aggregate state (CONTRACT §6). Holds only decision-relevant
 * state (idempotency + completion); fine-grained progress lives in read models.
 */
public record ReviewState(String reviewId,
                          RepoRef repo,
                          long prId,
                          Status status,
                          String currentCommit,
                          Set<String> reviewedCommits,
                          String summaryCommentId,
                          Map<String, ThreadState> threads) {

    public enum Status { IDLE, REVIEWING, COMPLETED, FAILED, CANCELLED }

    public record ThreadState(String status, String lastCommentId) {
    }

    public static ReviewState initial() {
        return new ReviewState(null, null, 0, Status.IDLE, null, Set.of(), null, Map.of());
    }

    public boolean isReviewing() {
        return status == Status.REVIEWING;
    }
}
