package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ReviewRunBinding;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Step 4's review-run results: the outcome is logged, never the artifact's text, and nothing else happens. Step 5
 * replaces this with the step that parses the artifact into findings.
 */
@ApplicationScoped
public class RecordingReviewRunResults implements ReviewRunResults {

    @Override
    public void accept(RunResult result, ReviewRunBinding binding) {
        throw new UnsupportedOperationException("review run results are not recorded yet: " + result.runId());
    }
}
