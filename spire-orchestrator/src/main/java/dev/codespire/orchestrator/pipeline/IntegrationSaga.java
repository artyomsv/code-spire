package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.event.ReviewIds;
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
 * Reacts to ingress events (the "integration" channel = cs.integration topic
 * at P1): translates them into Record commands for the aggregate and, when a
 * run starts, the first Action command of the pipeline.
 */
@ApplicationScoped
public class IntegrationSaga {

    private static final Logger LOG = Logger.getLogger(IntegrationSaga.class);

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    @Channel("commands")
    Emitter<ActionCommand> commands;

    @Incoming("integration")
    @Blocking
    public void on(IntegrationEvent event) {
        timeline.record("integration", event.getClass().getSimpleName(), reviewIdOf(event), "");
        switch (event) {
            case PullRequestEventReceived e -> onPullRequestEvent(e);
            case PullRequestClosed e -> lifecycle.handle(ReviewIds.reviewId(e.repo(), e.prId()),
                    new RecordCommand.CancelReview(e.reason().name()));
            case ManualCommandReceived e -> LOG.infof("Manual /%s command received — handled in P2", e.command());
            default -> LOG.debugf("No integration reaction for %s", event.getClass().getSimpleName());
        }
    }

    private void onPullRequestEvent(PullRequestEventReceived e) {
        String reviewId = ReviewIds.reviewId(e.repo(), e.prId());
        String commit = e.diffRefs().headSha();

        var emitted = lifecycle.handle(reviewId,
                new RecordCommand.RequestReview(commit, e.action().name(), false));

        boolean started = emitted.stream().anyMatch(DomainEvent.ReviewRequested.class::isInstance);
        if (started) {
            commands.send(new ActionCommand.FetchDiff(reviewId, e.repo(), e.prId(), commit));
            timeline.record("command", "FetchDiff", reviewId, "commit " + commit);
        }
    }

    private String reviewIdOf(IntegrationEvent event) {
        return switch (event) {
            case PullRequestEventReceived e -> ReviewIds.reviewId(e.repo(), e.prId());
            case PullRequestClosed e -> ReviewIds.reviewId(e.repo(), e.prId());
            case ManualCommandReceived e -> ReviewIds.reviewId(e.repo(), e.prId());
            default -> "";
        };
    }
}
