package dev.codespire.contract.lifecycle;

import dev.codespire.contract.scm.RepoRef;

import java.util.Map;
import java.util.Set;

/**
 * ReviewLifecycle aggregate state (CONTRACT §6). Holds only decision-relevant
 * state (idempotency + completion); fine-grained progress lives in read models.
 * {@code raisedFindingComments} is the idempotency key for {@code /finding}: the command
 * arrives at-least-once, and only the aggregate can stop a redelivery filing it twice.
 */
public record ReviewState(String reviewId,
                          RepoRef repo,
                          long prId,
                          Status status,
                          String currentCommit,
                          Set<String> reviewedCommits,
                          String summaryCommentId,
                          Map<String, ThreadState> threads,
                          Set<String> raisedFindingComments) {

    public enum Status { IDLE, REVIEWING, COMPLETED, FAILED, CANCELLED }

    public record ThreadState(String status, String lastCommentId) {
    }

    public static ReviewState initial() {
        return new ReviewState(null, null, 0, Status.IDLE, null, Set.of(), null, Map.of(), Set.of());
    }

    public boolean isReviewing() {
        return status == Status.REVIEWING;
    }
}
