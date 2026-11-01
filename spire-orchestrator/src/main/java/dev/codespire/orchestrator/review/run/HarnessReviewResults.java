package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.ResultSaga;

/**
 * A harness review run's answer becomes the review's outcome (harness reviews, step 5): the artifact is parsed into
 * findings and handed to {@link ResultSaga#acceptHarnessOutcome}, a failed run fails the review, and a result of an
 * ended round is dropped.
 *
 * <p>Not built yet: the red phase of step 5 declares the class and its collaborators so the tests compile. It is not a
 * bean until it is built, because {@link RecordingReviewRunResults} still answers the port and two beans would be
 * ambiguous; building it makes this the bean and deletes that one.
 */
public class HarnessReviewResults implements ReviewRunResults {

    ResultSaga saga;

    ReviewRuns runs;

    FactoryRunProjection runRows;

    long maxReportedTokens = -1;

    @Override
    public void accept(RunResult result, ReviewRunBinding binding) {
        throw new UnsupportedOperationException("not built yet");
    }
}
