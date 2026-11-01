package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The run-result guard for harness review runs (harness reviews, step 4): a {@code RunReviewArtifact} whose binding is
 * not the one its row stores answers a call this deployment never made, so it is neither projected nor charged.
 */
@ApplicationScoped
public class ReviewRunBindings {

    private static final Logger LOG = Logger.getLogger(ReviewRunBindings.class);

    @Inject
    FactoryRunProjection runs;

    /** Whether this result may be projected: true for every result but a review artifact naming another call. */
    public boolean acceptsBinding(RunResult result) {
        if (!(result instanceof RunReviewArtifact answer)) {
            return true;
        }
        if (runs.reviewBindingOf(answer.runId()).filter(answer.review()::equals).isPresent()) {
            return true;
        }
        LOG.warnf("review run %s: its answer names %s, which is not the call its row stores; neither projected nor "
                + "charged", answer.runId(), answer.review().reviewId());
        return false;
    }
}
