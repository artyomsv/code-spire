package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Step 4's review-run results: the outcome is logged, never the artifact's text, and nothing else happens. Step 5
 * replaces this with the step that parses the artifact into findings.
 *
 * <p>Never the text: it is model output about the reviewed code and may quote it, which the log must not carry
 * (SECURITY.md). Its size and whether it was over the cap are what an operator needs here.
 */
@ApplicationScoped
public class RecordingReviewRunResults implements ReviewRunResults {

    private static final Logger LOG = Logger.getLogger(RecordingReviewRunResults.class);

    @Override
    public void accept(RunResult result, ReviewRunBinding binding) {
        switch (result) {
            case RunReviewArtifact answer -> LOG.infof("review run %s answered %s %s on %s: %s",
                    answer.runId(), binding.kind(), binding.reviewId(), binding.commit(), describe(answer));
            case RunResult.RunFailed failed -> LOG.infof("review run %s for %s %s failed: %s",
                    failed.runId(), binding.kind(), binding.reviewId(), failed.cause());
            default -> LOG.debugf("review run %s for %s: %s", result.runId(), binding.reviewId(),
                    result.getClass().getSimpleName());
        }
    }

    private static String describe(RunReviewArtifact answer) {
        return answer.overCap() ? "over the " + RunReviewArtifact.MAX_TEXT_BYTES + " byte cap, no text"
                : answer.text().length() + " characters";
    }
}
