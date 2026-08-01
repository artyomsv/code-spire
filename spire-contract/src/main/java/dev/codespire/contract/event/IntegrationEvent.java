package dev.codespire.contract.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;

import java.util.List;
import java.util.Set;

/**
 * Integration events cross a system boundary: ingress from the SCM, worker
 * results, egress confirmations (CONTRACT §4, ADR-010). They are NOT appended
 * to aggregate streams — sagas translate them into Record commands.
 *
 * <p>The {@code type} discriminator is the Kafka wire format (cs.integration /
 * cs.results, CONTRACT §9); renaming a subtype is a breaking wire change and
 * follows the eventVersion/upcaster rule (CONTRACT §11).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        // explicit names: nested records would otherwise get "Outer$Inner" ids
        @JsonSubTypes.Type(value = IntegrationEvent.PullRequestEventReceived.class, name = "PullRequestEventReceived"),
        @JsonSubTypes.Type(value = IntegrationEvent.PullRequestClosed.class, name = "PullRequestClosed"),
        @JsonSubTypes.Type(value = IntegrationEvent.ManualCommandReceived.class, name = "ManualCommandReceived"),
        @JsonSubTypes.Type(value = IntegrationEvent.AuthorReplied.class, name = "AuthorReplied"),
        @JsonSubTypes.Type(value = IntegrationEvent.PushReceived.class, name = "PushReceived"),
        @JsonSubTypes.Type(value = IntegrationEvent.DiffFetched.class, name = "DiffFetched"),
        @JsonSubTypes.Type(value = IntegrationEvent.ContextRequested.class, name = "ContextRequested"),
        @JsonSubTypes.Type(value = IntegrationEvent.ContextContributed.class, name = "ContextContributed"),
        @JsonSubTypes.Type(value = IntegrationEvent.ContextAssembled.class, name = "ContextAssembled"),
        @JsonSubTypes.Type(value = IntegrationEvent.ReviewGenerated.class, name = "ReviewGenerated"),
        @JsonSubTypes.Type(value = IntegrationEvent.ReviewFailed.class, name = "ReviewFailed"),
        @JsonSubTypes.Type(value = IntegrationEvent.CommentsPosted.class, name = "CommentsPosted"),
        @JsonSubTypes.Type(value = IntegrationEvent.FollowUpGenerated.class, name = "FollowUpGenerated"),
        @JsonSubTypes.Type(value = IntegrationEvent.FollowUpPosted.class, name = "FollowUpPosted"),
        @JsonSubTypes.Type(value = IntegrationEvent.TurnCapNotified.class, name = "TurnCapNotified")
})
public sealed interface IntegrationEvent {

    enum PrAction { OPENED, UPDATED }

    enum CloseReason { MERGED, DECLINED }

    // --- ingress (produced by spire-gateway / ScmIngress) ---

    /**
     * {@code providerType} is the {@code scm_provider.type} that produced this event
     * (from the webhook adapter or the manual-register URL), so the saga resolves the
     * exact provider by (type, workspace) — a GitHub org and a Bitbucket workspace can
     * share a name. Nullable for backward compatibility with events serialized before
     * this field existed; the saga then falls back to workspace-only resolution.
     */
    record PullRequestEventReceived(RepoRef repo, long prId, PrAction action,
                                    String title, String description,
                                    String sourceBranch, String targetBranch,
                                    String headCommit, Author author,
                                    String htmlUrl, String providerType) implements IntegrationEvent {
    }

    /** Triggers the cancel saga (ADR-013). */
    record PullRequestClosed(RepoRef repo, long prId, CloseReason reason) implements IntegrationEvent {
    }

    /** Parsed from a "/command" PR comment; saga maps "review" -> RequestReview{force=true}. */
    record ManualCommandReceived(RepoRef repo, long prId, String command, String args,
                                 Author author) implements IntegrationEvent {
    }

    /**
     * A human's reply on a review conversation.
     *
     * <p>{@code topLevel} marks a plain PR (issue) comment — answered in the review's summary comment
     * as its conversation "thread", since no SCM threading exists for it. {@code false} (the shorter
     * ctors' default) is an inline review-comment reply, threaded on its own.
     *
     * <p>{@code mentions} is who the comment @-mentioned, already extracted by the ingress that
     * parsed it. It is a list of identities, not text: an SCM renders a mention in its own syntax
     * (a login, or an account id in braces), and only the adapter that knows the payload can read
     * it. Callers ask "is the bot in here?" and never learn how any provider writes it.
     */
    /**
     * {@code location} is where in the diff the thread sits, when the provider says — each ingress
     * reads it from its own payload (GitHub's {@code path}/{@code line}, GitLab's {@code position},
     * Bitbucket's {@code inline}). Null for a summary or top-level comment, and for any provider that
     * does not report one.
     *
     * <p>Without it the core cannot tell an inline thread from a general one: such threads had no
     * {@code (path, line)} in the read model, so the UI filed a comment demonstrably attached to a
     * line under "General discussion", and a reply on a line the bot had flagged could not be
     * recognised as being about that finding.
     */
    record AuthorReplied(RepoRef repo, long prId, String reviewId, ThreadRef threadRef,
                         String commentId, String text, Author author,
                         boolean topLevel, List<String> mentions,
                         ThreadLocation location) implements IntegrationEvent {

        public AuthorReplied {
            mentions = mentions == null ? List.of() : List.copyOf(mentions);
        }

        public AuthorReplied(RepoRef repo, long prId, String reviewId, ThreadRef threadRef,
                             String commentId, String text, Author author,
                             boolean topLevel, List<String> mentions) {
            this(repo, prId, reviewId, threadRef, commentId, text, author, topLevel, mentions, null);
        }

        public AuthorReplied(RepoRef repo, long prId, String reviewId, ThreadRef threadRef,
                             String commentId, String text, Author author, boolean topLevel) {
            this(repo, prId, reviewId, threadRef, commentId, text, author, topLevel, List.of(), null);
        }

        public AuthorReplied(RepoRef repo, long prId, String reviewId, ThreadRef threadRef,
                             String commentId, String text, Author author) {
            this(repo, prId, reviewId, threadRef, commentId, text, author, false, List.of(), null);
        }
    }

    /** P3: feeds the RAG indexer. */
    record PushReceived(RepoRef repo, String ref, List<String> commits) implements IntegrationEvent {

        public PushReceived {
            commits = commits == null ? null : List.copyOf(commits);
        }
    }

    // --- worker results ---

    /**
     * METADATA ONLY — no diff content (ADR-011); content is re-fetched by commit at generate time.
     *
     * <p>{@code references} are the candidate context references found in the PR's own title, branch
     * and description when the diff was fetched, threaded into {@code GatherContext} so the context
     * providers can resolve what they recognise. One neutral set: each registered
     * {@link dev.codespire.contract.port.ContextReferenceSource} contributes the shapes it knows, and
     * every entry is a recall-favouring candidate that a provider narrows later.
     */
    /**
     * {@code repoRules} is the repository's own {@code .codespire} review rules, read at diff-fetch
     * from the PR's TARGET branch (see {@code DiffSource.fetchTextFileOnBranch}) and carried here so
     * the context aggregator needs no SCM credential of its own. Null when the repo has no rules file,
     * which is the normal case. Nullable by design: events serialized before this field existed
     * deserialize with it absent.
     */
    record DiffFetched(String reviewId, long prId, String commit, int changedFiles,
                       List<String> languages, long sizeBytes, boolean truncated,
                       Set<String> references, String repoRules) implements IntegrationEvent {

        public DiffFetched {
            languages = languages == null ? null : List.copyOf(languages);
            references = references == null ? null : Set.copyOf(references);
        }
    }

    /** Fan-out signal each ContextProvider subscribes to (CONTRACT §8). */
    record ContextRequested(ContextRequest request) implements IntegrationEvent {
    }

    record ContextContributed(String reviewId, ContextContribution contribution) implements IntegrationEvent {
    }

    record ContextAssembled(String reviewId, long prId, String commit, String contextRef,
                            Set<String> contributingSources, Set<String> missingSources) implements IntegrationEvent {

        public ContextAssembled {
            contributingSources = contributingSources == null ? null : Set.copyOf(contributingSources);
            missingSources = missingSources == null ? null : Set.copyOf(missingSources);
        }
    }

    /**
     * Findings ride INLINE (ADR-011); may quote source — bus at-rest posture per ADR-014.
     * verdicts + reconcileUsage are set only by the follow-up reconcile flow (ADR-019);
     * both empty/null on a first review.
     */
    record ReviewGenerated(String reviewId, long prId, String commit, ReviewResult result,
                           List<FindingVerdict> verdicts, ModelUsage reconcileUsage) implements IntegrationEvent {

        public ReviewGenerated {
            verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        }

        public ReviewGenerated(String reviewId, long prId, String commit, ReviewResult result) {
            this(reviewId, prId, commit, result, List.of(), null);
        }
    }

    /**
     * @param credentialRejected the provider refused the credential (not a transient fault), so
     *                           the orchestrator can flag it for the operator instead of leaving
     *                           a dead token to be inferred from repeated failures
     */
    record ReviewFailed(String reviewId, String commit, String phase, String error,
                        boolean retryable, int attempt, boolean credentialRejected)
            implements IntegrationEvent {

        /** Call sites and in-flight records from before the credential signal existed. */
        public ReviewFailed(String reviewId, String commit, String phase, String error,
                            boolean retryable, int attempt) {
            this(reviewId, commit, phase, error, retryable, attempt, false);
        }
    }

    record CommentsPosted(String reviewId, long prId, String commit, String summaryThreadRef,
                          List<PostedInline> inline, List<ThreadOutcome> threadOutcomes) implements IntegrationEvent {

        public CommentsPosted {
            inline = inline == null ? null : List.copyOf(inline);
            threadOutcomes = threadOutcomes == null ? List.of() : List.copyOf(threadOutcomes);
        }

        /**
         * A posted inline finding, identified by the THREAD a human's reply will arrive under — which is
         * the only thing the core does with it (ownership, so the bot recognizes its own conversation).
         *
         * <p>What that id is belongs to the adapter: GitHub and Bitbucket make the comment its own thread
         * root, while GitLab posts a discussion whose id differs from the note's. The core deliberately
         * does not carry the comment id as well; modelling both here would put "some providers have two
         * ids" into shared code, and keying off the wrong one is what made a GitLab reply unrecognizable.
         */
        public record PostedInline(String threadRef, String path, int line) {
        }

        /** replyCommentId null when the reply was skipped (a human had already resolved the thread). */
        public record ThreadOutcome(String threadRef, FindingVerdict.Status status,
                                    String replyCommentId, boolean resolved) {
        }

        public CommentsPosted(String reviewId, long prId, String commit, String summaryThreadRef,
                              List<PostedInline> inline) {
            this(reviewId, prId, commit, summaryThreadRef, inline, List.of());
        }
    }

    /** {@code usage} is the follow-up LLM call's token/cost accounting (cost breakdown, roadmap 11);
     * null when deserializing a legacy event recorded before this field existed. */
    record FollowUpGenerated(String reviewId, ThreadRef threadRef, String answerText,
                             ModelUsage usage) implements IntegrationEvent {
    }

    record FollowUpPosted(String reviewId, ThreadRef threadRef, String commentId) implements IntegrationEvent {
    }

    /**
     * The turn-cap hand-off notice was posted in {@code threadRef} (the conversation root) as
     * {@code commentId}.
     *
     * <p>Deliberately NOT a {@link FollowUpPosted}: that event bumps the thread's turn count, so
     * reusing it would let the notice about running out of turns consume one.
     */
    record TurnCapNotified(String reviewId, ThreadRef threadRef, String commentId) implements IntegrationEvent {
    }
}
