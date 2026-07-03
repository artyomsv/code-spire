package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.diff.Anchors;
import dev.codespire.llm.FindingsParser;
import dev.codespire.llm.ReviewPromptBuilder;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import dev.codespire.scm.bitbucket.BitbucketApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
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
 * Posting rules (ADR-013 / SECURITY.md, hardened per review findings H1/H2/M1):
 * - the PR head is re-checked before the LLM call and before posting — if it
 *   moved (webhook not yet processed), the run is abandoned quietly instead of
 *   attributing findings to the wrong commit;
 * - every comment slot is CLAIMED in comment_idempotency before the external
 *   call; posted ids are persisted and REUSED to reconstruct CommentsPosted on
 *   redelivery — at-least-once delivery never duplicates or silently loses;
 * - one finding's posting failure never aborts the batch (per-finding
 *   isolation); transient SCM/LLM failures are marked retryable, only
 *   permanent ones terminal;
 * - findings whose cited line is not in the diff fold into the summary;
 * - model output is sanitized (raw HTML escaped) and posted as markdown TEXT;
 *   suggestions render as fenced code the human applies — never auto-applied.
 */
@ApplicationScoped
public class ReviewWorker {

    private static final Logger LOG = Logger.getLogger(ReviewWorker.class);
    private static final String SUMMARY_KEY = "SUMMARY";

    @Inject
    DiffSource diffSource;

    @Inject
    CommentSink commentSink;

    @Inject
    LlmProvider llm;

    @Inject
    CommentIdempotencyStore idempotency;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    @Channel("results")
    Emitter<IntegrationEvent> results;

    public void generateReview(GenerateReview command) {
        try {
            PullRequest pr = diffSource.fetchPullRequest(command.repo(), command.prId());
            if (!Commits.matches(pr.diffRefs().headSha(), command.commit())) {
                abandon("GenerateReview", command.reviewId(), "PR head moved past " + command.commit());
                return;
            }
            Diff diff = diffSource.fetchDiff(command.repo(), command.prId(), command.commit());

            Prompt prompt = ReviewPromptBuilder.build(pr, diff.files(), List.of()); // context items land in P2
            Completion completion = llm.complete(prompt,
                    new ModelParams(llm.id(), 0.2, null)).toCompletableFuture().join();

            ReviewResult result = FindingsParser.parse(completion.text(), completion.usage());
            emit(new ReviewGenerated(command.reviewId(), command.prId(), command.commit(), result));
        } catch (RuntimeException e) {
            Throwable cause = unwrap(e);
            if (isCommitGone(cause)) {
                abandon("GenerateReview", command.reviewId(), "diff 404 — commit force-pushed away");
                return;
            }
            LOG.warnf(cause, "GenerateReview failed for %s", command.reviewId());
            emit(new ReviewFailed(command.reviewId(), command.commit(), "generate",
                    cause.getMessage(), isRetryable(cause), command.attempt()));
        }
    }

    public void postComments(PostComments command) {
        try {
            PullRequest pr = diffSource.fetchPullRequest(command.repo(), command.prId());
            if (!Commits.matches(pr.diffRefs().headSha(), command.commit())) {
                abandon("PostComments", command.reviewId(), "PR head moved past " + command.commit());
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
                postOneInline(command, diff, finding, posted, unanchored, failed);
            }

            String summaryCommentId = postSummary(command, review, unanchored, failed);
            if (summaryCommentId == null) {
                return; // summary failure already emitted ReviewFailed
            }
            // Include ids reconstructed from a previous partially-completed attempt.
            previouslyPosted.forEach((key, id) -> {
                if (!SUMMARY_KEY.equals(key) && posted.stream().noneMatch(p -> p.commentId().equals(id))) {
                    posted.add(new CommentsPosted.PostedInline(id, key, 0));
                }
            });
            emit(new CommentsPosted(command.reviewId(), command.prId(), command.commit(),
                    summaryCommentId, posted));
        } catch (RuntimeException e) {
            Throwable cause = unwrap(e);
            LOG.warnf(cause, "PostComments failed for %s", command.reviewId());
            emit(new ReviewFailed(command.reviewId(), command.commit(), "post-comments",
                    cause.getMessage(), isRetryable(cause), 1));
        }
    }

    private void postOneInline(PostComments command, Diff diff, Finding finding,
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
                timeline.record("command", "skipped:inline", command.reviewId(), "already posted " + key);
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
                    // Per-finding isolation (H2): one failure never aborts the
                    // batch. The NULL claim row stays reclaimable for a retry.
                    LOG.warnf(unwrap(e), "Inline post failed for %s at %s", command.reviewId(), key);
                    failed.add(finding);
                }
            }
        }
    }

    /** @return the summary comment id, or null when posting it failed (ReviewFailed emitted). */
    private String postSummary(PostComments command, ReviewResult review,
                               List<Finding> unanchored, List<Finding> failed) {
        return switch (idempotency.claim(command.reviewId(), command.commit(), SUMMARY_KEY)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                timeline.record("command", "skipped:summary", command.reviewId(), "already posted");
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
                    emit(new ReviewFailed(command.reviewId(), command.commit(), "post-comments",
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

    // --- failure classification (H2/M3) ---

    private static Throwable unwrap(RuntimeException e) {
        return e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    private static boolean isCommitGone(Throwable cause) {
        return cause instanceof BitbucketApiException api && api.isNotFound();
    }

    /** Transient (5xx, I/O, timeout) -> retryable; client errors -> terminal. */
    private static boolean isRetryable(Throwable cause) {
        if (cause instanceof BitbucketApiException api) {
            return api.status() >= 500;
        }
        return cause instanceof UncheckedIOException || cause instanceof java.util.concurrent.TimeoutException;
    }

    private void abandon(String phase, String reviewId, String why) {
        timeline.record("command", "abandoned:" + phase, reviewId, why);
    }

    private void emit(IntegrationEvent event) {
        results.send(event).whenComplete((v, t) -> {
            if (t != null) {
                LOG.warnf(t, "Failed to emit %s", event.getClass().getSimpleName());
            }
        });
    }
}
