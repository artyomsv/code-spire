package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.llm.DefaultLlm;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.review.run.HarnessReviewStart;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import dev.codespire.orchestrator.settings.ReviewDiffLimit;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR 456, code-quality/M-4: the Review diff limit cannot be read while a review's LLM credential is packed, on the
 * ContextAssembled-to-dispatch path. No GenerateReview is emitted, and the review takes the saga's own bounded retry
 * (a {@code retry_at} the sweeper dispatches), not the dead-letter queue; past the budget it fails terminally, so it
 * never stays REVIEWING. The same ContextAssembled delivered again before the retry runs schedules nothing more: the
 * schedule is a compare-and-set on the persisted attempt (the projection fake keeps that state, as the row does).
 * Field-injected fakes, as {@link ResultSagaRetryTest}: no container, no database.
 */
class ResultSagaLimitReadTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, 7L);
    private static final String COMMIT = "TESTSHA00000";

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<RecordCommand> recorded = new ArrayList<>();
    private final RecordingProjection projection = new RecordingProjection();

    @Test
    void aFailedLimitReadSchedulesARetryAndDispatchesNothing() {
        ResultSaga saga = sagaWith(3);

        saga.on(contextAssembled());

        assertTrue(emitted.isEmpty(), "no GenerateReview without the operator's cap: " + emitted);
        assertEquals(List.of(2), projection.retryAttempts, "the next attempt is scheduled durably");
        assertTrue(projection.terminal.isEmpty(), "a transient read is not a terminal failure");
        assertTrue(recorded.isEmpty(), "the review stays in its run for the retry");
    }

    @Test
    void aDuplicateDeliveryBeforeTheRetryRunsSchedulesNothingMore() {
        ResultSaga saga = sagaWith(3);

        saga.on(contextAssembled());
        saga.on(contextAssembled());

        assertEquals(List.of(2), projection.retryAttempts, "one retry for one failed run");
        assertEquals(2, projection.attempt, "the duplicate leaves the attempt where the first delivery put it");
        assertTrue(emitted.isEmpty(), "no GenerateReview: " + emitted);
        assertTrue(projection.terminal.isEmpty(), "a duplicate cannot spend the budget into a terminal failure");
        assertTrue(recorded.isEmpty());
    }

    @Test
    void aDuplicateOfTheLastAllowedAttemptDoesNotEndTheReview() {
        ResultSaga saga = sagaWith(2);

        saga.on(contextAssembled());
        saga.on(contextAssembled());

        assertEquals(List.of(2), projection.retryAttempts);
        assertTrue(projection.terminal.isEmpty(), "the scheduled last attempt still runs");
        assertTrue(recorded.isEmpty());
    }

    @Test
    void theRetriedRunFailingAgainAdvancesFromThePersistedAttempt() {
        ResultSaga saga = sagaWith(3);

        saga.on(contextAssembled());
        projection.retryPending = false; // the sweeper claimed the due row and the retried run reached here
        saga.on(contextAssembled());

        assertEquals(List.of(2, 3), projection.retryAttempts);
        assertTrue(emitted.isEmpty(), "no GenerateReview: " + emitted);
    }

    @Test
    void aFailedLimitReadPastTheBudgetFailsTheReviewTerminally() {
        ResultSaga saga = sagaWith(1);

        saga.on(contextAssembled());

        assertTrue(emitted.isEmpty(), "no GenerateReview without the operator's cap: " + emitted);
        assertTrue(projection.retryAttempts.isEmpty(), "no retry is left");
        assertEquals(List.of("failed"), projection.terminal);
        RecordCommand.RecordFailure failure = (RecordCommand.RecordFailure) recorded.getFirst();
        assertFalse(failure.retryable(), "the review leaves REVIEWING");
    }

    private static ContextAssembled contextAssembled() {
        return new ContextAssembled(REVIEW_ID, 7L, COMMIT, "TEST-context-ref", Set.of(), Set.of());
    }

    private ResultSaga sagaWith(int maxAttempts) {
        ResultSaga saga = new ResultSaga();
        saga.reviewPolicy = policy(maxAttempts);
        saga.lifecycle = reviewingLifecycle();
        saga.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.projection = projection;
        saga.harnessStart = apiReviews();
        saga.workerLlmCredentials = unreadableLimit();
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("TEST-scm-cred");
            }
        };
        saga.limitRetry = limitRetry(saga);
        return saga;
    }

    private LimitReadRetry limitRetry(ResultSaga saga) {
        LimitReadRetry retry = new LimitReadRetry();
        retry.projection = projection;
        retry.reviewPolicy = saga.reviewPolicy;
        retry.timeline = saga.timeline;
        retry.workerCredentials = saga.workerCredentials;
        return retry;
    }

    private static dev.codespire.orchestrator.policy.ReviewPolicy policy(int maxAttempts) {
        return new dev.codespire.orchestrator.policy.ReviewPolicy() {
            @Override
            public int maxAttempts() {
                return maxAttempts;
            }

            @Override
            public Duration retryDelay(int attempt) {
                return Duration.ofSeconds(5L * attempt);
            }
        };
    }

    private ReviewLifecycleService reviewingLifecycle() {
        return new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                recorded.add(command);
                return List.of();
            }

            @Override
            public ReviewState currentState(String reviewId) {
                return new ReviewState(reviewId, null, 1L, ReviewState.Status.REVIEWING,
                        COMMIT, Set.of(), null, java.util.Map.of(), Set.of());
            }
        };
    }

    private static HarnessReviewStart apiReviews() {
        return new HarnessReviewStart() {
            @Override
            public Decision start(ContextAssembled event) {
                return new NotHarness();
            }
        };
    }

    /** The real pack throws this when {@link ReviewDiffLimit#current} cannot read its row. */
    private static WorkerLlmCredentials unreadableLimit() {
        return new WorkerLlmCredentials() {
            @Override
            public DefaultLlm resolveForReview(String reviewId) {
                throw new AppSettingRepository.ReadFailed(ReviewDiffLimit.KEY, new SQLException("TEST-unreachable"));
            }
        };
    }

    /**
     * Every method the path reaches is overridden: an un-overridden one opens a real database. The attempt and the
     * pending retry are kept as the row keeps them, so a second delivery meets the first one's write.
     */
    private static final class RecordingProjection extends ReviewProjection {
        private final List<Integer> retryAttempts = new ArrayList<>();
        private final List<String> terminal = new ArrayList<>();
        private int attempt = 1;
        private boolean retryPending;

        @Override
        public void appendEvent(String reviewId, String lane, String type, String detail) {
        }

        @Override
        public void updateStage(String reviewId, int stage) {
        }

        @Override
        public void setHarnessRefusal(String reviewId, String reason) {
        }

        @Override
        public int currentAttempt(String reviewId) {
            return attempt;
        }

        @Override
        public boolean retryPending(String reviewId) {
            return retryPending;
        }

        @Override
        public boolean scheduleRetryFrom(String reviewId, int fromAttempt, RetryPlan plan) {
            if (attempt != fromAttempt || retryPending) {
                return false;
            }
            attempt = plan.attempt();
            retryPending = true;
            retryAttempts.add(plan.attempt());
            return true;
        }

        @Override
        public void scheduleRetry(String reviewId, int attempt, String note, Instant dueAt) {
            retryAttempts.add(attempt);
        }

        @Override
        public void clearScheduledRetry(String reviewId) {
            retryPending = false;
        }

        @Override
        public void projectTerminalFailure(String reviewId, int stage, String note, String error) {
            terminal.add("failed");
        }
    }
}
