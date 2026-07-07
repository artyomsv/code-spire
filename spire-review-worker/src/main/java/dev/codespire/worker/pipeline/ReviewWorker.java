package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.worker.adapters.WorkerLlmProvider;
import dev.codespire.worker.adapters.WorkerScmClients;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.diff.Anchors;
import dev.codespire.llm.FindingsParser;
import dev.codespire.llm.ReviewPromptBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * The review worker: GenerateReview (re-fetch diff by commit -> prompt -> LLM
 * -> parse findings) and PostComments (idempotent, anchor-validated).
 *
 * Posting rules (ADR-013 / SECURITY.md):
 * - the PR head is re-checked before the LLM call and before posting — if it
 *   moved (webhook not yet processed), the run is abandoned quietly instead of
 *   attributing findings to the wrong commit;
 * - every comment slot is CLAIMED in comment_idempotency before the external
 *   call; posted ids are persisted and REUSED to reconstruct CommentsPosted on
 *   redelivery — at-least-once delivery never duplicates or silently loses;
 * - one finding's posting failure never aborts the batch; transient SCM/LLM
 *   failures are marked retryable, only permanent ones terminal;
 * - findings whose cited line is not in the diff fold into the summary;
 * - model output is sanitized (raw HTML escaped) and posted as markdown TEXT;
 *   suggestions render as fenced code the human applies — never auto-applied.
 */
@ApplicationScoped
public class ReviewWorker {

    private static final Logger LOG = Logger.getLogger(ReviewWorker.class);
    private static final String SUMMARY_KEY = "SUMMARY";

    @Inject
    WorkerScmClients scm;

    @Inject
    WorkerLlmProvider llm;

    @Inject
    CommentIdempotencyStore idempotency;

    @Inject
    ResultsEmitter results;

    @Inject
    ObjectMapper mapper;

    /** Idempotency slot for the paid LLM call itself (finding H4). */
    private static final String LLM_KEY = "LLM";

    public void generateReview(GenerateReview command) {
        try {
            DiffSource diffSource = scm.forCommand(command).diff();
            PullRequest pr = diffSource.fetchPullRequest(command.repo(), command.prId());
            if (!Commits.matches(pr.diffRefs().headSha(), command.commit())) {
                LOG.infof("Abandoning GenerateReview for %s: PR head moved past %s",
                        command.reviewId(), command.commit());
                return;
            }
            // Command-level dedup (finding H4): a redelivered GenerateReview for a
            // commit whose LLM call already completed must not pay twice. A
            // crashed claim (NULL) stays reclaimable — same semantics as comments.
            if (idempotency.claim(command.reviewId(), command.commit(), LLM_KEY)
                    instanceof CommentIdempotencyStore.Claim.AlreadyPosted already) {
                reEmitPersistedResult(command, already.commentId());
                return;
            }
            Diff diff = diffSource.fetchDiff(command.repo(), command.prId(), command.commit());

            ReviewPromptBuilder.Built built = ReviewPromptBuilder.build(pr, diff.files(), List.of()); // context items land in P2
            WorkerLlmProvider.LlmClient client = llm.forCommand(command);
            Completion completion = client.provider().complete(built.prompt(), client.params())
                    .toCompletableFuture().join();

            ReviewResult parsed = FindingsParser.parse(completion.text(), completion.usage());
            // Carry the "diff was clipped to fit the budget" flag so a partial review is
            // marked on the dashboard and the posted summary comment (not silent).
            ReviewResult result = built.truncated()
                    ? new ReviewResult(parsed.findings(), parsed.summary(), parsed.usage(), true)
                    : parsed;
            // Persist-then-emit (finding M2): marking first makes the paid call
            // unrepeatable; the serialized result keeps redelivery re-emittable
            // when a crash lands between the mark and the emit.
            idempotency.markPosted(command.reviewId(), command.commit(), LLM_KEY, writeResult(result));
            results.emit(new ReviewGenerated(command.reviewId(), command.prId(), command.commit(), result));
        } catch (RuntimeException e) {
            Throwable cause = unwrap(e);
            if (isCommitGone(cause)) {
                LOG.infof("Abandoning GenerateReview for %s: diff 404 — commit force-pushed away",
                        command.reviewId());
                return;
            }
            LOG.warnf(cause, "GenerateReview failed for %s", command.reviewId());
            results.emit(new ReviewFailed(command.reviewId(), command.commit(), "generate",
                    cause.getMessage(), isRetryable(cause), command.attempt()));
        }
    }

    /**
     * Redelivery of a completed LLM call: never pay twice, but always converge —
     * the original emit may not have reached the broker, so ReviewGenerated is
     * re-emitted from the result persisted at markPosted time (downstream is
     * idempotent by reviewId/commit). Legacy rows predating the persisted
     * payload were only marked after a successful emit, so skipping is safe.
     */
    private void reEmitPersistedResult(GenerateReview command, String payload) {
        ReviewResult persisted = readResult(payload);
        if (persisted == null) {
            LOG.infof("Skipping GenerateReview for %s: LLM call already completed for %s",
                    command.reviewId(), command.commit());
            return;
        }
        LOG.infof("Re-emitting ReviewGenerated for %s from the persisted LLM result", command.reviewId());
        results.emit(new ReviewGenerated(command.reviewId(), command.prId(), command.commit(), persisted));
    }

    private String writeResult(ReviewResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** @return the persisted result, or null when the row predates payload persistence. */
    private ReviewResult readResult(String payload) {
        try {
            return mapper.readValue(payload, ReviewResult.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public void postComments(PostComments command) {
        try {
            WorkerScmClients.Clients clients = scm.forCommand(command);
            DiffSource diffSource = clients.diff();
            CommentSink commentSink = clients.comments();
            PullRequest pr = diffSource.fetchPullRequest(command.repo(), command.prId());
            if (!Commits.matches(pr.diffRefs().headSha(), command.commit())) {
                LOG.infof("Abandoning PostComments for %s: PR head moved past %s",
                        command.reviewId(), command.commit());
                return;
            }
            // Anchors are resolved against the diff — one more idempotent GET.
            Diff diff = diffSource.fetchDiff(command.repo(), command.prId(), command.commit());
            ReviewResult review = command.findings();
            Map<String, String> previouslyPosted = idempotency.postedFor(command.reviewId(), command.commit());

            List<CommentsPosted.PostedInline> posted = new ArrayList<>();
            List<Finding> unanchored = new ArrayList<>();
            List<Finding> failed = new ArrayList<>();

            for (Finding finding : review.findings()) {
                postOneInline(commentSink, command, diff, finding, posted, unanchored, failed);
            }

            String summaryCommentId = postSummary(commentSink, command, review, unanchored, failed);
            if (summaryCommentId == null) {
                return; // summary failure already emitted ReviewFailed
            }
            // Include ids reconstructed from a previous partially-completed attempt.
            previouslyPosted.forEach((key, id) -> {
                if (!SUMMARY_KEY.equals(key) && posted.stream().noneMatch(p -> p.commentId().equals(id))) {
                    posted.add(new CommentsPosted.PostedInline(id, key, 0));
                }
            });
            results.emit(new CommentsPosted(command.reviewId(), command.prId(), command.commit(),
                    summaryCommentId, posted));
        } catch (RuntimeException e) {
            Throwable cause = unwrap(e);
            LOG.warnf(cause, "PostComments failed for %s", command.reviewId());
            results.emit(new ReviewFailed(command.reviewId(), command.commit(), "post-comments",
                    cause.getMessage(), isRetryable(cause), 1));
        }
    }

    private void postOneInline(CommentSink commentSink, PostComments command, Diff diff, Finding finding,
                               List<CommentsPosted.PostedInline> posted,
                               List<Finding> unanchored, List<Finding> failed) {
        Optional<InlineAnchor> anchor = Anchors.resolveNewLine(
                diff.files(), finding.path(), finding.range().startLine());
        if (anchor.isEmpty()) {
            unanchored.add(finding);
            return;
        }
        String key = anchor.get().anchorKey();
        switch (idempotency.claim(command.reviewId(), command.commit(), key)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                LOG.debugf("Skipping already-posted inline %s for %s", key, command.reviewId());
                posted.add(new CommentsPosted.PostedInline(already.commentId(),
                        finding.path(), finding.range().startLine()));
            }
            case CommentIdempotencyStore.Claim.Post ignored -> {
                try {
                    CommentRef ref = commentSink.postInline(command.repo(), command.prId(),
                            diff.refs(), anchor.get(), renderInline(finding));
                    idempotency.markPosted(command.reviewId(), command.commit(), key, ref.commentId());
                    posted.add(new CommentsPosted.PostedInline(ref.commentId(),
                            finding.path(), finding.range().startLine()));
                } catch (RuntimeException e) {
                    // Per-finding isolation: one failure never aborts the batch.
                    // The NULL claim row stays reclaimable for a retry.
                    LOG.warnf(unwrap(e), "Inline post failed for %s at %s", command.reviewId(), key);
                    failed.add(finding);
                }
            }
        }
    }

    /** @return the summary comment id, or null when posting it failed (ReviewFailed emitted). */
    private String postSummary(CommentSink commentSink, PostComments command, ReviewResult review,
                               List<Finding> unanchored, List<Finding> failed) {
        return switch (idempotency.claim(command.reviewId(), command.commit(), SUMMARY_KEY)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                LOG.debugf("Skipping already-posted summary for %s", command.reviewId());
                yield already.commentId();
            }
            case CommentIdempotencyStore.Claim.Post ignored -> {
                try {
                    CommentRef summary = commentSink.postSummary(command.repo(), command.prId(),
                            renderSummary(review, unanchored, failed, command.commit()));
                    idempotency.markPosted(command.reviewId(), command.commit(), SUMMARY_KEY, summary.commentId());
                    yield summary.commentId();
                } catch (RuntimeException e) {
                    Throwable cause = unwrap(e);
                    LOG.warnf(cause, "Summary post failed for %s", command.reviewId());
                    results.emit(new ReviewFailed(command.reviewId(), command.commit(), "post-comments",
                            cause.getMessage(), isRetryable(cause), 1));
                    yield null;
                }
            }
        };
    }

    // --- rendering (model output sanitized: raw HTML escaped, SECURITY.md) ---

    private String renderInline(Finding finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(finding.severity()).append("** ").append(escapeHtml(finding.message()));
        if (finding.suggestion() != null && !finding.suggestion().isBlank()) {
            // fenced code block: rendered inert, applied only by a human
            sb.append("\n\nSuggestion:\n```\n").append(finding.suggestion()).append("\n```");
        }
        return sb.toString();
    }

    private String renderSummary(ReviewResult review, List<Finding> unanchored,
                                 List<Finding> failed, String commit) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Code Spire review (`").append(commit).append("`)\n\n");
        if (review.truncated()) {
            sb.append("> **Partial review:** this PR's diff exceeded the review budget and was ")
                    .append("truncated — changes beyond the token limit were not reviewed.\n\n");
        }
        sb.append(escapeHtml(review.summary())).append('\n');
        if (!review.findings().isEmpty()) {
            sb.append("\n**Findings:** ").append(review.findings().size()).append('\n');
        }
        appendFindingList(sb, "\nNot anchorable to the diff (cited lines not in the change):\n", unanchored);
        appendFindingList(sb, "\nCould not be posted inline (SCM error — will appear on retry):\n", failed);
        return sb.toString();
    }

    private void appendFindingList(StringBuilder sb, String heading, List<Finding> findings) {
        if (findings.isEmpty()) {
            return;
        }
        sb.append(heading);
        for (Finding f : findings) {
            sb.append("- `").append(f.path()).append(':').append(f.range().startLine())
                    .append("` — ").append(f.severity()).append(' ').append(escapeHtml(f.message())).append('\n');
        }
    }

    private static String escapeHtml(String text) {
        return text == null ? "" : text.replace("<", "&lt;");
    }

    // --- failure classification ---

    private static Throwable unwrap(RuntimeException e) {
        return e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    private static boolean isCommitGone(Throwable cause) {
        return cause instanceof ScmApiException api && api.isNotFound();
    }

    /** Transient (SCM 5xx/429, LLM retriable, I/O, timeout) -> retryable; client errors -> terminal. */
    private static boolean isRetryable(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ScmApiException api && (api.status() >= 500 || api.isRateLimited())) {
                return true;
            }
            if (t instanceof UncheckedIOException || t instanceof java.io.IOException
                    || t instanceof java.util.concurrent.TimeoutException
                    || isLangChain4jRetriable(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The worker never compiles against LangChain4j (it stays an implementation
     * detail of spire-llm), so its transient provider failures — RateLimitException,
     * InternalServerException and TimeoutException all extend RetriableException —
     * are recognized by hierarchy name.
     */
    private static final String LANGCHAIN4J_RETRIABLE = "dev.langchain4j.exception.RetriableException";

    private static boolean isLangChain4jRetriable(Throwable t) {
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            if (LANGCHAIN4J_RETRIABLE.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }
}
