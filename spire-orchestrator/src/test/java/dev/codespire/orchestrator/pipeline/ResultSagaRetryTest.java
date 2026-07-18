package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
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

            @Override
            public Optional<dev.codespire.contract.review.PriorRun> priorRunFor(String reviewId) {
                return Optional.empty();
            }

            @Override
            public void recordPosted(String reviewId, String commit, String summaryCommentId) {
            }

            @Override
            public void recordReconciliation(String reviewId,
                    List<dev.codespire.contract.review.FindingVerdict> verdicts,
                    List<dev.codespire.contract.review.PriorFinding> priorFindings) {
            }

            @Override
            public void recordOpenFindings(String reviewId, ReviewResult result,
                    List<dev.codespire.contract.review.FindingVerdict> verdicts,
                    List<dev.codespire.contract.review.PriorFinding> priorFindings) {
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
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

    /**
     * Saga-level seam for the priorSummaryRef fix: a follow-up review after a CLEAN prior run
     * (0 findings, so no reconciliation to record) must still resolve priorSummaryRef from
     * priorRunFor so PostComments updates the existing summary in place instead of duplicating it.
     */
    @Test
    void reviewGeneratedWithEmptyVerdicts_stillCarriesThePriorSummaryRefWhenAPriorRunExists() {
        PriorRun cleanPrior = new PriorRun(COMMIT, "sum-prior-1", List.of());
        var saga = sagaForReviewGenerated(cleanPrior, Optional.of("packed-cred"));

        var result = new ReviewResult(List.of(), "all clean", new ModelUsage(null, 0, 0, 0));
        saga.on(new ReviewGenerated(REVIEW_ID, 412L, COMMIT, result));

        assertEquals(1, emitted.size(), "one PostComments command emitted");
        var postComments = assertInstanceOf(ActionCommand.PostComments.class, emitted.get(0));
        assertNotNull(postComments.priorSummaryRef(),
                "priorSummaryRef must resolve from priorRunFor even when verdicts are empty");
        assertEquals("sum-prior-1", postComments.priorSummaryRef());
    }

    /** Minimal ResultSaga wired for the ReviewGenerated -> PostComments path (no ReviewFailed fakery needed). */
    private ResultSaga sagaForReviewGenerated(PriorRun priorRun, Optional<String> credential) {
        ResultSaga saga = new ResultSaga();
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                recorded.add(command);
                return List.of();
            }

            @Override
            public ReviewState currentState(String reviewId) {
                return new ReviewState(reviewId, REPO, 412L, ReviewState.Status.REVIEWING,
                        COMMIT, java.util.Set.of(), null, java.util.Map.of());
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
            public void recordOutcome(String reviewId, ReviewResult result, int stage) {
            }

            @Override
            public void recordLlmCall(String reviewId, String kind, ModelUsage usage) {
            }

            @Override
            public Optional<PriorRun> priorRunFor(String reviewId) {
                return Optional.of(priorRun);
            }

            @Override
            public void recordReconciliation(String reviewId,
                    List<dev.codespire.contract.review.FindingVerdict> verdicts,
                    List<dev.codespire.contract.review.PriorFinding> priorFindings) {
            }

            @Override
            public void recordOpenFindings(String reviewId, ReviewResult result,
                    List<dev.codespire.contract.review.FindingVerdict> verdicts,
                    List<dev.codespire.contract.review.PriorFinding> priorFindings) {
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return credential;
            }
        };
        return saga;
    }
}
