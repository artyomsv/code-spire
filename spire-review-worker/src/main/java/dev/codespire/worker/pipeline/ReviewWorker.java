package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.worker.adapters.WorkerLlmProvider;
import dev.codespire.worker.adapters.WorkerScmClients;
import dev.codespire.contract.review.AssembledContext;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.diff.Anchors;
import dev.codespire.diff.DiffRenderer;
import dev.codespire.diff.UnifiedDiffParser;
import dev.codespire.llm.FindingsParser;
import dev.codespire.llm.ReconcilePrompt;
import dev.codespire.llm.ReviewPromptBuilder;
import dev.codespire.llm.VerdictsParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    BlobStore blobStore;

    @Inject
    ObjectMapper mapper;

    /** Idempotency slot for the paid LLM call itself (finding H4). */
    private static final String LLM_KEY = "LLM";

    /** Idempotency slot for the reconcile call (ADR-019) — same keyspace, its own claim. */
    static final String RECONCILE_KEY = "LLM:reconcile";

    /** Persisted under RECONCILE_KEY so redelivery replays verdicts without a second spend. */
    private record ReconcileOutcome(List<FindingVerdict> verdicts, ModelUsage usage) {
    }

    public void generateReview(GenerateReview command) {
        try {
            WorkerScmClients.Clients clients = scm.forCommand(command);
            DiffSource diffSource = clients.diff();
            PullRequest pr = diffSource.fetchPullRequest(command.repo(), command.prId());
            if (!Commits.matches(pr.diffRefs().headSha(), command.commit())) {
                LOG.infof("Abandoning GenerateReview for %s: PR head moved past %s",
                        command.reviewId(), command.commit());
                return;
            }
            Diff diff = diffSource.fetchDiff(command.repo(), command.prId(), command.commit());
            List<ContextItem> context = loadContext(command.contextRef());
            WorkerLlmProvider.LlmClient client = llm.forCommand(command);

            // ADR-019: reconcile (its own claim) runs before the LLM_KEY switch below. Skipped
            // when there are no prior findings to judge — that call is guaranteed to yield zero
            // verdicts, so paying for it would be pure waste (the summary-update path doesn't
            // depend on verdicts either, so skipping here is otherwise a no-op).
            PriorRun prior = command.priorRun();
            ReconcileOutcome reconcile = prior == null || prior.findings().isEmpty()
                    ? null : reconcile(command, clients, diff, prior, client);

            // Command-level dedup (finding H4): a redelivered GenerateReview for a
            // commit whose LLM call already completed must not pay twice. A
            // crashed claim (NULL) stays reclaimable — same semantics as comments.
            switch (idempotency.claim(command.reviewId(), command.commit(), LLM_KEY)) {
                case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                    reEmitPersistedResult(command, already.commentId(), reconcile);
                    return;
                }
                case CommentIdempotencyStore.Claim.Post ignored -> { }
            }
            runReviewCall(command, pr, diff, context, prior, client, reconcile);
        } catch (RuntimeException e) {
            Throwable cause = unwrap(e);
            if (isCommitGone(cause)) {
                LOG.infof("Abandoning GenerateReview for %s: diff 404 — commit force-pushed away (%s)",
                        command.reviewId(), cause.getMessage());
                return;
            }
            LOG.warnf(cause, "GenerateReview failed for %s", command.reviewId());
            results.emit(new ReviewFailed(command.reviewId(), command.commit(), "generate",
                    cause.getMessage(), isRetryable(cause), command.attempt()));
        }
    }

    /**
     * The review call: prompt (with the prior-run exclusion list, if any) -> LLM -> parse ->
     * drop still-open anchor collisions -> persist-then-emit. Split out of generateReview to
     * keep that method within the method-length budget.
     */
    private void runReviewCall(GenerateReview command, PullRequest pr, Diff diff, List<ContextItem> context,
                               PriorRun prior, WorkerLlmProvider.LlmClient client, ReconcileOutcome reconcile) {
        List<PriorFinding> exclusions = prior == null ? List.of() : prior.findings();
        ReviewPromptBuilder.Built built = ReviewPromptBuilder.build(pr, diff.files(), context, exclusions);
        Completion completion = client.provider().complete(built.prompt(), client.params())
                .toCompletableFuture().join();

        ReviewResult parsed = FindingsParser.parse(completion.text(), completion.usage());
        // Carry the "diff was clipped to fit the budget" flag so a partial review is
        // marked on the dashboard and the posted summary comment (not silent).
        ReviewResult truncatedAware = built.truncated()
                ? new ReviewResult(parsed.findings(), parsed.summary(), parsed.usage(), true)
                : parsed;
        ReviewResult result = reconcile == null
                ? truncatedAware
                : dropAnchorCollisions(truncatedAware, reconcile.verdicts());

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
     * The reconcile call (ADR-019): its own claim-guarded, paid LLM call judging each prior
     * finding against the incremental diff (or the full diff on a compare failure) plus its
     * thread transcript. Runs before the review call so a crash between the two replays cleanly.
     */
    private ReconcileOutcome reconcile(GenerateReview command, WorkerScmClients.Clients clients,
                                       Diff diff, PriorRun prior, WorkerLlmProvider.LlmClient client) {
        switch (idempotency.claim(command.reviewId(), command.commit(), RECONCILE_KEY)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                return readReconcileOutcome(already.commentId());
            }
            case CommentIdempotencyStore.Claim.Post ignored -> { }
        }
        String incremental = compareOrNull(clients.diff(), command, prior);
        String diffText = incremental != null ? incremental : DiffRenderer.render(diff.files());
        Map<String, ThreadTranscript> transcripts = fetchTranscripts(clients, command, prior);
        Prompt prompt = ReconcilePrompt.render(prior.findings(), transcripts, diffText, incremental != null);
        Completion completion = client.provider().complete(prompt, client.params())
                .toCompletableFuture().join();
        List<FindingVerdict> verdicts = downgradeUntouched(
                VerdictsParser.parse(completion.text(), prior.findings()), incremental);
        ReconcileOutcome outcome = new ReconcileOutcome(verdicts, completion.usage());
        idempotency.markPosted(command.reviewId(), command.commit(), RECONCILE_KEY, writeReconcileOutcome(outcome));
        return outcome;
    }

    /**
     * Deterministic backstop: the LLM sometimes marks a finding STILL_OPEN even though the
     * follow-up commit never touched its file. When the incremental diff is available, downgrade
     * any such verdict to UNCHANGED so the reviewer stays silent on threads the author's changes
     * couldn't possibly have affected. No incremental diff (force-push/blank fallback) means we
     * can't tell what was touched, so the LLM's verdicts stand as-is.
     */
    private static List<FindingVerdict> downgradeUntouched(List<FindingVerdict> verdicts, String incrementalDiff) {
        if (incrementalDiff == null) {
            return verdicts;
        }
        Set<String> touchedPaths = UnifiedDiffParser.parse(incrementalDiff).stream()
                .flatMap(patch -> Stream.of(patch.oldPath(), patch.newPath()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return verdicts.stream()
                .map(v -> v.status() == FindingVerdict.Status.STILL_OPEN && !touchedPaths.contains(v.path())
                        ? new FindingVerdict(v.threadRef(), v.path(), v.line(), FindingVerdict.Status.UNCHANGED, v.note())
                        : v)
                .toList();
    }

    /**
     * @return the incremental diff, or null (fall back to the full diff) when the provider can't
     * compare or returns a blank result (GitLab: no diffs between the revisions; GitHub: an empty
     * body when base==head) — a blank string is not a valid "incremental diff", it's "no diff".
     */
    private String compareOrNull(DiffSource diffSource, GenerateReview command, PriorRun prior) {
        String result;
        try {
            result = diffSource.fetchCompareDiff(command.repo(), prior.headCommit(), command.commit());
        } catch (RuntimeException e) {
            LOG.infof("compare %s..%s unavailable (%s) — reconciling on the full diff",
                    prior.headCommit(), command.commit(), e.getMessage());
            return null;
        }
        if (result == null || result.isBlank()) {
            LOG.debugf("compare %s..%s returned a blank diff — reconciling on the full diff",
                    prior.headCommit(), command.commit());
            return null;
        }
        return result;
    }

    /** Best-effort per-finding thread transcripts: a fetch failure never aborts the reconcile call. */
    private Map<String, ThreadTranscript> fetchTranscripts(WorkerScmClients.Clients clients,
                                                           GenerateReview command, PriorRun prior) {
        if (!(clients.comments() instanceof ThreadSource threadSource)) {
            return Map.of(); // transcripts are best-effort; reconcile still runs on findings + diff
        }
        Map<String, ThreadTranscript> transcripts = new HashMap<>();
        for (PriorFinding finding : prior.findings()) {
            if (finding.threadRef() == null) {
                continue;
            }
            try {
                transcripts.put(finding.threadRef(), threadSource.fetchThread(
                        command.repo(), command.prId(), new ThreadRef(finding.threadRef())));
            } catch (RuntimeException e) {
                LOG.debugf("thread %s fetch failed: %s", finding.threadRef(), e.getMessage());
            }
        }
        return transcripts;
    }

    /**
     * Drops new findings whose anchor collides with a STILL_OPEN or UNCHANGED verdict — that code
     * is already tracked (in its thread, or deliberately left untouched) so a fresh finding there
     * would be a duplicate.
     */
    private static ReviewResult dropAnchorCollisions(ReviewResult result, List<FindingVerdict> verdicts) {
        Set<String> stillPresent = verdicts.stream()
                .filter(v -> v.status() == FindingVerdict.Status.STILL_OPEN
                        || v.status() == FindingVerdict.Status.UNCHANGED)
                .map(v -> v.path() + ":" + v.line())
                .collect(Collectors.toSet());
        if (stillPresent.isEmpty()) {
            return result;
        }
        List<Finding> kept = result.findings().stream()
                .filter(f -> !stillPresent.contains(f.path() + ":" + f.range().startLine()))
                .toList();
        return new ReviewResult(kept, result.summary(), result.usage(), result.truncated());
    }

    /**
     * Redelivery of a completed LLM call: never pay twice, but always converge —
     * the original emit may not have reached the broker, so ReviewGenerated is
     * re-emitted from the result persisted at markPosted time (downstream is
     * idempotent by reviewId/commit). Legacy rows predating the persisted
     * payload were only marked after a successful emit, so skipping is safe.
     */
    private void reEmitPersistedResult(GenerateReview command, String payload, ReconcileOutcome reconcile) {
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

    /**
     * Loads the assembled context the aggregator persisted for this run. A null
     * ref (no context source configured) or a vanished blob (deleted / superseded
     * by a re-run mid-flight) both degrade to an empty list — the review still
     * runs, just without ticket context.
     */
    private List<ContextItem> loadContext(String contextRef) {
        if (contextRef == null || contextRef.isBlank()) {
            return List.of();
        }
        byte[] bytes = blobStore.get(new BlobStore.BlobRef(contextRef));
        if (bytes == null) {
            LOG.debugf("Context blob %s not found — reviewing without context", contextRef);
            return List.of();
        }
        try {
            AssembledContext assembled = mapper.readValue(bytes, AssembledContext.class);
            return assembled.items() == null ? List.of() : assembled.items();
        } catch (java.io.IOException e) {
            LOG.warnf(e, "Unreadable context blob %s — reviewing without context", contextRef);
            return List.of();
        }
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

    private String writeReconcileOutcome(ReconcileOutcome outcome) {
        try {
            return mapper.writeValueAsString(outcome);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the persisted reconcile outcome, or an empty/null one for a null or unreadable (legacy) blob. */
    private ReconcileOutcome readReconcileOutcome(String payload) {
        if (payload == null) {
            return new ReconcileOutcome(List.of(), null);
        }
        try {
            return mapper.readValue(payload, ReconcileOutcome.class);
        } catch (JsonProcessingException e) {
            return new ReconcileOutcome(List.of(), null);
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

            // Reconciliation (ADR-019): reply into (and where supported, resolve) each
            // verdict's existing thread BEFORE posting genuinely new findings. Idempotent
            // per-thread, so redelivery reconstructs outcomes from the claim map for free.
            List<CommentsPosted.ThreadOutcome> outcomes = actOnVerdicts(commentSink, command);

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
            // Recover inline comment ids from a previous partially-completed attempt. Skip the
            // non-inline claims (SUMMARY, LLM[:reconcile], reply:, resolve:) — none of those are
            // posted inline comments, so they must never leak into the inline list.
            previouslyPosted.forEach((key, id) -> {
                if (!SUMMARY_KEY.equals(key) && !LLM_KEY.equals(key) && !RECONCILE_KEY.equals(key)
                        && !key.startsWith("reply:") && !key.startsWith("resolve:")
                        && posted.stream().noneMatch(p -> p.commentId().equals(id))) {
                    posted.add(new CommentsPosted.PostedInline(id, key, 0));
                }
            });
            results.emit(new CommentsPosted(command.reviewId(), command.prId(), command.commit(),
                    summaryCommentId, posted, outcomes));
        } catch (RuntimeException e) {
            Throwable cause = unwrap(e);
            LOG.warnf(cause, "PostComments failed for %s", command.reviewId());
            results.emit(new ReviewFailed(command.reviewId(), command.commit(), "post-comments",
                    cause.getMessage(), isRetryable(cause), 1));
        }
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
                    resolvedByBot = "bot".equals(a.commentId());
                    humanResolved = "human".equals(a.commentId());
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
            LOG.warnf(unwrap(e), "resolve %s failed — continuing reply-only", thread.value());
            return CommentSink.ThreadResolution.UNSUPPORTED;
        }
    }

    private String postReply(CommentSink sink, PostComments command, ThreadRef thread, FindingVerdict verdict) {
        String replyKey = "reply:" + thread.value();
        switch (idempotency.claim(command.reviewId(), command.commit(), replyKey)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                return already.commentId();
            }
            case CommentIdempotencyStore.Claim.Post ignored -> {
                try {
                    CommentRef reply = sink.replyInThread(command.repo(), command.prId(), thread,
                            renderVerdictReply(verdict, command.commit()));
                    idempotency.markPosted(command.reviewId(), command.commit(), replyKey, reply.commentId());
                    return reply.commentId();
                } catch (RuntimeException e) {
                    LOG.warnf(unwrap(e), "reply in %s failed", thread.value());
                    return null;   // claim stays NULL -> reclaimable on redelivery
                }
            }
        }
    }

    private String renderVerdictReply(FindingVerdict verdict, String commit) {
        String sha = commit.length() > 7 ? commit.substring(0, 7) : commit;
        String note = verdict.note() == null || verdict.note().isBlank() ? "" : " " + escapeHtml(verdict.note());
        return switch (verdict.status()) {
            case RESOLVED -> "**Fixed in `" + sha + "`.**" + note;
            case STILL_OPEN -> "**Still open after `" + sha + "`:**" + note;
            case ACKNOWLEDGED -> "**Acknowledged** — closing this thread." + note;
            case SUPERSEDED -> "**The flagged code changed in `" + sha + "`** — this finding no longer applies." + note;
            case UNCHANGED -> throw new IllegalStateException("UNCHANGED verdicts never reply");
        };
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
                    String body = renderSummary(review, unanchored, failed, command.commit())
                            + renderReconciliationSection(command.verdicts());
                    CommentRef summary = updateOrPost(commentSink, command, body);
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

    /** Update the prior summary in place on a follow-up review; a vanished/edited comment falls back to a fresh post. */
    private CommentRef updateOrPost(CommentSink sink, PostComments command, String body) {
        if (command.priorSummaryRef() != null) {
            try {
                return sink.updateComment(command.repo(), command.prId(), command.priorSummaryRef(), body);
            } catch (RuntimeException e) {
                LOG.infof("summary %s update failed (%s) — posting fresh",
                        command.priorSummaryRef(), unwrap(e).getMessage());
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
