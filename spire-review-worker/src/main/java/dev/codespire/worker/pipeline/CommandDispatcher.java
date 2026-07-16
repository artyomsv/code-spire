package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.AnswerFollowUp;
import dev.codespire.contract.command.ActionCommand.FetchDiff;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.command.ActionCommand.PostComments;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Routes cs.commands to workers. The aggregate-based stale-run guard lives in
 * the ORCHESTRATOR's sagas after the split (it owns the event store); the
 * worker keeps the PR-head re-check inside ReviewWorker for the expensive and
 * externally-visible steps (ADR-013).
 */
@ApplicationScoped
public class CommandDispatcher {

    private static final Logger LOG = Logger.getLogger(CommandDispatcher.class);

    @Inject
    DiffWorker diffWorker;

    @Inject
    ContextWorker contextWorker;

    @Inject
    ReviewWorker reviewWorker;

    @Inject
    FollowUpWorker followUpWorker;

    @Incoming("commands-in")
    // ordered (default): redelivered/interleaved same-review commands must not
    // race each other (finding H3). Cross-PR concurrency comes from topic
    // partitions (scale `partitions` on cs.commands), not from unordered
    // dispatch — a 60s LLM call head-of-line-blocks only its own partition.
    @Blocking
    public void on(ActionCommand command) {
        if (command == null) {
            return; // poison record already logged by the deserializer
        }
        // MDC (observability rule): the handler is @Blocking-synchronous, so
        // put/remove happen on the same worker thread.
        MDC.put("reviewId", command.reviewId());
        try {
            switch (command) {
                case FetchDiff c -> diffWorker.fetchDiff(c);
                case GatherContext c -> contextWorker.gatherContext(c);
                case GenerateReview c -> reviewWorker.generateReview(c);
                case PostComments c -> reviewWorker.postComments(c);
                case AnswerFollowUp c -> followUpWorker.answer(c);
                default -> LOG.debugf("No worker for %s", command.getClass().getSimpleName());
            }
        } finally {
            MDC.remove("reviewId");
        }
    }
}
