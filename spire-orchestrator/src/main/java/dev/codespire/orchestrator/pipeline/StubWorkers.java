package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.ContextContributed;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.annotations.Merge;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 0 STUB workers behind the "commands" channel: they exercise the
 * pipeline shape (stale-run pre-check, result events) without any real SCM or
 * LLM. Every output is self-labeling synthetic ("STUB ..."). Replaced by the
 * real DiffSource / ContextProviders / LlmProvider / CommentSink in Phase 1-2.
 */
@ApplicationScoped
public class StubWorkers {

    private static final Logger LOG = Logger.getLogger(StubWorkers.class);

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    @Channel("results")
    Emitter<IntegrationEvent> results;

    @Inject
    PrRegistry registry;

    @Incoming("commands")
    @Merge // several sagas emit to "commands" — merge their upstreams
    @Blocking
    public void on(ActionCommand command) {
        // Stale-run pre-check (ADR-013): abandon BEFORE the expensive/visible
        // action, so a superseded run never spends tokens or posts comments.
        if (isStale(command)) {
            timeline.record("command", "abandoned:" + command.getClass().getSimpleName(),
                    command.reviewId(), "stale run — superseded or cancelled");
            return;
        }
        switch (command) {
            case FetchDiff c -> {
                registry.remember(c.reviewId(), c.repo());
                results.send(new DiffFetched(c.reviewId(), c.prId(), c.commit(),
                        2, List.of("java"), 1_204, false));
            }
            case GatherContext c -> {
                results.send(new IntegrationEvent.ContextRequested(new dev.codespire.contract.review.ContextRequest(
                        c.reviewId(), c.repo(), c.prId(), c.commit(), c.ticketKeys(), c.links(), Set.of("RULES"))));
                results.send(new ContextContributed(c.reviewId(), new ContextContribution(
                        "RULES", ContribStatus.OK,
                        List.of(new ContextItem("RULE", "STUB rule", "STUB: prefer descriptive names", null)), 3)));
                results.send(new ContextAssembled(c.reviewId(), c.prId(), c.commit(),
                        null, Set.of("RULES"), Set.of()));
            }
            case GenerateReview c -> results.send(new ReviewGenerated(c.reviewId(), c.prId(), c.commit(),
                    new ReviewResult(
                            List.of(new Finding("src/Demo.java", new LineRange(1, 1), Severity.INFO,
                                    "STUB finding: this is Phase 0 scaffolding output, not a real review.", null)),
                            "STUB summary: pipeline works end-to-end.",
                            new ModelUsage("stub-model", 0, 0, 0))));
            case PostComments c -> {
                LOG.infof("STUB CommentSink: would post %d finding(s) + summary to %s#%d",
                        c.findings().findings().size(), c.repo().full(), c.prId());
                results.send(new CommentsPosted(c.reviewId(), c.prId(), c.commit(), "STUB-comment-1",
                        List.of(new CommentsPosted.PostedInline("STUB-comment-2", "src/Demo.java", 1))));
            }
            default -> LOG.debugf("No stub worker for %s", command.getClass().getSimpleName());
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

    /**
     * P0-only convenience: result events don't carry RepoRef, so sagas look the
     * repo up here. Goes away at P1 when workers own their command payloads.
     */
    @ApplicationScoped
    public static class PrRegistry {

        private final Map<String, RepoRef> byReviewId = new ConcurrentHashMap<>();

        public void remember(String reviewId, RepoRef repo) {
            byReviewId.put(reviewId, repo);
        }

        public RepoRef repo(String reviewId) {
            return byReviewId.getOrDefault(reviewId, new RepoRef("unknown", "unknown"));
        }
    }
}
