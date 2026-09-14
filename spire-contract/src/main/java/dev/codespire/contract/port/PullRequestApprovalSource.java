package dev.codespire.contract.port;

import dev.codespire.contract.scm.RepoRef;

/** Current native review evidence. An unsupported forge never grants approval. */
public interface PullRequestApprovalSource {
    record Approval(String actorId, String head, boolean approved, boolean human) {}
    default boolean available() { return false; }
    default Approval read(RepoRef repository, long pullRequest, String reviewId) {
        throw new UnsupportedOperationException("Current pull-request approval evidence is unavailable");
    }
}
