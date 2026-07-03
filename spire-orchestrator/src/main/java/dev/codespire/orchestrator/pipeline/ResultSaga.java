package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Reacts to worker results (the "results" channel = cs.results topic at P1):
 * issues the next Action command and the matching Record command (CONTRACT §5).
 */
@ApplicationScoped
public class ResultSaga {

    private static final Logger LOG = Logger.getLogger(ResultSaga.class);

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    @Channel("commands")
    Emitter<ActionCommand> commands;

    @Inject
    PrRegistry registry;

    @Incoming("results")
    @Blocking
    public void on(IntegrationEvent event) {
        timeline.record("result", event.getClass().getSimpleName(), reviewIdOf(event), "");
        switch (event) {
            case DiffFetched e -> send(new ActionCommand.GatherContext(
                    e.reviewId(), registry.repo(e.reviewId()), e.prId(), e.commit(),
                    java.util.Set.of(), java.util.List.of()));
            case ContextAssembled e -> send(new ActionCommand.GenerateReview(
                    e.reviewId(), registry.repo(e.reviewId()), e.prId(), e.commit(), e.contextRef(), 1, null));
            case ReviewGenerated e -> {
                lifecycle.handle(e.reviewId(), new RecordCommand.RecordReviewOutcome(
                        e.commit(), e.result().findings().size(),
                        Integer.toHexString(e.result().summary().hashCode())));
                send(new ActionCommand.PostComments(
                        e.reviewId(), registry.repo(e.reviewId()), e.prId(), e.commit(), e.result()));
            }
            case CommentsPosted e -> lifecycle.handle(e.reviewId(), new RecordCommand.RecordCommentsPosted(
                    e.commit(), e.summaryCommentId(), e.inline().size()));
            case ReviewFailed e -> lifecycle.handle(e.reviewId(), new RecordCommand.RecordFailure(
                    e.commit(), e.phase(), e.retryable()));
            default -> LOG.debugf("No result reaction for %s", event.getClass().getSimpleName());
        }
    }

    private void send(ActionCommand command) {
        commands.send(command);
        timeline.record("command", command.getClass().getSimpleName(), command.reviewId(), "");
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
