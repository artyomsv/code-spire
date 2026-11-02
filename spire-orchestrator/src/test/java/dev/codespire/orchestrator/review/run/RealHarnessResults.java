package dev.codespire.orchestrator.review.run;

import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.ResultSaga;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The real {@link HarnessReviewResults} handing its outcomes to the saga it is given, with the review in round 1. The
 * round lock is a Postgres advisory lock (ReviewRoundLockTest drives it), so here it runs the work inline; the round
 * read and the run row's model are fakes for the same reason.
 *
 * <p>Public for {@code HarnessDispatchFailureTest} in the pipeline package; the bean's fields are package-private.
 * Every fake overrides each method this path reaches (CLAUDE.md, saga test fakes).
 */
public final class RealHarnessResults {

    private RealHarnessResults() {
    }

    public static HarnessReviewResults results(ResultSaga saga) {
        HarnessReviewResults results = new HarnessReviewResults();
        results.saga = saga;
        results.runs = firstRound();
        results.rounds = inlineLock();
        results.runRows = runRows();
        results.maxReportedTokens = -1;
        return results;
    }

    private static ReviewRuns firstRound() {
        return new ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return ReviewRuns.FIRST_RUN;
            }

            @Override
            public int roundOrUnknown(String reviewId) {
                return ReviewRuns.FIRST_RUN;
            }
        };
    }

    private static ReviewRoundLock inlineLock() {
        return new ReviewRoundLock() {
            @Override
            public <T> T holding(String reviewId, Supplier<T> work) {
                return work.get();
            }
        };
    }

    private static FactoryRunProjection runRows() {
        return new FactoryRunProjection() {
            @Override
            public Optional<String> modelOf(String runId) {
                return Optional.of("TEST-model");
            }

            @Override
            public Optional<String> paidByOf(String runId) {
                return Optional.of("API_KEY");
            }

            @Override
            protected void push(String runId) {
                throw new AssertionError("unexpected live broadcast");
            }
        };
    }
}
