package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.annotations.Merge;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.Objects;

/**
 * Routes Action commands to workers (the "commands" channel = cs.commands at
 * P1). Applies the ADR-013 stale-run pre-check BEFORE any expensive or
 * externally-visible action: a superseded/cancelled run never spends LLM
 * tokens and never posts a comment.
 */
@ApplicationScoped
public class CommandDispatcher {

    private static final Logger LOG = Logger.getLogger(CommandDispatcher.class);

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    DiffWorker diffWorker;

    @Inject
    ContextWorker contextWorker;

    @Inject
    ReviewWorker reviewWorker;

    @Incoming("commands")
    @Merge // several sagas emit to "commands" — merge their upstreams
    // ordered=false: one PR's 60s LLM call must not head-of-line-block other
    // PRs (review finding M2). Per-review sequencing is preserved because each
    // next command is only emitted after the previous result event.
    @Blocking(ordered = false)
    public void on(ActionCommand command) {
        if (isStale(command)) {
            timeline.record("command", "abandoned:" + command.getClass().getSimpleName(),
                    command.reviewId(), "stale run — superseded or cancelled");
            return;
        }
        switch (command) {
            case FetchDiff c -> diffWorker.fetchDiff(c);
            case GatherContext c -> contextWorker.gatherContext(c);
            case GenerateReview c -> reviewWorker.generateReview(c);
            case PostComments c -> reviewWorker.postComments(c);
            default -> LOG.debugf("No worker for %s", command.getClass().getSimpleName());
        }
    }

    private boolean isStale(ActionCommand command) {
        String commit = switch (command) {
            case FetchDiff c -> c.commit();
            case GatherContext c -> c.commit();
            case GenerateReview c -> c.commit();
            case PostComments c -> c.commit();
            default -> null;
        };
        if (commit == null) {
            return false;
        }
        var state = lifecycle.currentState(command.reviewId());
        return !(state.isReviewing() && Objects.equals(commit, state.currentCommit()));
    }
}
