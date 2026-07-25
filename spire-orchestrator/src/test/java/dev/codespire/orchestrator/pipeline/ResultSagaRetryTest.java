package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.FollowUpGenerated;
import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
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
    private final List<java.time.Instant> scheduledFor = new ArrayList<>();
    private final List<String> clearedSchedules = new ArrayList<>();
    private final List<String> terminalStatuses = new ArrayList<>();
    private final List<String> terminalErrors = new ArrayList<>();

    private ResultSaga sagaWith(int storedAttempt, int maxAttempts, Optional<String> credential) {
        ResultSaga saga = new ResultSaga();
        // The budget is read from the policy on each failure, so Settings changes it without a restart.
        saga.reviewPolicy = new dev.codespire.orchestrator.policy.ReviewPolicy() {
            @Override
            public int maxAttempts() {
                return maxAttempts;
            }

            @Override
            public java.time.Duration retryDelay(int attempt) {
                // The real one reads app_setting; the schedule-vs-dispatch decision under test doesn't
                // depend on the exact wait, only that one is applied.
                return java.time.Duration.ofSeconds(5L * attempt);
            }
        };
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
            public void scheduleRetry(String reviewId, int attempt, String note, java.time.Instant dueAt) {
                retryAttempts.add(attempt);
                retryNotes.add(note);
                scheduledFor.add(dueAt);
            }

            @Override
            public void clearScheduledRetry(String reviewId) {
                clearedSchedules.add(reviewId);
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

            @Override
            public void touch(String reviewId) {
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
    void retryableWithBudget_schedulesTheNextAttemptInsteadOfDispatchingIt() {
        // The delay cannot be awaited here: this consumer is @Blocking and ordered per partition, so
        // sleeping would stall every other review on it. The due time is persisted and the sweeper
        // (ReviewRetryScheduler) dispatches it — which is also why a restart mid-backoff resumes.
        java.time.Instant before = java.time.Instant.now();
        var saga = sagaWith(1, 3, Optional.of("packed-cred"));
        saga.on(failure(true));

        assertTrue(emitted.isEmpty(), "nothing dispatched now — the retry is scheduled");
        assertEquals(List.of(2), retryAttempts, "attempt counter bumped to 2");
        assertTrue(retryNotes.get(0).contains("2/3"), "note shows the attempt budget");
        assertTrue(retryNotes.get(0).contains("retrying in"), "note tells the operator it is waiting: "
                + retryNotes.get(0));
        assertEquals(1, scheduledFor.size(), "one retry scheduled");
        assertTrue(scheduledFor.get(0).isAfter(before), "scheduled in the future, not immediately");
        assertTrue(recorded.isEmpty(), "no terminal RecordFailure while retrying");
        assertTrue(terminalStatuses.isEmpty(), "status not flipped to failed on a retry");
    }

    @Test
    void commentsPosted_keysOwnershipByTheThreadNotTheComment() {
        // GitLab posts a discussion whose id differs from the note's, and a reply arrives under the
        // discussion. Keying ownership by the comment made the bot decline its own thread
        // ("threadIsOurs=false") and stay silent — invisible on GitHub/Bitbucket, where they coincide.
        List<String> ownedThreads = new ArrayList<>();
        ResultSaga saga = new ResultSaga();
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                return List.of();
            }
        };
        saga.threads = new ReviewThreadView() {
            @Override
            public void markFindingThread(String reviewId, ThreadRef thread, String path, int line) {
                ownedThreads.add(thread.value());
            }

            @Override
            public void markSummaryThread(String reviewId, ThreadRef thread) {
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
            }

            @Override
            public void updateStage(String reviewId, int stage) {
            }

            @Override
            public void recordPosted(String reviewId, String commit, String summaryCommentId) {
            }
        };

        saga.on(new dev.codespire.contract.event.IntegrationEvent.CommentsPosted(
                REVIEW_ID, 412L, COMMIT, "summary-1",
                List.of(new dev.codespire.contract.event.IntegrationEvent.CommentsPosted.PostedInline(
                        "note-1", "discussion-1", "src/App.java", 9)),
                List.of()));

        assertEquals(List.of("discussion-1"), ownedThreads,
                "the thread a reply arrives under is what must be owned");
    }

    @Test
    void terminalFailure_cancelsAnyScheduledRetry() {
        // The claim in the sweeper clears retry_at, but a run can also reach a terminal state before the
        // retry comes due — leaving it set would resurrect a finished review.
        var saga = sagaWith(3, 3, Optional.of("packed-cred"));
        saga.on(failure(true));

        assertEquals(List.of(REVIEW_ID), clearedSchedules, "a terminal run leaves no pending retry");
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

            @Override
            public void touch(String reviewId) {
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

    /**
     * A follow-up's cost landing (recordLlmCall) must also bump the dashboard's live feed
     * (updated_at) — otherwise the new cost/turn only shows up after a hard refresh.
     */
    @Test
    void followUpGenerated_touchesTheProjectionForLiveUpdate() {
        List<String> touched = new ArrayList<>();
        ResultSaga saga = new ResultSaga();
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
            }

            @Override
            public void touch(String reviewId) {
                touched.add(reviewId);
            }
        };

        saga.on(new FollowUpGenerated(REVIEW_ID, new ThreadRef("t-1"), "because it leaks a resource", null));

        assertEquals(List.of(REVIEW_ID), touched, "a new follow-up turn must bump the live dashboard");
    }

    /**
     * The bot's reply actually landing on the SCM (FollowUpPosted) must (a) clear the "answering"
     * flag — the normal-completion clear (fix #5), which also bumps the dashboard's live feed — and
     * (b) mark the bot's own answer comment as an owned thread, so a reply to that answer is
     * recognized as ours and multi-turn continues (Bitbucket threads by immediate parent).
     */
    @Test
    void followUpPosted_setsAnsweringFalseAndMarksTheAnswerOwned() {
        List<Boolean> answeringCalls = new ArrayList<>();
        List<String> markedOwned = new ArrayList<>();
        List<String> bumpedThreads = new ArrayList<>();
        ResultSaga saga = new ResultSaga();
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.threads = new ReviewThreadView() {
            @Override
            public ThreadRef rootOf(String reviewId, ThreadRef thread) {
                return thread; // this test's thread IS the root
            }

            @Override
            public void bumpTurn(String reviewId, ThreadRef thread, String lastCommentId) {
                bumpedThreads.add(thread.value());
            }

            @Override
            public void markAnswerThread(String reviewId, ThreadRef answer, ThreadRef root) {
                markedOwned.add(answer.value() + "->" + root.value());
            }
        };
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                recorded.add(command);
                return List.of();
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public void setAnswering(String reviewId, boolean answering) {
                answeringCalls.add(answering);
            }
        };

        saga.on(new FollowUpPosted(REVIEW_ID, new ThreadRef("t-1"), "c-1"));

        assertEquals(List.of(false), answeringCalls,
                "the bot's reply landing must clear the answering flag (and bump the live dashboard)");
        assertEquals(List.of("c-1->t-1"), markedOwned,
                "the answer comment is marked owned AND linked to its conversation root, so a reply to it "
                        + "continues the same conversation instead of starting a new thread");
        assertEquals(List.of("t-1"), bumpedThreads,
                "the turn is counted on the conversation root, so the cap sees one conversation");
    }
}
