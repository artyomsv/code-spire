package dev.codespire.orchestrator.review.run;

import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.pipeline.ResultSaga;
import dev.codespire.orchestrator.provider.WorkerCredentials;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The real {@link HarnessReviewResults} handing its outcomes to the saga it is given, in round 1 or a given round. The
 * round lock is a Postgres advisory lock (ReviewRoundLockTest drives it), so here it runs the work inline; the round
 * read and the run row's model are fakes for the same reason.
 *
 * <p>Public for {@code HarnessDispatchFailureTest} in the pipeline package; the bean's fields are package-private.
 * Every fake overrides each method this path reaches (CLAUDE.md, saga test fakes).
 */
public final class RealHarnessResults {

    private RealHarnessResults() {
    }

    /**
     * @param saga     the saga the finished outcome is handed to
     * @param commands where a run's answer is sent to the worker to finish (step 6)
     */
    public static HarnessReviewResults results(ResultSaga saga, CommandsEmitter commands) {
        return inRound(saga, commands, ReviewRuns.FIRST_RUN);
    }

    /**
     * @param saga         the saga the finished outcome is handed to
     * @param commands     where a run's answer is sent to the worker to finish (step 6)
     * @param currentRound the review's current round, as a forced re-run on the same head leaves it
     */
    public static HarnessReviewResults inRound(ResultSaga saga, CommandsEmitter commands, int currentRound) {
        HarnessReviewResults results = new HarnessReviewResults();
        results.saga = saga;
        results.commands = commands;
        results.credentials = reviewerCredential();
        results.runs = round(currentRound);
        results.rounds = inlineLock();
        results.runRows = runRows();
        results.maxReportedTokens = -1;
        return results;
    }

    private static ReviewRuns round(int currentRound) {
        return new ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return currentRound;
            }

            @Override
            public int roundOrUnknown(String reviewId) {
                return currentRound;
            }
        };
    }

    /** The real one resolves the reviewer account from the database and packs it with Tink. */
    private static WorkerCredentials reviewerCredential() {
        return new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("TEST-packed-scm");
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
