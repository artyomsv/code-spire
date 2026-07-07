package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
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
    dev.codespire.orchestrator.provider.WorkerCredentials workerCredentials;

    @Inject
    dev.codespire.orchestrator.llm.WorkerLlmCredentials workerLlmCredentials;

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
                commands.emit(new ActionCommand.GatherContext(
                        e.reviewId(), ReviewIds.parse(e.reviewId()).repo(), e.prId(), e.commit(),
                        Set.of(), List.of()));
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
                emitWithCredential(e.reviewId(), "GenerateReview", scmCred -> new ActionCommand.GenerateReview(
                        e.reviewId(), ReviewIds.parse(e.reviewId()).repo(), e.prId(), e.commit(),
                        e.contextRef(), 1, null, scmCred, llmCred.get()));
            });
            case ReviewGenerated e -> ifCurrentRun(e.reviewId(), e.commit(), "ReviewGenerated", () -> {
                projection.appendEvent(e.reviewId(), "result", "ReviewGenerated",
                        e.result().findings().size() + " findings");
                // Price the token usage against the model catalog (roadmap 11) before recording.
                projection.recordOutcome(e.reviewId(), priced(e.result()), ReviewProjection.STAGE_COMMENTS);
                if (e.result().truncated()) {
                    projection.setNote(e.reviewId(), "Diff exceeded the review budget — partial review "
                            + "(changes beyond the token limit were not reviewed).");
                }
                lifecycle.handle(e.reviewId(), new RecordCommand.RecordReviewOutcome(
                        e.commit(), e.result().findings().size(),
                        Integer.toHexString(e.result().summary().hashCode())));
                emitWithCredential(e.reviewId(), "PostComments", cred -> new ActionCommand.PostComments(
                        e.reviewId(), ReviewIds.parse(e.reviewId()).repo(), e.prId(), e.commit(), e.result(), cred));
            });
            case CommentsPosted e -> {
                projection.appendEvent(e.reviewId(), "result", "CommentsPosted", e.inline().size() + " inline comments");
                projection.updateStage(e.reviewId(), ReviewProjection.STAGE_POSTING);
                lifecycle.handle(e.reviewId(), new RecordCommand.RecordCommentsPosted(
                        e.commit(), e.summaryCommentId(), e.inline().size()));
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
            java.util.Optional<String> cred = workerCredentials.packForWorkspace(parsed.repo().workspace());
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
        java.util.Optional<String> cred = workerCredentials.packForWorkspace(workspace);
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

    private String reviewIdOf(IntegrationEvent event) {
        return switch (event) {
            case DiffFetched e -> e.reviewId();
            case ContextAssembled e -> e.reviewId();
            case ReviewGenerated e -> e.reviewId();
            case CommentsPosted e -> e.reviewId();
            case ReviewFailed e -> e.reviewId();
            default -> "";
        };
    }
}
