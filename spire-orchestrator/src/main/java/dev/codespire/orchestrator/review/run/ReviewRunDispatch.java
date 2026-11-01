package dev.codespire.orchestrator.review.run;

import dev.codespire.orchestrator.factory.FactoryConfig;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.factory.RunCredentials;
import dev.codespire.orchestrator.factory.RunFunding;
import dev.codespire.orchestrator.factory.RunLaunch;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Funds, records and sends one harness review run (harness reviews, step 4). It funds through {@link RunFunding}, writes
 * the queued review row and launches an {@code ExecuteReviewRun} on the review lane. No caller in this step: step 5
 * dispatches the first reviews.
 */
@ApplicationScoped
public class ReviewRunDispatch {

    @Inject
    RunFunding funding;

    @Inject
    FactoryRunProjection runs;

    @Inject
    RunLaunch launch;

    @Inject
    RepositoryAccounts accounts;

    @Inject
    FixTargets targets;

    @Inject
    RunCredentials credentials;

    @Inject
    FactoryConfig config;

    /** What became of one dispatch. */
    public sealed interface Outcome permits Dispatched, AlreadyQueued, Uncertain, Refused {
    }

    /** The broker acknowledged the command. */
    public record Dispatched(String runId) implements Outcome {
    }

    /** The same review call's run is already queued: its deterministic id matched a row, so nothing was sent. */
    public record AlreadyQueued(String runId) implements Outcome {
    }

    /** Whether the command reached the broker is unknown, so nothing is retried until somebody knows. */
    public record Uncertain(String runId) implements Outcome {
    }

    /** Refused before a row was written or a token moved, with the reason funding gave. */
    public record Refused(String reason) implements Outcome {
    }

    public Outcome dispatch(ReviewRunRequest request) {
        throw new UnsupportedOperationException("review runs are not dispatched yet: " + request.binding().reviewId());
    }
}
