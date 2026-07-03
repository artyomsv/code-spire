package dev.codespire.contract.command;

import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;

import java.util.List;
import java.util.Set;

/**
 * Action commands go to workers/adapters — they cause side effects and produce
 * integration result events (CONTRACT §5). Workers are idempotent by
 * causationId and pre-check run staleness before expensive/visible actions
 * (ADR-013).
 */
public sealed interface ActionCommand {

    String reviewId();

    record FetchDiff(String reviewId, RepoRef repo, long prId, String commit) implements ActionCommand {
    }

    record GatherContext(String reviewId, RepoRef repo, long prId, String commit,
                         Set<String> ticketKeys, List<String> links) implements ActionCommand {
    }

    /** providerOverride is set by the fallback saga on retry; worker re-fetches the diff by commit. */
    record GenerateReview(String reviewId, RepoRef repo, long prId, String commit, String contextRef,
                          int attempt, String providerOverride) implements ActionCommand {
    }

    /** Findings inline — same ReviewResult as ReviewGenerated (ADR-011). */
    record PostComments(String reviewId, RepoRef repo, long prId, String commit,
                        ReviewResult findings) implements ActionCommand {
    }

    /** Worker fetches thread history from the SCM on demand — no blob (CONTRACT §5). */
    record AnswerFollowUp(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                          String question) implements ActionCommand {
    }
}
