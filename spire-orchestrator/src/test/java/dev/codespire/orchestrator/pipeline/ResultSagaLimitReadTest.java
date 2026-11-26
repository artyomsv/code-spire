package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.llm.DefaultLlm;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewLimitDeferrals;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.review.run.HarnessReviewStart;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import dev.codespire.orchestrator.settings.ReviewDiffLimit;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR 456, code-quality/M-4: the Review diff limit cannot be read while a review's LLM credential is packed, on the
 * ContextAssembled-to-dispatch path. Nothing is emitted and the review is deferred, not failed and not counted as a
 * retry attempt; the deferral store answers {@code EXPIRED} once the setting has been unreadable past its window, and
 * only then does the review fail. A read that succeeds closes the window and dispatches GenerateReview. The store's
 * own SQL is proven in {@code ReviewLimitDeferralsTest}; here it is a fake that answers what the test sets.
 */
class ResultSagaLimitReadTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, 7L);
    private static final String COMMIT = "TESTSHA00000";

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<RecordCommand> recorded = new ArrayList<>();
    private final RecordingProjection projection = new RecordingProjection();
    private final FakeDeferrals deferrals = new FakeDeferrals();

    @Test
    void aFailedLimitReadDefersTheReviewAndDispatchesNothing() {
        ResultSaga saga = sagaWith(unreadableLimit());

        saga.on(contextAssembled());

        assertTrue(emitted.isEmpty(), "no GenerateReview without the operator's cap: " + emitted);
        assertEquals(List.of(REVIEW_ID + "@" + COMMIT), deferrals.deferred, "the review waits for the sweeper");
        assertEquals(0, projection.scheduledRetries, "a deferral spends no retry attempt");
        assertTrue(projection.terminal.isEmpty(), "a transient read is not a failure");
        assertTrue(recorded.isEmpty(), "the review stays in its run");
    }

    @Test
    void aDuplicateFailedDeliveryOnlyDefersAgain() {
        ResultSaga saga = sagaWith(unreadableLimit());

        saga.on(contextAssembled());
        saga.on(contextAssembled());

        assertEquals(2, deferrals.deferred.size(), "each delivery asks the store, which keeps the earlier due time");
        assertEquals(0, projection.scheduledRetries, "no attempt is spent by either delivery");
        assertTrue(projection.terminal.isEmpty(), "a duplicate cannot end the review");
        assertTrue(recorded.isEmpty());
        assertTrue(emitted.isEmpty(), "no GenerateReview: " + emitted);
    }

    @Test
    void aFailedReadPastTheWindowFailsTheReviewAndNamesTheSetting() {
        ResultSaga saga = sagaWith(unreadableLimit());
        deferrals.outcome = ReviewLimitDeferrals.Outcome.EXPIRED;

        saga.on(contextAssembled());

        assertTrue(emitted.isEmpty(), "no GenerateReview without the operator's cap: " + emitted);
        assertEquals(List.of("failed"), projection.terminal);
        assertTrue(projection.error.contains("Review diff limit setting could not be read"), projection.error);
        assertEquals(0, projection.scheduledRetries, "a closed window is not retried");
        RecordCommand.RecordFailure failure = (RecordCommand.RecordFailure) recorded.getFirst();
        assertFalse(failure.retryable(), "the review leaves REVIEWING");
    }

    @Test
    void aDeliveryForAReviewNoLongerRunningChangesNothing() {
        ResultSaga saga = sagaWith(unreadableLimit());
        deferrals.outcome = ReviewLimitDeferrals.Outcome.NOT_RUNNING;

        saga.on(contextAssembled());

        assertTrue(emitted.isEmpty());
        assertTrue(projection.terminal.isEmpty(), "a stale delivery does not fail a finished review");
        assertTrue(recorded.isEmpty());
    }

    @Test
    void aRecoveredReadClosesTheWindowAndDispatchesGenerateReview() {
        ResultSaga saga = sagaWith(readableLimit());

        saga.on(contextAssembled());

        assertEquals(1, emitted.size(), "the review goes on: " + emitted);
        assertInstanceOf(ActionCommand.GenerateReview.class, emitted.getFirst());
        assertEquals(List.of(REVIEW_ID), deferrals.cleared, "a later failure starts a new window");
        assertTrue(deferrals.deferred.isEmpty());
    }

    private static ContextAssembled contextAssembled() {
        return new ContextAssembled(REVIEW_ID, 7L, COMMIT, "TEST-context-ref", Set.of(), Set.of());
    }

    private ResultSaga sagaWith(WorkerLlmCredentials credentials) {
        ResultSaga saga = new ResultSaga();
        saga.reviewPolicy = ResultSagaLimitReadFakes.policy();
        saga.lifecycle = reviewingLifecycle();
        saga.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        saga.timeline = ResultSagaLimitReadFakes.silentTimeline();
        saga.projection = projection;
        saga.harnessStart = apiReviews();
        saga.workerLlmCredentials = credentials;
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("TEST-scm-cred");
            }
        };
        saga.spendGate = ResultSagaLimitReadFakes.uncapped();
        saga.promptTemplates = ResultSagaLimitReadFakes.noPrompts();
        saga.limitDeferral = deferral(saga.timeline);
        return saga;
    }

    private LimitReadDeferral deferral(TimelineBroadcaster timeline) {
        LimitReadDeferral deferral = new LimitReadDeferral();
        deferral.deferrals = deferrals;
        deferral.projection = projection;
        deferral.timeline = timeline;
        return deferral;
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

    private static WorkerLlmCredentials readableLimit() {
        return new WorkerLlmCredentials() {
            @Override
            public DefaultLlm resolveForReview(String reviewId) {
                return DefaultLlm.spendable("TEST-packed-llm", "TEST-model");
            }
        };
    }

    /** Answers the outcome the test sets and records each call; the real store's SQL has its own database test. */
    private static final class FakeDeferrals extends ReviewLimitDeferrals {
        private final List<String> deferred = new ArrayList<>();
        private final List<String> cleared = new ArrayList<>();
        private Outcome outcome = Outcome.DEFERRED;

        @Override
        public Outcome defer(Deferral deferral) {
            deferred.add(deferral.reviewId() + "@" + deferral.commit());
            return outcome;
        }

        @Override
        public void clear(String reviewId) {
            cleared.add(reviewId);
        }
    }

    /** Every method the path reaches is overridden: an un-overridden one opens a real database. */
    private static final class RecordingProjection extends ReviewProjection {
        private final List<String> terminal = new ArrayList<>();
        private int scheduledRetries;
        private String error = "";

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
        public void touch(String reviewId) {
        }

        @Override
        public Optional<PriorRun> priorRunFor(String reviewId) {
            return Optional.empty();
        }

        @Override
        public int currentAttempt(String reviewId) {
            return 1;
        }

        @Override
        public void scheduleRetry(String reviewId, int attempt, String note, Instant dueAt) {
            scheduledRetries++;
        }

        @Override
        public void clearScheduledRetry(String reviewId) {
        }

        @Override
        public void projectTerminalFailure(String reviewId, int stage, String note, String error) {
            terminal.add("failed");
            this.error = error;
        }
    }
}
