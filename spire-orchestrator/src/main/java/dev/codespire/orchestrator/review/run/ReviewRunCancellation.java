package dev.codespire.orchestrator.review.run;

import dev.codespire.orchestrator.factory.RunCommandEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;

/**
 * Stops a review's harness run when what it reads is gone (harness reviews, step 4): a superseded head, or a closed pull
 * request. It sends {@code CancelRun} on the run-control topic for each live review-kind run, and never
 * {@code CancelReview}: ADR-013 keeps the lifecycle's own decisions with the aggregate.
 */
@ApplicationScoped
public class ReviewRunCancellation {

    @Inject
    DataSource dataSource;

    @Inject
    RunCommandEmitter emitter;

    /** Cancel every live review run of {@code reviewId} that reads {@code supersededCommit}, the head now replaced. */
    public void supersede(String reviewId, String supersededCommit) {
        throw new UnsupportedOperationException("review runs are not cancelled yet: " + reviewId);
    }

    /** Cancel every live review run of {@code reviewId}: its pull request closed. */
    public void close(String reviewId) {
        throw new UnsupportedOperationException("review runs are not cancelled yet: " + reviewId);
    }
}
