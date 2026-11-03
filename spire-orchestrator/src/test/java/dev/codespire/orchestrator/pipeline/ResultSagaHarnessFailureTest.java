package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewFailed;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.review.run.RealHarnessResults;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.COMMIT;
import static dev.codespire.orchestrator.pipeline.HarnessSagaFakes.REVIEW_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A harness review the worker could not prepare or finish (review of PR #370, code-quality/H-1). A forced re-run keeps
 * the commit, so a late failure of round 1 (a slow worker, or a replayed dead letter) arrives at round 2 on the same
 * head: it must neither fail round 2 nor schedule a retry for it. The failure carries its round and is applied by the
 * real {@code HarnessReviewResults} only when that round is current, then takes the API path's failure handling.
 *
 * <p>A plain unit test with hand-written fakes: {@link HarnessSagaFakes} and {@link HarnessSagaProjection} override
 * every method these paths reach (CLAUDE.md, saga test fakes); the review policy is a fake for the same reason.
 */
class ResultSagaHarnessFailureTest {

    private static final int CURRENT_ROUND = 2;
    private static final int ENDED_ROUND = 1;
    private static final String PREPARE = "harness-prepare";
    private static final String FINISH = "harness-finish";
    private static final String OLD_COMMIT = "fedcba9876543210fedcba9876543210fedcba98";

    private final HarnessSagaProjection projection = new HarnessSagaProjection();
    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<RecordCommand> recorded = new ArrayList<>();
    private final List<String> timelineLines = new ArrayList<>();

    @Test
    void aLatePrepareFailureOfAnEndedRoundLeavesTheCurrentRoundAlone() {
        saga().on(failed(ENDED_ROUND, PREPARE, true));

        assertCurrentRoundUntouched();
    }

    @Test
    void aLateFinishFailureOfAnEndedRoundLeavesTheCurrentRoundAlone() {
        saga().on(failed(ENDED_ROUND, FINISH, false));

        assertCurrentRoundUntouched();
    }

    /** The control for the two above: the current round's transient prepare failure is retried, as the API path's. */
    @Test
    void aRetryablePrepareFailureOfTheCurrentRoundSchedulesARetry() {
        saga().on(failed(CURRENT_ROUND, PREPARE, true));

        assertEquals(List.of(2), projection.retries, "attempt 1 of 3 schedules attempt 2");
        assertTrue(recorded.isEmpty(), "a scheduled retry leaves the review reviewing: " + recorded);
    }

    @Test
    void aFinishFailureOfTheCurrentRoundFailsTheReviewTerminally() {
        saga().on(failed(CURRENT_ROUND, FINISH, false));

        assertEquals(1, projection.terminalFailures.size(), projection.terminalFailures.toString());
        RecordCommand.RecordFailure failure = assertInstanceOf(RecordCommand.RecordFailure.class, recorded.getLast());
        assertEquals(FINISH, failure.phase());
        assertFalse(failure.retryable());
        assertTrue(projection.retries.isEmpty(), projection.retries.toString());
    }

    /** A push to a new head ended the round too; the commit guard still applies after the round check. */
    @Test
    void aFailureOfASupersededCommitIsDropped() {
        saga().on(new HarnessReviewFailed(REVIEW_ID, OLD_COMMIT, CURRENT_ROUND, FINISH, "TEST-error", false, false));

        assertCurrentRoundUntouched();
    }

    private void assertCurrentRoundUntouched() {
        assertTrue(projection.retries.isEmpty(), "no retry is scheduled for the newer round: " + projection.retries);
        assertTrue(projection.terminalFailures.isEmpty(), projection.terminalFailures.toString());
        assertEquals(0, projection.clearedRetries, "the newer round's scheduled retry is kept");
        assertTrue(projection.statuses.isEmpty(), projection.statuses.toString());
        assertTrue(recorded.isEmpty(), "nothing is recorded against the newer round: " + recorded);
        assertTrue(emitted.isEmpty(), emitted.toString());
    }

    private static HarnessReviewFailed failed(int round, String phase, boolean retryable) {
        return new HarnessReviewFailed(REVIEW_ID, COMMIT, round, phase, "TEST-error", retryable, false);
    }

    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.findings = HarnessSagaFakes.SILENT_FINDINGS;
        saga.preferenceFilter = HarnessSagaFakes.NO_PREFERENCES;
        saga.lifecycle = HarnessSagaFakes.lifecycle(recorded);
        saga.commands = HarnessSagaFakes.commands(emitted);
        saga.timeline = HarnessSagaFakes.timeline(timelineLines);
        saga.threads = HarnessSagaFakes.silentThreads();
        saga.projection = projection;
        saga.reviewPolicy = threeAttempts();
        HarnessSagaFakes.wireRepository(saga);
        HarnessSagaFakes.wireCalls(saga);
        // The real one, with a forced re-run on the same head having started round 2.
        saga.harnessResults = RealHarnessResults.inRound(saga, saga.commands, CURRENT_ROUND);
        return saga;
    }

    /** The real one reads the budget and the back-off from Settings. */
    private static ReviewPolicy threeAttempts() {
        return new ReviewPolicy() {
            @Override
            public int maxAttempts() {
                return 3;
            }

            @Override
            public Duration retryDelay(int attempt) {
                return Duration.ofSeconds(5L * attempt);
            }
        };
    }
}
