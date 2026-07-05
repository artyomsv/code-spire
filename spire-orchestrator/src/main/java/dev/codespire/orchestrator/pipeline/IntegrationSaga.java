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
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;

import java.util.Locale;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Reacts to ingress events (cs.integration): translates them into Record
 * commands for the aggregate and, when a run starts, the first Action command.
 */
@ApplicationScoped
public class IntegrationSaga {

    private static final Logger LOG = Logger.getLogger(IntegrationSaga.class);

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    CommandsEmitter commands;

    @Inject
    ReviewPolicy policy;

    @Inject
    ReviewProjection projection;

    @Incoming("integration-in")
    @Blocking // ordered (default): per-partition = per-review sequencing (CONTRACT §9, finding H3)
    public void on(IntegrationEvent event) {
        if (event == null) {
            return; // poison record already logged by the deserializer
        }
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

        // Allowlist gate: colleagues unaware of the prototype never get touched.
        if (!policy.allows(e.author())) {
            timeline.record("integration", "PullRequestSkipped", reviewId,
                    "author not in allowlist: @" + username(e));
            LOG.infof("Skipping %s — author @%s not in allowlist", reviewId, username(e));
            return;
        }

        boolean observe = policy.observeOnly();
        // Register in the read model (header + first event) so the review is
        // visible on the dashboard whether or not any work runs.
        projection.registerHeader(reviewId, e.repo(), e.prId(), e.title(), username(e), authorId(e),
                e.sourceBranch(), e.targetBranch(), commit, e.htmlUrl(),
                observe ? "observed" : "reviewing",
                observe ? ReviewProjection.STAGE_RECEIVED : ReviewProjection.STAGE_DIFF);
        projection.appendEvent(reviewId, "integration", "PullRequestEventReceived",
                e.action().name().toLowerCase(Locale.ROOT) + " · head " + commit);

        var emitted = lifecycle.handle(reviewId,
                new RecordCommand.RequestReview(commit, e.action().name(), false));

        boolean started = emitted.stream().anyMatch(DomainEvent.ReviewRequested.class::isInstance);
        if (!started) {
            return;
        }

        // Observe-only gate: registered above, but emit no work (no diff/LLM/comments).
        if (observe) {
            timeline.record("domain", "ReviewObserved", reviewId,
                    "observe-only: registered, no review run");
            projection.appendEvent(reviewId, "domain", "ReviewObserved", "observe-only: registered, no review run");
            projection.setNote(reviewId, "Observe-only mode — registered, no review run.");
            LOG.infof("Observe-only: registered %s, emitting no commands", reviewId);
            return;
        }

        commands.emit(new ActionCommand.FetchDiff(reviewId, e.repo(), e.prId(), commit));
    }

    private static String username(PullRequestEventReceived e) {
        return e.author() == null ? "unknown" : e.author().username();
    }

    private static String authorId(PullRequestEventReceived e) {
        return e.author() == null ? "" : e.author().providerUserId();
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
