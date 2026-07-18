package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.IntegrationEvent.FollowUpGenerated;
import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reacts to worker results (cs.results): issues the next Action command and
 * the matching Record command (CONTRACT §5).
 *
 * <p>Post-split home of the ADR-013 stale-run guard: the orchestrator owns the
 * aggregate, so results belonging to a superseded/cancelled run are dropped
 * HERE, before the next (possibly expensive) command is ever emitted. The
 * worker keeps only the PR-head re-check for its own visible actions.
 */
@ApplicationScoped
public class ResultSaga {

    private static final Logger LOG = Logger.getLogger(ResultSaga.class);

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    CommandsEmitter commands;

    @Inject
    ReviewProjection projection;

    @Inject
    dev.codespire.orchestrator.readmodel.ReviewThreadView threads;

    @Inject
    dev.codespire.orchestrator.provider.WorkerCredentials workerCredentials;

    @Inject
    dev.codespire.orchestrator.llm.WorkerLlmCredentials workerLlmCredentials;

    @Inject
    dev.codespire.orchestrator.context.WorkerContextCredentials workerContextCredentials;

    @Inject
    dev.codespire.orchestrator.llm.LlmModelRegistry llmModels;

    /** Max pipeline runs before a retryable failure fails terminally (C8). Tuning knob; safe default. */
    @ConfigProperty(name = "spire.review.max-attempts", defaultValue = "3")
    int maxAttempts;

    @Incoming("results-in")
    @Blocking // ordered (default): per-partition = per-review sequencing (CONTRACT §9, finding H3)
    public void on(IntegrationEvent event) {
        if (event == null) {
            return; // poison record already logged by the deserializer
        }
        // MDC (observability rule): the handler is @Blocking-synchronous, so
        // put/remove happen on the same worker thread.
        MDC.put("reviewId", reviewIdOf(event));
        try {
            handle(event);
        } finally {
            MDC.remove("reviewId");
        }
    }

    private void handle(IntegrationEvent event) {
        timeline.record("result", event.getClass().getSimpleName(), reviewIdOf(event), "");
        switch (event) {
            // repo/prId are derived from the reviewId itself — no in-memory
            // registry, nothing lost on restart or scale-out (finding H2).
            case DiffFetched e -> ifCurrentRun(e.reviewId(), e.commit(), "DiffFetched", () -> {
                projection.appendEvent(e.reviewId(), "result", "DiffFetched", e.changedFiles() + " files");
                projection.updateStage(e.reviewId(), ReviewProjection.STAGE_CONTEXT);
                // Pack every enabled context credential (Jira + Confluence) — null when none is configured,
                // in which case the worker assembles an empty context (the review still runs).
                String workspace = ReviewIds.parse(e.reviewId()).repo().workspace();
                String contextCred = workerContextCredentials.packAll(workspace).orElse(null);
                commands.emit(new ActionCommand.GatherContext(
                        e.reviewId(), ReviewIds.parse(e.reviewId()).repo(), e.prId(), e.commit(),
                        e.ticketKeys() == null ? Set.of() : e.ticketKeys(),
                        e.links() == null ? List.of() : e.links(), contextCred));
            });
            case ContextAssembled e -> ifCurrentRun(e.reviewId(), e.commit(), "ContextAssembled", () -> {
                projection.appendEvent(e.reviewId(), "result", "ContextAssembled", "context assembled");
                projection.updateStage(e.reviewId(), ReviewProjection.STAGE_REVIEW);
                // GenerateReview also needs the LLM credential (ADR-018): resolve the
                // global-default provider and pack it encrypted; skip if none is set.
                String workspace = ReviewIds.parse(e.reviewId()).repo().workspace();
                java.util.Optional<String> llmCred = workerLlmCredentials.packDefault(workspace);
                if (llmCred.isEmpty()) {
                    timeline.record("result", "skipped:GenerateReview", e.reviewId(),
                            "no default LLM provider configured");
                    projection.setNote(e.reviewId(),
                            "No default LLM provider configured — register one in Settings → LLM.");
                    LOG.warnf("Skipping GenerateReview for %s — no default LLM provider set", e.reviewId());
                    return;
                }
                PriorRun prior = projection.priorRunFor(e.reviewId()).orElse(null);
                emitWithCredential(e.reviewId(), "GenerateReview", scmCred -> new ActionCommand.GenerateReview(
                        e.reviewId(), ReviewIds.parse(e.reviewId()).repo(), e.prId(), e.commit(),
                        e.contextRef(), 1, null, scmCred, llmCred.get(), prior));
            });
            case ReviewGenerated e -> ifCurrentRun(e.reviewId(), e.commit(), "ReviewGenerated", () -> {
                projection.appendEvent(e.reviewId(), "result", "ReviewGenerated",
                        e.result().findings().size() + " findings");
                // Price the token usage against the model catalog (roadmap 11) before recording.
                ReviewResult pricedResult = priced(e.result());
                projection.recordOutcome(e.reviewId(), pricedResult, ReviewProjection.STAGE_COMMENTS);
                projection.recordLlmCall(e.reviewId(), "review", pricedResult.usage());
                if (e.reconcileUsage() != null) {
                    projection.recordLlmCall(e.reviewId(), "reconcile", priceUsage(e.reconcileUsage()));
                }
                String priorSummaryRef = null;
                if (!e.verdicts().isEmpty()) {
                    java.util.Optional<PriorRun> prior = projection.priorRunFor(e.reviewId());
                    projection.recordReconciliation(e.reviewId(), e.verdicts(),
                            prior.map(PriorRun::findings).orElse(List.of()));
                    priorSummaryRef = prior.map(PriorRun::summaryCommentId).orElse(null);
                }
                if (e.result().truncated()) {
                    projection.setNote(e.reviewId(), "Diff exceeded the review budget — partial review "
                            + "(changes beyond the token limit were not reviewed).");
                }
                lifecycle.handle(e.reviewId(), new RecordCommand.RecordReviewOutcome(
                        e.commit(), e.result().findings().size(),
                        Integer.toHexString(e.result().summary().hashCode())));
                String finalPriorSummaryRef = priorSummaryRef;
                emitWithCredential(e.reviewId(), "PostComments", cred -> new ActionCommand.PostComments(
                        e.reviewId(), ReviewIds.parse(e.reviewId()).repo(), e.prId(), e.commit(), e.result(), cred,
                        e.verdicts(), finalPriorSummaryRef));
            });
            case CommentsPosted e -> {
                projection.appendEvent(e.reviewId(), "result", "CommentsPosted", e.inline().size() + " inline comments");
                projection.updateStage(e.reviewId(), ReviewProjection.STAGE_POSTING);
                lifecycle.handle(e.reviewId(), new RecordCommand.RecordCommentsPosted(
                        e.commit(), e.summaryCommentId(), e.inline().size()));
                // Scope A: every inline finding's comment id is a thread we own — a reply there engages the bot.
                // Record its (path, line) too so the review detail can nest that finding's conversation.
                // (The partial-retry reconstruction branch in the worker emits (anchorKey, 0); such a row simply
                //  won't match a finding loc and its thread falls to General discussion — never lost.)
                for (CommentsPosted.PostedInline inline : e.inline()) {
                    threads.markFindingThread(e.reviewId(), new ThreadRef(inline.commentId()), inline.path(), inline.line());
                }
                // Flag the summary thread so its replies classify as "general" (not a finding). is_ours unchanged.
                threads.markSummaryThread(e.reviewId(), new ThreadRef(e.summaryCommentId()));
                // ADR-019: a reconciled thread's outcome lands on the timeline and, when the finding
                // is confirmed fixed, flags the thread resolved for the detail view.
                for (var outcome : e.threadOutcomes()) {
                    if (outcome.resolved()) {
                        threads.markResolved(e.reviewId(), new ThreadRef(outcome.threadRef()));
                    }
                    projection.appendEvent(e.reviewId(), "result",
                            outcome.resolved() ? "ThreadResolved" : "ThreadReplied",
                            outcome.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                            outcome.threadRef());
                }
                // Snapshot AFTER the thread outcomes so a superseded mid-flight run never stamps a
                // posted snapshot inconsistent with the commit it actually reached the SCM at.
                projection.recordPosted(e.reviewId(), e.commit(), e.summaryCommentId());
            }
            case FollowUpGenerated e -> {
                projection.appendEvent(e.reviewId(), "result", "FollowUpGenerated",
                        Previews.of(e.answerText()), e.threadRef().value());
                // Price + record the follow-up's LLM call for the cost breakdown (roadmap 11).
                if (e.usage() != null) {
                    projection.recordLlmCall(e.reviewId(), "followup", priceUsage(e.usage()));
                }
            }
            case FollowUpPosted e -> {
                threads.bumpTurn(e.reviewId(), e.threadRef(), e.commentId());
                lifecycle.handle(e.reviewId(), new RecordCommand.RecordFollowUp(e.threadRef(), e.commentId()));
            }
            case ReviewFailed e -> onReviewFailed(e);
            default -> LOG.debugf("No result reaction for %s", event.getClass().getSimpleName());
        }
    }

    /**
     * Bounded auto-retry (C8). A retryable failure with budget left restarts the
     * pipeline from FetchDiff (same as a manual re-push, but automatic and
     * capped) — the LLM/comment idempotency stores make the re-run safe. When the
     * budget is exhausted, the provider is gone, or the failure is permanent, the
     * run fails terminally so the aggregate leaves REVIEWING instead of stalling.
     */
    private void onReviewFailed(ReviewFailed e) {
        projection.appendEvent(e.reviewId(), "result", "ReviewFailed", "failed at " + e.phase());
        ReviewIds.Parsed parsed = ReviewIds.parse(e.reviewId());
        int attempt = projection.currentAttempt(e.reviewId());

        if (e.retryable() && attempt < maxAttempts) {
            java.util.Optional<String> cred = workerCredentials.packForReview(e.reviewId());
            if (cred.isPresent()) {
                int next = attempt + 1;
                LOG.warnf("Review %s hit a retryable failure at %s — auto-retry %d/%d (error: %s)",
                        e.reviewId(), e.phase(), next, maxAttempts, e.error());
                projection.retryPipeline(e.reviewId(), next,
                        "Transient failure at " + e.phase() + " — retrying (attempt " + next + "/" + maxAttempts + ").");
                timeline.record("result", "retry:" + e.phase(), e.reviewId(),
                        "retryable failure — auto-retry " + next + "/" + maxAttempts);
                commands.emit(new ActionCommand.FetchDiff(
                        e.reviewId(), parsed.repo(), parsed.prId(), e.commit(), cred.get()));
                return;
            }
        }

        // Terminal: budget exhausted, provider gone, or a permanent failure.
        String note = terminalNote(e, attempt);
        LOG.errorf("Review %s failed terminally at %s (attempt %d/%d, retryable=%s, error: %s) — %s",
                e.reviewId(), e.phase(), attempt, maxAttempts, e.retryable(), e.error(), note);
        projection.updateStatus(e.reviewId(), "failed", phaseStage(e.phase()));
        projection.setNote(e.reviewId(), note);
        // Persist the actual provider/worker error so the UI can show why it failed (not just the log).
        projection.setError(e.reviewId(), e.error());
        // Force non-retryable so the decider yields ReviewFailedTerminally and the run leaves REVIEWING.
        lifecycle.handle(e.reviewId(), new RecordCommand.RecordFailure(e.commit(), e.phase(), false));
    }

    private String terminalNote(ReviewFailed e, int attempt) {
        if (e.retryable() && attempt >= maxAttempts) {
            return "Failed at " + e.phase() + " after " + attempt + " attempts (retry budget exhausted).";
        }
        if (e.retryable()) {
            return "Failed at " + e.phase() + " — provider unavailable, cannot retry.";
        }
        return "Failed terminally at " + e.phase() + ".";
    }

    /**
     * Resolve the workspace's provider credential (ADR-015) and emit the built
     * command. If the provider was disabled/removed mid-review the credential is
     * absent, so the command is skipped with a visible note rather than emitted
     * uncredentialed.
     */
    private void emitWithCredential(String reviewId, String phase,
                                    java.util.function.Function<String, ActionCommand> build) {
        String workspace = ReviewIds.parse(reviewId).repo().workspace();
        java.util.Optional<String> cred = workerCredentials.packForReview(reviewId);
        if (cred.isEmpty()) {
            timeline.record("result", "skipped:" + phase, reviewId,
                    "provider unavailable for workspace " + workspace + " — cannot issue " + phase);
            projection.setNote(reviewId,
                    "Provider for " + workspace + " is disabled or removed — " + phase + " not issued.");
            LOG.warnf("Skipping %s for %s — no enabled provider for workspace %s", phase, reviewId, workspace);
            return;
        }
        commands.emit(build.apply(cred.get()));
    }

    /** ADR-013: a superseded/cancelled run's results never trigger the next command. */
    private void ifCurrentRun(String reviewId, String commit, String phase, Runnable action) {
        ReviewState state = lifecycle.currentState(reviewId);
        if (state.isReviewing() && Objects.equals(commit, state.currentCommit())) {
            action.run();
        } else {
            timeline.record("result", "dropped:" + phase, reviewId, "stale run — superseded or cancelled");
        }
    }

    /** Map a worker failure phase to the pipeline step it stalled on. */
    private static int phaseStage(String phase) {
        String p = phase == null ? "" : phase.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("diff")) {
            return ReviewProjection.STAGE_DIFF;
        }
        if (p.contains("context")) {
            return ReviewProjection.STAGE_CONTEXT;
        }
        if (p.contains("comment") || p.contains("post")) {
            return ReviewProjection.STAGE_COMMENTS;
        }
        return ReviewProjection.STAGE_REVIEW; // generate / llm / unknown
    }

    /** Fill in the review cost by pricing the token usage against the model catalog (roadmap 11). */
    private ReviewResult priced(ReviewResult result) {
        ModelUsage u = result.usage();
        if (u == null || u.model() == null) {
            return result;
        }
        long cost = llmModels.costMillicents(u.model(), u.tokensIn(), u.tokensOut());
        if (cost == u.costMillicents()) {
            return result;
        }
        // Preserve the truncated flag when re-pricing (4-arg constructor).
        return new ReviewResult(result.findings(), result.summary(),
                new ModelUsage(u.model(), u.tokensIn(), u.tokensOut(), cost), result.truncated());
    }

    /** Price a follow-up call's token usage against the model catalog (mirrors {@link #priced(ReviewResult)}). */
    private ModelUsage priceUsage(ModelUsage u) {
        if (u == null || u.model() == null) {
            return u;
        }
        return new ModelUsage(u.model(), u.tokensIn(), u.tokensOut(),
                llmModels.costMillicents(u.model(), u.tokensIn(), u.tokensOut()));
    }

    private String reviewIdOf(IntegrationEvent event) {
        return switch (event) {
            case DiffFetched e -> e.reviewId();
            case ContextAssembled e -> e.reviewId();
            case ReviewGenerated e -> e.reviewId();
            case CommentsPosted e -> e.reviewId();
            case FollowUpGenerated e -> e.reviewId();
            case FollowUpPosted e -> e.reviewId();
            case ReviewFailed e -> e.reviewId();
            default -> "";
        };
    }
}
