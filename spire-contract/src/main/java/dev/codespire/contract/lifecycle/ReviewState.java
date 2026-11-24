package dev.codespire.contract.lifecycle;

import dev.codespire.contract.scm.RepoRef;

import java.util.Map;
import java.util.Set;

/**
 * ReviewLifecycle aggregate state (CONTRACT §6). Holds only decision-relevant
 * state (idempotency + completion); fine-grained progress lives in read models.
 * {@code raisedFindingComments} is the idempotency key for {@code /finding}: the command
 * arrives at-least-once, and only the aggregate can stop a redelivery filing it twice.
 * {@code round} is the run's identity: the count of {@code ReviewRequested} folded so far (the same count
 * {@code ReviewRuns} reads), so a re-run on the same head is another round. Review waits for CI fences on it.
 */
public record ReviewState(String reviewId,
                          RepoRef repo,
                          long prId,
                          Status status,
                          String currentCommit,
                          Set<String> reviewedCommits,
                          String summaryCommentId,
                          Map<String, ThreadState> threads,
                          Set<String> raisedFindingComments,
                          int round) {

    public enum Status { IDLE, REVIEWING, COMPLETED, FAILED, CANCELLED }

    public record ThreadState(String status, String lastCommentId) {
    }

    /** Callers from before the round was folded; round 0, no run yet. */
    public ReviewState(String reviewId, RepoRef repo, long prId, Status status, String currentCommit,
                       Set<String> reviewedCommits, String summaryCommentId, Map<String, ThreadState> threads,
                       Set<String> raisedFindingComments) {
        this(reviewId, repo, prId, status, currentCommit, reviewedCommits, summaryCommentId, threads,
                raisedFindingComments, 0);
    }

    public static ReviewState initial() {
        return new ReviewState(null, null, 0, Status.IDLE, null, Set.of(), null, Map.of(), Set.of());
    }

    /** The same state one run later: a {@code ReviewRequested} was folded. */
    public ReviewState nextRound() {
        return new ReviewState(reviewId, repo, prId, status, currentCommit, reviewedCommits, summaryCommentId, threads,
                raisedFindingComments, round + 1);
    }

    public boolean isReviewing() {
        return status == Status.REVIEWING;
    }
}
