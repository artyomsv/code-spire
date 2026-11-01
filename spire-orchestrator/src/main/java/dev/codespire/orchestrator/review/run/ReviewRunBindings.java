package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunResult;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The run-result guard for harness review runs (harness reviews, step 4): a {@code RunReviewArtifact} whose binding is
 * not the one its row stores answers a call this deployment never made, so it is neither projected nor charged.
 */
@ApplicationScoped
public class ReviewRunBindings {

    @Inject
    FactoryRunProjection runs;

    /** Whether this result may be projected: true for every result but a review artifact naming another call. */
    public boolean acceptsBinding(RunResult result) {
        return true;
    }
}
