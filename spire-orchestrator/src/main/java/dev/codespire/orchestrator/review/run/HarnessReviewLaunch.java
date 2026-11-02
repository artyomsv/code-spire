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
 * Dispatches a prepared harness review's run (harness reviews, step 6): the worker has written the run's inputs, and
 * the launch checks that the review's round is still the one prepared, composes the prompt and dispatches the run with
 * those inputs. A refusal never falls back to the API (operator decision 6).
 */
@ApplicationScoped
public class HarnessReviewLaunch {

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

    public HarnessReviewStart.Decision launch(IntegrationEvent.HarnessReviewPrepared prepared) {
        throw new UnsupportedOperationException("not built yet");
    }
}
