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
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.diff.Anchors;
import dev.codespire.llm.FindingsParser;
import dev.codespire.llm.ReviewPromptBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    static final String SUMMARY_KEY = "SUMMARY";

    @Inject
    WorkerScmClients scm;

    @Inject
    WorkerLlmProvider llm;

    @Inject
    CommentIdempotencyStore idempotency;

    @Inject
    ResultsEmitter results;

    @Inject
    PromptLog promptLog;

    @Inject
    AssembledContextReader contextReader;

    @Inject
    ObjectMapper mapper;

    /** Fixed pacing sleep between successful inline posts — avoids GitHub's secondary rate
     *  limit on bursty comment creation. 0 disables it (tests). */
    @ConfigProperty(name = "spire.scm.inline-post-throttle-ms", defaultValue = "500")
    long inlinePostThrottleMs;

    /** Upper bound on the backoff sleep for a single rate-limited inline post retry. */
    @ConfigProperty(name = "spire.scm.rate-limit-retry-cap-seconds", defaultValue = "120")
    long rateLimitRetryCapSeconds;

    /** Ceiling on TOTAL inline-retry sleep across every finding in one postComments message.
     *  Budgeted against the ack threshold, NOT max.poll.interval.ms: SmallRye pauses fetching
     *  rather than polling, so a long hold cannot trip the poll interval or cause a rebalance —
     *  what it can do is age the record past throttled.unprocessed-record-max-age.ms and fail the
     *  channel. LlmTimeoutBudget.NON_LLM_OVERHEAD_MS covers this sleep and refuses a pairing that
     *  does not leave room for it. */
    @ConfigProperty(name = "spire.scm.rate-limit-budget-seconds", defaultValue = "180")
    long rateLimitBudgetSeconds;

    /** Idempotency slot for the paid LLM call itself (finding H4). */
    static final String LLM_KEY = "LLM";

    /** What the review call reads besides the command: the pull request, its diff, its context, the model client. */
    private record ReviewMaterial(PullRequest pr, Diff diff, List<ContextItem> context,
                                  WorkerLlmProvider.LlmClient client) {
    }

    public void generateReview(GenerateReview command) {
        try {
            WorkerScmClients.Clients clients = scm.forCommand(command);
            DiffSource diffSource = clients.diff();
            PullRequest pr = diffSource.fetchPullRequest(command.repo(), command.prId());
            if (!Commits.matches(pr.headCommit(), command.commit())) {
                LOG.infof("Abandoning GenerateReview for %s: PR head moved past %s",
                        command.reviewId(), command.commit());
                return;
            }
            Diff diff = diffSource.fetchDiff(command.repo(), command.prId(), command.commit());
            List<ContextItem> context = contextReader.load(command.contextRef());
            WorkerLlmProvider.LlmClient client = llm.forCommand(command);

            // ADR-019: reconcile (its own claim) runs before the LLM_KEY switch below. Skipped
            // when there are no prior findings to judge — that call is guaranteed to yield zero
            // verdicts, so paying for it would be pure waste (the summary-update path doesn't
            // depend on verdicts either, so skipping here is otherwise a no-op).
            PriorRun prior = command.priorRun();
            ReconcileCall.Reconciliation reconciliation = prior == null || prior.findings().isEmpty()
                    ? ReconcileCall.Reconciliation.NONE
                    : new ReconcileCall(new ReconcileCall.Ports(idempotency, promptLog, mapper))
                            .run(command, new ReconcileCall.Sources(clients, diff, client));

            // Command-level dedup (finding H4): a redelivered GenerateReview for a
            // commit whose LLM call already completed must not pay twice. A
            // crashed claim (NULL) stays reclaimable — same semantics as comments.
            switch (idempotency.claim(command.reviewId(), command.commit(), LLM_KEY)) {
                case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                    reEmitPersistedResult(command, already.postedRef(), reconciliation.outcome());
                    return;
                }
                case CommentIdempotencyStore.Claim.Post ignored -> { }
            }
            runReviewCall(command, new ReviewMaterial(pr, diff, context, client), reconciliation);
        } catch (RuntimeException e) {
            Throwable cause = WorkerFailures.unwrap(e);
            if (WorkerFailures.isCommitGone(cause)) {
                LOG.infof("Abandoning GenerateReview for %s: diff 404 — commit force-pushed away (%s)",
                        command.reviewId(), cause.getMessage());
                return;
            }
            LOG.warnf(cause, "GenerateReview failed for %s", command.reviewId());
            results.emit(new ReviewFailed(command.reviewId(), command.commit(), "generate",
                    cause.getMessage(), WorkerFailures.isRetryable(cause), command.attempt(),
                    WorkerFailures.isCredentialRejected(cause)));
        }
    }

    /**
     * The review call: prompt (with the prior-run exclusion list, if any) -> LLM -> parse ->
     * drop still-open anchor collisions -> persist-then-emit. Split out of generateReview to
     * keep that method within the method-length budget.
     */
    private void runReviewCall(GenerateReview command, ReviewMaterial material,
                               ReconcileCall.Reconciliation reconciliation) {
        WorkerLlmProvider.LlmClient client = material.client();
        ReviewPromptBuilder.Built built = ReviewPromptBuilder.build(new ReviewPromptBuilder.ReviewInput(material.pr(),
                        material.diff().files(), material.context(), reconciliation.exclusions()),
                command.reviewPrompt(), client.limits());
        Completion completion = PaidCalls.complete(promptLog.tap("review", client.provider()), client.params(),
                new PaidCalls.Call(built.prompt(), built.size()));

        ReconcileCall.ReconcileOutcome reconcile = reconciliation.outcome();
        ReviewResult parsed = ReviewClipping.apply(FindingsParser.parse(completion), built, reconcile);
        ReviewResult result = reconcile == null
                ? parsed
                : ReconcileSteps.dropAnchorCollisions(parsed, reconcile.verdicts());

        // Persist-then-emit (finding M2): marking first makes the paid call
        // unrepeatable; the serialized result keeps redelivery re-emittable
        // when a crash lands between the mark and the emit.
        idempotency.markPosted(command.reviewId(), command.commit(), LLM_KEY, writeResult(result));
        results.emit(reconcile == null
                ? new ReviewGenerated(command.reviewId(), command.prId(), command.commit(), result)
                : new ReviewGenerated(command.reviewId(), command.prId(), command.commit(),
                        result, reconcile.verdicts(), reconcile.usage()));
    }

    /**
     * Redelivery of a completed LLM call: never pay twice, but always converge —
     * the original emit may not have reached the broker, so ReviewGenerated is
     * re-emitted from the result persisted at markPosted time (downstream is
     * idempotent by reviewId/commit). Legacy rows predating the persisted
     * payload were only marked after a successful emit, so skipping is safe.
     */
    private void reEmitPersistedResult(GenerateReview command, String payload,
                                       ReconcileCall.ReconcileOutcome reconcile) {
        ReviewResult persisted = readResult(payload);
        if (persisted == null) {
            LOG.infof("Skipping GenerateReview for %s: LLM call already completed for %s",
                    command.reviewId(), command.commit());
            return;
        }
        LOG.infof("Re-emitting ReviewGenerated for %s from the persisted LLM result", command.reviewId());
        results.emit(reconcile == null
                ? new ReviewGenerated(command.reviewId(), command.prId(), command.commit(), persisted)
                : new ReviewGenerated(command.reviewId(), command.prId(), command.commit(),
                        persisted, reconcile.verdicts(), reconcile.usage()));
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
            if (!Commits.matches(pr.headCommit(), command.commit())) {
                LOG.infof("Abandoning PostComments for %s: PR head moved past %s",
                        command.reviewId(), command.commit());
                return;
            }
            // Anchors are resolved against the diff — one more idempotent GET.
            Diff diff = diffSource.fetchDiff(command.repo(), command.prId(), command.commit());
            ReviewResult review = command.findings();
            Map<String, String> previouslyPosted = idempotency.postedFor(command.reviewId(), command.commit());

            // Reconciliation (ADR-019): reply into (and where supported, resolve) each
            // verdict's existing thread BEFORE posting genuinely new findings. Idempotent
            // per-thread, so redelivery reconstructs outcomes from the claim map for free.
            List<CommentsPosted.ThreadOutcome> outcomes = actOnVerdicts(commentSink, command);

            // Fix 1: one budget per message, shared across every finding's retry loop.
            BackoffBudget backoffBudget = new BackoffBudget(rateLimitBudgetSeconds);
            if (command.assessment() != null) {
                NativeReviewPost.Rendering rendering = new NativeReviewPost.Rendering(
                        (unanchored, failed) -> renderSummary(review, unanchored, failed, command.commit(), command.suppressedCount())
                                + renderReconciliationSection(command.verdicts()),
                        this::renderInline,
                        (anchor, body) -> {
                            CommentRef ref = postInlineWithBackoff(commentSink, command, diff, anchor, body, backoffBudget);
                            pace();
                            return ref;
                        });
                Optional<NativeReviewPost.Outcome> nativeOutcome = new NativeReviewPost(
                        new NativeReviewPost.Ports(idempotency, commentSink, rendering), command, diff).run();
                if (nativeOutcome.isPresent()) {
                    NativeReviewPost.Outcome outcome = nativeOutcome.orElseThrow();
                    List<CommentsPosted.PostedInline> posted = new ArrayList<>(outcome.inline());
                    InlineRecovery.recover(previouslyPosted, posted);
                    results.emit(new CommentsPosted(command.reviewId(), command.prId(), command.commit(),
                            NativeReviewPost.REF_PREFIX + outcome.reviewRef(), posted, outcomes)
                            .withPostedReview(outcome.postedVerdict(), outcome.reviewRef(), outcome.reviewUrl())
                            .withLabelFailure(new VerdictLabels(idempotency, commentSink).apply(command)));
                    return;
                }
            }

            List<CommentsPosted.PostedInline> posted = new ArrayList<>();
            List<Finding> unanchored = new ArrayList<>();
            List<Finding> failed = new ArrayList<>();
            for (Finding finding : review.findings()) {
                postOneInline(commentSink, command, diff, finding, posted, unanchored, failed, backoffBudget);
            }

            String summaryThreadRef = postSummary(commentSink, command, review, unanchored, failed);
            if (summaryThreadRef == null) {
                return; // summary failure already emitted ReviewFailed
            }
            InlineRecovery.recover(previouslyPosted, posted);
            results.emit(new CommentsPosted(command.reviewId(), command.prId(), command.commit(),
                    summaryThreadRef, posted, outcomes));
        } catch (RuntimeException e) {
            Throwable cause = WorkerFailures.unwrap(e);
            LOG.warnf(cause, "PostComments failed for %s", command.reviewId());
            emitPostCommentsFailed(command, cause);
        }
    }

    /** A PostComments, or its summary, that failed on its one attempt. */
    private void emitPostCommentsFailed(PostComments command, Throwable cause) {
        results.emit(new ReviewFailed(command.reviewId(), command.commit(), "post-comments",
                cause.getMessage(), WorkerFailures.isRetryable(cause), 1,
                WorkerFailures.isCredentialRejected(cause)));
    }

    // --- reconciliation thread actions (ADR-019) ---

    private List<CommentsPosted.ThreadOutcome> actOnVerdicts(CommentSink sink, PostComments command) {
        List<CommentsPosted.ThreadOutcome> outcomes = new ArrayList<>();
        for (FindingVerdict verdict : command.verdicts()) {
            // UNCHANGED means the follow-up never touched this finding — no reply, no resolve,
            // no thread interaction at all. The dashboard badge comes from reconciliation_json
            // (written from ALL verdicts at ReviewGenerated), not from ThreadOutcome.
            if (verdict.status() == FindingVerdict.Status.UNCHANGED) {
                continue;
            }
            if (verdict.threadRef() != null) {
                outcomes.add(actOnVerdict(sink, command, verdict));
            }
        }
        return outcomes;
    }

    /**
     * One verdict's thread action: a closing verdict (anything but STILL_OPEN) first tries to
     * resolve the thread — a human who beat us to it (ALREADY_RESOLVED) means we stay quiet and
     * skip the reply entirely; otherwise (resolved-by-us or unsupported) a reply always follows.
     * STILL_OPEN never resolves and always replies. UNCHANGED never reaches this method — the
     * caller (actOnVerdicts) skips it before dispatch, so "closing" here is only ever computed
     * over {@code RESOLVED, ACKNOWLEDGED, SUPERSEDED, STILL_OPEN}.
     */
    private CommentsPosted.ThreadOutcome actOnVerdict(CommentSink sink, PostComments command,
                                                      FindingVerdict verdict) {
        ThreadRef thread = new ThreadRef(verdict.threadRef());
        boolean closing = verdict.status() != FindingVerdict.Status.STILL_OPEN;
        boolean resolvedByBot = false;
        boolean humanResolved = false;
        if (closing) {
            String resolveKey = "resolve:" + verdict.threadRef();
            switch (idempotency.claim(command.reviewId(), command.commit(), resolveKey)) {
                case CommentIdempotencyStore.Claim.AlreadyPosted a -> {
                    resolvedByBot = "bot".equals(a.postedRef());
                    humanResolved = "human".equals(a.postedRef());
                }
                case CommentIdempotencyStore.Claim.Post ignored -> {
                    CommentSink.ThreadResolution resolution = resolveQuietly(sink, command, thread);
                    resolvedByBot = resolution == CommentSink.ThreadResolution.RESOLVED_NOW;
                    humanResolved = resolution == CommentSink.ThreadResolution.ALREADY_RESOLVED;
                    idempotency.markPosted(command.reviewId(), command.commit(), resolveKey,
                            humanResolved ? "human" : resolvedByBot ? "bot" : "unsupported");
                }
            }
            if (humanResolved) {
                // A human closed it first — nothing to add (skip the reply on closing verdicts).
                return new CommentsPosted.ThreadOutcome(verdict.threadRef(), verdict.status(), null, true);
            }
        }
        String replyId = postReply(sink, command, thread, verdict);
        return new CommentsPosted.ThreadOutcome(verdict.threadRef(), verdict.status(), replyId, resolvedByBot);
    }

    private CommentSink.ThreadResolution resolveQuietly(CommentSink sink, PostComments command, ThreadRef thread) {
        try {
            return sink.resolveThread(command.repo(), command.prId(), thread);
        } catch (RuntimeException e) {
            LOG.warnf(WorkerFailures.unwrap(e), "resolve %s failed — continuing reply-only", thread.value());
            return CommentSink.ThreadResolution.UNSUPPORTED;
        }
    }

    private String postReply(CommentSink sink, PostComments command, ThreadRef thread, FindingVerdict verdict) {
        String replyKey = "reply:" + thread.value();
        switch (idempotency.claim(command.reviewId(), command.commit(), replyKey)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                return already.postedRef();
            }
            case CommentIdempotencyStore.Claim.Post ignored -> {
                try {
                    CommentRef reply = sink.replyInThread(command.repo(), command.prId(), thread,
                            renderVerdictReply(verdict, command.commit()));
                    idempotency.markPosted(command.reviewId(), command.commit(), replyKey, reply.commentId());
                    return reply.commentId();
                } catch (RuntimeException e) {
                    LOG.warnf(WorkerFailures.unwrap(e), "reply in %s failed", thread.value());
                    return null;   // claim stays NULL -> reclaimable on redelivery
                }
            }
        }
    }

    private String renderVerdictReply(FindingVerdict verdict, String commit) {
        String sha = commit.length() > 7 ? commit.substring(0, 7) : commit;
        String note = verdict.note() == null || verdict.note().isBlank() ? "" : " " + verdict.note();
        return escapeHtml(switch (verdict.status()) {
            case RESOLVED -> "**Fixed in `" + sha + "`.**" + note;
            case STILL_OPEN -> "**Still open after `" + sha + "`:**" + note;
            case ACKNOWLEDGED -> "**Acknowledged** — closing this thread." + note;
            case SUPERSEDED -> "**The flagged code changed in `" + sha + "`** — this finding no longer applies." + note;
            case UNCHANGED -> throw new IllegalStateException("UNCHANGED verdicts never reply");
        });
    }

    private void postOneInline(CommentSink commentSink, PostComments command, Diff diff, Finding finding,
                               List<CommentsPosted.PostedInline> posted, List<Finding> unanchored,
                               List<Finding> failed, BackoffBudget backoffBudget) {
        Optional<InlineAnchor> anchor = Anchors.resolveLine(
                diff.files(), finding.path(), finding.range().startLine(), finding.range().endLine());
        if (anchor.isEmpty()) {
            unanchored.add(finding);
            return;
        }
        String key = anchor.get().anchorKey();
        switch (idempotency.claim(command.reviewId(), command.commit(), key)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                LOG.debugf("Skipping already-posted inline %s for %s", key, command.reviewId());
                posted.add(new CommentsPosted.PostedInline(already.postedRef(),
                        finding.path(), finding.range().startLine()));
            }
            case CommentIdempotencyStore.Claim.Post ignored -> {
                try {
                    CommentRef ref = postInlineWithBackoff(commentSink, command, diff, anchor.get(),
                            escapeHtml(renderInline(finding)), backoffBudget);
                    // Both ids: the comment identifies what was posted, the thread identifies where a
                    // reply will land. GitLab's discussion id differs from its note id, and ownership is
                    // keyed by the thread — recording only the comment left the bot unable to recognize
                    // its own thread.
                    // The THREAD is what gets recorded — the id a human's reply arrives under, and the only
                    // thing the core does with a posted finding. What that id IS stays the adapter's
                    // business: a comment id on GitHub and Bitbucket, a discussion id on GitLab.
                    idempotency.markPosted(command.reviewId(), command.commit(), key, ref.thread().value());
                    posted.add(new CommentsPosted.PostedInline(ref.thread().value(),
                            finding.path(), finding.range().startLine()));
                    pace();
                } catch (RuntimeException e) {
                    // Per-finding isolation: one failure never aborts the batch.
                    // The NULL claim row stays reclaimable for a retry.
                    LOG.warnf(WorkerFailures.unwrap(e), "Inline post failed for %s at %s", command.reviewId(), key);
                    failed.add(finding);
                }
            }
        }
    }

    private static final int MAX_INLINE_ATTEMPTS = 3;

    /**
     * Shared, mutable per-message backoff ceiling (Fix 1): one instance is created per
     * {@code postComments} call and threaded through every finding's retry loop, so the TOTAL
     * sleep across the whole batch is bounded — not just each finding's own attempts.
     */
    private static final class BackoffBudget {

        private long remainingSeconds;

        BackoffBudget(long totalSeconds) {
            this.remainingSeconds = totalSeconds;
        }

        boolean isExhausted() {
            return remainingSeconds <= 0;
        }

        long remainingSeconds() {
            return remainingSeconds;
        }

        void spend(long seconds) {
            remainingSeconds -= seconds;
        }
    }

    /**
     * GitHub secondary rate limits hit bursty comment creation; honor Retry-After with a
     * bounded in-loop retry instead of dropping the finding. Non-rate-limit failures keep
     * the existing fail-fast per-finding isolation. The shared {@code budget} bounds the TOTAL
     * sleep across every finding in the message — once exhausted, this finding gives up
     * immediately (no sleep) and folds into the summary failed-list like an attempt exhaustion.
     */
    private CommentRef postInlineWithBackoff(CommentSink sink, PostComments command, Diff diff,
                                             InlineAnchor anchor, String body, BackoffBudget budget) {
        for (int attempt = 1; ; attempt++) {
            try {
                return sink.postInline(command.repo(), command.prId(), diff.headCommit(), anchor, body);
            } catch (RuntimeException e) {
                Throwable cause = WorkerFailures.unwrap(e);
                if (attempt >= MAX_INLINE_ATTEMPTS || !(cause instanceof ScmApiException api)
                        || !api.isRateLimited()) {
                    throw e;
                }
                if (budget.isExhausted()) {
                    LOG.infof("Rate-limit backoff budget exhausted posting inline (%s) — giving up without sleeping",
                            api.status());
                    throw e;
                }
                sleepAndSpendBudget(api, attempt, budget);
            }
        }
    }

    /** Computes the bounded sleep (single-retry cap AND remaining message budget), sleeps, then
     *  debits the budget by exactly what was slept. */
    private void sleepAndSpendBudget(ScmApiException api, int attempt, BackoffBudget budget) {
        long waitSeconds = api.retryAfterSeconds() != null ? api.retryAfterSeconds() : (1L << attempt);
        long cappedSeconds = Math.min(waitSeconds, Math.min(rateLimitRetryCapSeconds, budget.remainingSeconds()));
        long sleepSeconds = Math.max(0, cappedSeconds);
        sleepSeconds(sleepSeconds);
        budget.spend(sleepSeconds);
        LOG.infof("Rate limited posting inline (%s) — retry %d/%d",
                api.status(), attempt + 1, MAX_INLINE_ATTEMPTS);
    }

    private void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during rate-limit backoff", e);
        }
    }

    /** Fixed pacing delay after a successful inline post; 0 disables it (tests). */
    private void pace() {
        if (inlinePostThrottleMs <= 0) {
            return;
        }
        try {
            Thread.sleep(inlinePostThrottleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return the summary THREAD ref, or null when posting it failed (ReviewFailed emitted). */
    private String postSummary(CommentSink commentSink, PostComments command, ReviewResult review,
                               List<Finding> unanchored, List<Finding> failed) {
        return switch (idempotency.claim(command.reviewId(), command.commit(), SUMMARY_KEY)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                LOG.debugf("Skipping already-posted summary for %s", command.reviewId());
                yield already.postedRef();
            }
            case CommentIdempotencyStore.Claim.Post ignored -> {
                try {
                    String body = renderSummary(review, unanchored, failed, command.commit(),
                            command.suppressedCount())
                            + renderReconciliationSection(command.verdicts());
                    CommentRef summary = updateOrPost(commentSink, command, body);
                    // The THREAD, matching what inline slots record: it is what core needs as the
                    // conversation key AND what updateComment rewrites next round. Recording the
                    // comment id instead left core casting a comment id to a ThreadRef — the same
                    // "a comment is its own thread root" assumption that broke inline replies.
                    idempotency.markPosted(command.reviewId(), command.commit(), SUMMARY_KEY,
                            summary.thread().value());
                    yield summary.thread().value();
                } catch (RuntimeException e) {
                    Throwable cause = WorkerFailures.unwrap(e);
                    LOG.warnf(cause, "Summary post failed for %s", command.reviewId());
                    emitPostCommentsFailed(command, cause);
                    yield null;
                }
            }
        };
    }

    /** Update the prior summary in place on a follow-up review; only a 404 (the human deleted it)
     *  falls back to a fresh post — every other failure (lost permission, transient error)
     *  rethrows so it classifies through the normal summary failure path instead of risking a
     *  doomed second post. */
    private CommentRef updateOrPost(CommentSink sink, PostComments command, String body) {
        if (command.priorSummaryRef() != null) {
            try {
                return sink.updateComment(command.repo(), command.prId(),
                        new ThreadRef(command.priorSummaryRef()), body);
            } catch (RuntimeException e) {
                if (!(WorkerFailures.unwrap(e) instanceof ScmApiException api) || !api.isNotFound()) {
                    throw e;   // permission/transient failures classify upstream; don't double-post
                }
                LOG.infof("summary %s is gone (404) — posting fresh", command.priorSummaryRef());
            }
        }
        return sink.postSummary(command.repo(), command.prId(), body);
    }

    private String renderReconciliationSection(List<FindingVerdict> verdicts) {
        if (verdicts.isEmpty()) {
            return "";
        }
        long closed = verdicts.stream().filter(v -> v.status() == FindingVerdict.Status.RESOLVED
                || v.status() == FindingVerdict.Status.ACKNOWLEDGED
                || v.status() == FindingVerdict.Status.SUPERSEDED).count();
        long open = verdicts.size() - closed; // STILL_OPEN + UNCHANGED
        return "\n\n---\n**Reconciliation:** " + closed + " closed · " + open + " still open.";
    }

    // --- rendering (model output sanitized: raw HTML escaped, SECURITY.md) ---

    private String renderInline(Finding finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(finding.severity()).append("** ").append(finding.message());
        if (finding.suggestion() != null && !finding.suggestion().isBlank()) {
            // fenced code block: rendered inert, applied only by a human
            sb.append("\n\nSuggestion:\n```\n").append(finding.suggestion()).append("\n```");
        }
        // Unescaped: the comment it goes into is escaped whole, since text around it changes what its Markdown means.
        return sb.toString();
    }

    private String renderSummary(ReviewResult review, List<Finding> unanchored,
                                 List<Finding> failed, String commit, int suppressedCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Code Spire review (`").append(commit).append("`)\n\n");
        if (review.truncated()) {
            sb.append(PartialReviewNote.markdown(review.omittedFiles()));
        }
        sb.append(review.summary() == null ? "" : review.summary()).append('\n');
        if (!review.findings().isEmpty()) {
            sb.append("\n**Findings:** ").append(review.findings().size()).append('\n');
        }
        appendFindingList(sb, "\nNot anchorable to the diff (cited lines not in the change):\n", unanchored);
        appendFindingList(sb, "\nCould not be posted inline (SCM error):\n", failed);
        if (suppressedCount > 0) {
            // Said out loud, on the artefact the author actually reads, because a filter nobody can
            // see is the failure mode this whole design avoids: if a learned preference is wrong,
            // the only way anyone finds out is by noticing the count and going to look.
            sb.append("\n_").append(suppressedCount)
                    .append(suppressedCount == 1 ? " finding was" : " findings were")
                    .append(" hidden by a learned preference an operator approved ")
                    .append("(Settings -> Memory). Switching that preference off brings them ")
                    .append("back on the next review._\n");
        }
        sb.append("\nRun `/finding` in any inline thread to file what you discussed there as a tracked finding.\n");
        return escapeHtml(sb.toString());
    }

    private void appendFindingList(StringBuilder sb, String heading, List<Finding> findings) {
        if (findings.isEmpty()) {
            return;
        }
        sb.append(heading);
        for (Finding f : findings) {
            sb.append("- `").append(f.path()).append(':').append(f.range().startLine())
                    .append("` — ").append(f.severity()).append(' ').append(f.message()).append('\n');
        }
    }

    /** Raw HTML in a finished comment is escaped outside Markdown code; code keeps its text, so `List<Double>` reads as written. */
    private static String escapeHtml(String text) {
        return MarkdownHtmlEscape.outsideCode(text);
    }
}
