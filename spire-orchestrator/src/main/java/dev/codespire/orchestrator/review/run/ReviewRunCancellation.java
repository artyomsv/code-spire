package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunCommandEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Stops a review's harness run when what it reads is gone (harness reviews, step 4): a superseded head, or a closed pull
 * request. It sends {@code CancelRun} on the run-control topic for each live review-kind run, and never
 * {@code CancelReview}: ADR-013 keeps the lifecycle's own decisions with the aggregate.
 *
 * <p>A cancel that arrives before the run starts is held by the worker's cancel claim, so the run never starts; one
 * that arrives while it runs stops the unit. A finished run is not live and is sent nothing.
 */
@ApplicationScoped
public class ReviewRunCancellation {

    private static final Logger LOG = Logger.getLogger(ReviewRunCancellation.class);

    /** Twelve characters of a head, as the run id spells it. */
    private static final int COMMIT_CHARS = 12;

    @Inject
    FactoryRunProjection runs;

    @Inject
    RunCommandEmitter emitter;

    /** Cancel every live review run of {@code reviewId} that reads {@code supersededCommit}, the head now replaced. */
    public void supersede(String reviewId, String supersededCommit) {
        String head = supersededCommit.substring(0, Math.min(COMMIT_CHARS, supersededCommit.length()));
        cancel(runs.liveReviewRuns(reviewId, supersededCommit), "superseded: head " + head + " was replaced");
    }

    /** Cancel every live review run of {@code reviewId}, a reply run on a completed review included: its pull request closed. */
    public void close(String reviewId) {
        cancel(runs.liveReviewRuns(reviewId, null), "pull request closed");
    }

    private void cancel(List<String> runIds, String reason) {
        for (String runId : runIds) {
            try {
                emitter.control(new RunCommand.CancelRun(runId, reason));
            } catch (RuntimeException e) {
                // Not rethrown: the caller is the close path, and a throw would dead-letter the event that also
                // cancels the review. The run finishes and is charged, which is a cost, never a wrong review.
                LOG.errorf(e, "review run %s could not be cancelled (%s); it runs to its end", runId, reason);
            }
        }
    }
}
