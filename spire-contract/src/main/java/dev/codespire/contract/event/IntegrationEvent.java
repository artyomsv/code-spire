package dev.codespire.contract.event;

import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;

import java.util.List;
import java.util.Set;

/**
 * Integration events cross a system boundary: ingress from the SCM, worker
 * results, egress confirmations (CONTRACT §4, ADR-010). They are NOT appended
 * to aggregate streams — sagas translate them into Record commands.
 */
public sealed interface IntegrationEvent {

    enum PrAction { OPENED, UPDATED }

    enum CloseReason { MERGED, DECLINED }

    // --- ingress (produced by spire-gateway / ScmIngress) ---

    record PullRequestEventReceived(RepoRef repo, long prId, PrAction action,
                                    String title, String description,
                                    String sourceBranch, String targetBranch,
                                    DiffRefs diffRefs, Author author,
                                    String htmlUrl) implements IntegrationEvent {
    }

    /** Triggers the cancel saga (ADR-013). */
    record PullRequestClosed(RepoRef repo, long prId, CloseReason reason) implements IntegrationEvent {
    }

    /** Parsed from a "/command" PR comment; saga maps "review" -> RequestReview{force=true}. */
    record ManualCommandReceived(RepoRef repo, long prId, String command, String args,
                                 Author author) implements IntegrationEvent {
    }

    record AuthorReplied(RepoRef repo, long prId, String reviewId, ThreadRef threadRef,
                         String commentId, String text, Author author) implements IntegrationEvent {
    }

    /** P3: feeds the RAG indexer. */
    record PushReceived(RepoRef repo, String ref, List<String> commits) implements IntegrationEvent {
    }

    // --- worker results ---

    /** METADATA ONLY — no diff content (ADR-011); content is re-fetched by commit at generate time. */
    record DiffFetched(String reviewId, long prId, String commit, int changedFiles,
                       List<String> languages, long sizeBytes, boolean truncated) implements IntegrationEvent {
    }

    /** Fan-out signal each ContextProvider subscribes to (CONTRACT §8). */
    record ContextRequested(ContextRequest request) implements IntegrationEvent {
    }

    record ContextContributed(String reviewId, ContextContribution contribution) implements IntegrationEvent {
    }

    record ContextAssembled(String reviewId, long prId, String commit, String contextRef,
                            Set<String> contributingSources, Set<String> missingSources) implements IntegrationEvent {
    }

    /** Findings ride INLINE (ADR-011); may quote source — bus at-rest posture per ADR-014. */
    record ReviewGenerated(String reviewId, long prId, String commit,
                           ReviewResult result) implements IntegrationEvent {
    }

    record ReviewFailed(String reviewId, String commit, String phase, String error,
                        boolean retryable, int attempt) implements IntegrationEvent {
    }

    record CommentsPosted(String reviewId, long prId, String commit, String summaryCommentId,
                          List<PostedInline> inline) implements IntegrationEvent {

        public record PostedInline(String commentId, String path, int line) {
        }
    }

    record FollowUpGenerated(String reviewId, ThreadRef threadRef, String answerText) implements IntegrationEvent {
    }

    record FollowUpPosted(String reviewId, ThreadRef threadRef, String commentId) implements IntegrationEvent {
    }
}
