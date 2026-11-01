package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Whether a review's first round runs on a harness, and if so dispatches its review run (harness reviews, step 5).
 *
 * <p>Decides in this order: no repository or no review harness is {@link NotHarness}; a re-review (a prior round was
 * posted) is {@link NotHarness}, because re-reviews stay on the API until step 6; a fork pull request, or one whose
 * fork status is unknown, is {@link Refused}; otherwise the run is dispatched, and a refused dispatch is
 * {@link Refused} with its reason.
 *
 * <p>Not built yet: the red phase of step 5 declares the decision and its collaborators so the tests compile.
 */
@ApplicationScoped
public class HarnessReviewStart {

    @Inject
    RepositoryRegistry repositories;

    @Inject
    ReviewProjection projection;

    @Inject
    FixTargets targets;

    @Inject
    ReviewRunDispatch dispatch;

    @Inject
    ReviewRuns runs;

    @Inject
    WorkerPromptTemplates promptTemplates;

    /** What became of a review's start. */
    public sealed interface Decision permits NotHarness, Started, Refused {
    }

    /** The review takes today's API path. */
    public record NotHarness() implements Decision {
    }

    /** A review run was dispatched (or is already queued, or its launch is uncertain). */
    public record Started(String runId, String harness) implements Decision {
    }

    /** The review is refused on its harness, never sent to the API instead. */
    public record Refused(String harness, String reason) implements Decision {
    }

    public Decision start(IntegrationEvent.ContextAssembled event) {
        throw new UnsupportedOperationException("not built yet");
    }
}
