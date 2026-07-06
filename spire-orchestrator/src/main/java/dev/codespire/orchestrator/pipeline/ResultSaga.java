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
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

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

    @Incoming("results-in")
    @Blocking // ordered (default): per-partition = per-review sequencing (CONTRACT §9, finding H3)
    public void on(IntegrationEvent event) {
        if (event == null) {
            return; // poison record already logged by the deserializer
        }
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
                emitWithCredential(e.reviewId(), "GenerateReview", cred -> new ActionCommand.GenerateReview(
                        e.reviewId(), ReviewIds.parse(e.reviewId()).repo(), e.prId(), e.commit(),
                        e.contextRef(), 1, null, cred));
            });
            case ReviewGenerated e -> ifCurrentRun(e.reviewId(), e.commit(), "ReviewGenerated", () -> {
                projection.appendEvent(e.reviewId(), "result", "ReviewGenerated",
                        e.result().findings().size() + " findings");
                projection.recordOutcome(e.reviewId(), e.result(), ReviewProjection.STAGE_COMMENTS);
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
            case ReviewFailed e -> {
                projection.appendEvent(e.reviewId(), "result", "ReviewFailed", "stalled: " + e.phase());
                projection.updateStatus(e.reviewId(), "failed", phaseStage(e.phase()));
                projection.setNote(e.reviewId(), e.retryable()
                        ? "Stalled at " + e.phase() + " — retryable, but no retry budget yet; re-push or /review to restart."
                        : "Failed terminally at " + e.phase() + ".");
                lifecycle.handle(e.reviewId(), new RecordCommand.RecordFailure(
                        e.commit(), e.phase(), e.retryable()));
                if (e.retryable()) {
                    // No retry budget yet (ADR-013 TODO): surface the stall on
                    // the dashboard instead of letting the run vanish (M3).
                    timeline.record("result", "stalled:" + e.phase(), e.reviewId(),
                            "retryable failure, retry budget not implemented yet — re-push or /review to restart");
                }
            }
            default -> LOG.debugf("No result reaction for %s", event.getClass().getSimpleName());
        }
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
