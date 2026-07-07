package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The C8 bounded-retry decision in {@link ResultSaga}: a retryable failure with
 * budget left restarts the pipeline from FetchDiff; an exhausted budget, a gone
 * provider, or a permanent failure fails the run terminally (leaving REVIEWING).
 * Collaborators are field-injected, so hand-written fakes are set directly — no
 * CDI container, no mocking framework (mirrors {@link IntegrationSagaPolicyTest}).
 */
class ResultSagaRetryTest {

    private static final RepoRef REPO = new RepoRef("acme", "web");
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, 412L);
    private static final String COMMIT = "cafe123";

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<RecordCommand> recorded = new ArrayList<>();
    private final List<String> retryNotes = new ArrayList<>();
    private final List<Integer> retryAttempts = new ArrayList<>();
    private final List<String> terminalStatuses = new ArrayList<>();
    private final List<String> terminalErrors = new ArrayList<>();

    private ResultSaga sagaWith(int storedAttempt, int maxAttempts, Optional<String> credential) {
        ResultSaga saga = new ResultSaga();
        saga.maxAttempts = maxAttempts;
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                recorded.add(command);
                return List.of();
            }
        };
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
        saga.projection = new ReviewProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
            }

            @Override
            public int currentAttempt(String reviewId) {
                return storedAttempt;
            }

            @Override
            public void retryPipeline(String reviewId, int attempt, String note) {
                retryAttempts.add(attempt);
                retryNotes.add(note);
            }

            @Override
            public void updateStatus(String reviewId, String status, int stage) {
                terminalStatuses.add(status);
            }

            @Override
            public void setNote(String reviewId, String note) {
            }

            @Override
            public void setError(String reviewId, String error) {
                terminalErrors.add(error);
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForWorkspace(String workspace) {
                return credential;
            }
        };
        return saga;
    }

    private static ReviewFailed failure(boolean retryable) {
        return new ReviewFailed(REVIEW_ID, COMMIT, "generate", "boom", retryable, 1);
    }

    @Test
    void retryableWithBudget_restartsPipelineFromFetchDiff() {
        var saga = sagaWith(1, 3, Optional.of("packed-cred"));
        saga.on(failure(true));

        assertEquals(1, emitted.size(), "one command re-emitted");
        var fetch = assertInstanceOf(ActionCommand.FetchDiff.class, emitted.get(0));
        assertEquals(REVIEW_ID, fetch.reviewId());
        assertEquals(412L, fetch.prId(), "prId recovered from the reviewId");
        assertEquals(COMMIT, fetch.commit());
        assertEquals("packed-cred", fetch.scmCredential(), "retry carries a freshly-brokered credential");
        assertEquals(List.of(2), retryAttempts, "attempt counter bumped to 2");
        assertTrue(retryNotes.get(0).contains("2/3"), "note shows the attempt budget");
        assertTrue(recorded.isEmpty(), "no terminal RecordFailure while retrying");
        assertTrue(terminalStatuses.isEmpty(), "status not flipped to failed on a retry");
    }

    @Test
    void retryableButBudgetExhausted_failsTerminally() {
        var saga = sagaWith(3, 3, Optional.of("packed-cred"));
        saga.on(failure(true));

        assertTrue(emitted.isEmpty(), "no retry once the budget is spent");
        assertTrue(retryAttempts.isEmpty());
        assertEquals(List.of("failed"), terminalStatuses);
        var rf = assertInstanceOf(RecordCommand.RecordFailure.class, recorded.get(0));
        assertEquals(false, rf.retryable(), "forced non-retryable so the aggregate goes terminal");
    }

    @Test
    void nonRetryable_failsTerminallyImmediately() {
        var saga = sagaWith(1, 3, Optional.of("packed-cred"));
        saga.on(failure(false));

        assertTrue(emitted.isEmpty(), "a permanent failure is never retried");
        assertEquals(List.of("failed"), terminalStatuses);
        assertEquals(List.of("boom"), terminalErrors, "the provider error is persisted for the UI");
        var rf = assertInstanceOf(RecordCommand.RecordFailure.class, recorded.get(0));
        assertEquals(false, rf.retryable());
    }

    @Test
    void retryableButProviderGone_failsTerminally() {
        var saga = sagaWith(1, 3, Optional.empty());
        saga.on(failure(true));

        assertTrue(emitted.isEmpty(), "cannot retry without a provider credential");
        assertEquals(List.of("failed"), terminalStatuses);
        assertInstanceOf(RecordCommand.RecordFailure.class, recorded.get(0));
    }
}
