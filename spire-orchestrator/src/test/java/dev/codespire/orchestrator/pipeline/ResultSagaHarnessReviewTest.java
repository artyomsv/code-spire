package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.DefaultLlm;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.review.run.HarnessReviewStart;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A first review on a harness repository (harness reviews, step 5): at {@code ContextAssembled} the saga asks
 * {@link HarnessReviewStart}, and a started run sends no {@code GenerateReview}; a refused one ends the review
 * {@code refused} with the reason the attention feed shows. The run's outcome comes back through
 * {@link ResultSaga#acceptHarnessOutcome}, which takes the existing outcome path and never charges again.
 *
 * <p>A plain unit test with hand-written fakes, as {@code ResultSagaVerdictTest} has. Every method the
 * ContextAssembled, ReviewGenerated and refusal paths reach is overridden on purpose: an un-overridden concrete method
 * opens a real connection from a unit test (CLAUDE.md, saga test fakes).
 */
class ResultSagaHarnessReviewTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final long PR = 7L;
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, PR);
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final ContextAssembled CONTEXT =
            new ContextAssembled(REVIEW_ID, PR, COMMIT, "TEST-context-ref", Set.of(), Set.of());

    private HarnessReviewStart.Decision decision = new HarnessReviewStart.NotHarness();
    private int openBlockers;

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<RecordCommand> recorded = new ArrayList<>();
    private final List<String> statuses = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> harnessRefusals = new ArrayList<>();
    private final List<ChargeCall> charges = new ArrayList<>();
    private final List<String> timelineLines = new ArrayList<>();

    @Test
    void aStartedHarnessReviewSendsNoGenerateReview() {
        decision = new HarnessReviewStart.Started("TEST-run-1", "TEST-harness");

        saga().on(CONTEXT);

        assertTrue(emitted.stream().noneMatch(ActionCommand.GenerateReview.class::isInstance),
                "a harness review is a run, never a worker call: " + emitted);
        assertTrue(timelineLines.contains("harness-review:TEST-run-1"), timelineLines.toString());
        assertEquals("Reviewing on TEST-harness, run TEST-run-1", notes.getLast());
    }

    @Test
    void aRefusedHarnessReviewIsRefusedWithAttentionReason() {
        decision = new HarnessReviewStart.Refused("codex", "subscription_unavailable");

        saga().on(CONTEXT);

        assertTrue(emitted.stream().noneMatch(ActionCommand.GenerateReview.class::isInstance),
                "a refused harness review never falls back to the API: " + emitted);
        assertEquals("refused", statuses.getLast());
        assertEquals("codex: subscription_unavailable", harnessRefusals.getLast());
        assertEquals("Harness review refused: subscription_unavailable", notes.getLast());
        RecordCommand.RecordFailure failure = assertInstanceOf(RecordCommand.RecordFailure.class, recorded.getLast());
        assertEquals("harness-review", failure.phase());
        assertFalse(failure.retryable(), "a refusal leaves REVIEWING terminally");
    }

    /** The control: a repository on the API reviews exactly as today. */
    @Test
    void aNonHarnessReviewSendsGenerateReviewAsToday() {
        saga().on(CONTEXT);

        assertInstanceOf(ActionCommand.GenerateReview.class, emitted.getLast());
    }

    /**
     * Charged once: step 4's ReviewRunCharges already charged the run under the review's call slot. The same event
     * through the worker path does charge, which proves the fake would have seen a charge.
     */
    @Test
    void aHarnessOutcomeIsNotChargedAgain() {
        ResultSaga saga = saga();

        saga.acceptHarnessOutcome(generated(COMMIT));
        assertTrue(charges.isEmpty(), "a harness outcome is never charged by the saga: " + charges);

        saga.on(generated(COMMIT));
        assertFalse(charges.isEmpty(), "the control: a worker's ReviewGenerated is charged");
    }

    @Test
    void aHarnessOutcomeForAnOldCommitIsDropped() {
        saga().acceptHarnessOutcome(generated("fedcba9876543210fedcba9876543210fedcba98"));

        assertTrue(emitted.isEmpty(), "a superseded commit's answer is never posted: " + emitted);
    }

    @Test
    void aHarnessOutcomeEmitsPostComments() {
        openBlockers = 1;

        saga().acceptHarnessOutcome(generated(COMMIT));

        ActionCommand.PostComments post = assertInstanceOf(ActionCommand.PostComments.class, emitted.getLast());
        assertEquals(1, post.findings().findings().size());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.verdict(), "the verdict is decided here, from the findings");
    }

    @Test
    void anOutcomeOfAnotherKindIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> saga().acceptHarnessOutcome(CONTEXT));
    }

    private static ReviewGenerated generated(String commit) {
        Finding finding = new Finding("src/TEST.java", new LineRange(3, 3), Severity.BLOCKER, null,
                "TEST-message", null);
        return new ReviewGenerated(REVIEW_ID, PR, commit,
                new ReviewResult(List.of(finding), "TEST-summary", ModelUsage.of("TEST-model", 10, 5)));
    }

    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.harnessStart = new HarnessReviewStart() {
            @Override
            public Decision start(ContextAssembled event) {
                return decision;
            }
        };
        saga.findings = SILENT_FINDINGS;
        saga.preferenceFilter = NO_PREFERENCES;
        saga.lifecycle = lifecycle();
        saga.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                timelineLines.add(type);
            }
        };
        saga.threads = new ReviewThreadView() {
            @Override
            public void markFindingThread(String reviewId, ThreadRef thread, String path, int line) {
            }

            @Override
            public void markSummaryThread(String reviewId, ThreadRef thread) {
            }
        };
        saga.projection = projection();
        wireRepository(saga);
        wireCalls(saga);
        return saga;
    }

    private ReviewLifecycleService lifecycle() {
        return new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                recorded.add(command);
                return List.of();
            }

            @Override
            public ReviewState currentState(String reviewId) {
                return new ReviewState(reviewId, REPO, PR, ReviewState.Status.REVIEWING,
                        COMMIT, Set.of(), null, java.util.Map.of(), Set.of());
            }
        };
    }

    private void wireRepository(ResultSaga saga) {
        saga.repositories = new dev.codespire.orchestrator.repository.RepositoryRegistry() {
            @Override
            public Optional<Boolean> reviewerMayApprove(UUID id) {
                return Optional.of(true);
            }
        };
        saga.repositoryAccounts = new dev.codespire.orchestrator.repository.RepositoryAccounts() {
            @Override
            public boolean reviewerIdentityKnown(UUID repositoryId) {
                return true;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("TEST-packed-scm");
            }
        };
    }

    /** What the API path reaches before GenerateReview, and what a charge reaches. */
    private void wireCalls(ResultSaga saga) {
        saga.workerLlmCredentials = new WorkerLlmCredentials() {
            @Override
            public DefaultLlm resolveForReview(String reviewId) {
                return DefaultLlm.spendable("TEST-packed-llm", "TEST-model");
            }
        };
        saga.spendGate = new SpendGate() {
            @Override
            public Decision decide() {
                return Decision.of(CapRefusal.allow());
            }
        };
        saga.promptTemplates = new WorkerPromptTemplates() {
            @Override
            public dev.codespire.contract.llm.PromptTemplate forKind(
                    dev.codespire.contract.llm.PromptKind kind, RepoRef repo) {
                return null;
            }
        };
        saga.llmModels = new LlmModelRegistry() {
            @Override
            public List<ChargeLine> priceCall(String model, ModelUsage usage) {
                return List.of();
            }
        };
        saga.runs = new dev.codespire.orchestrator.llm.ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return 1;
            }

            @Override
            public int roundOrUnknown(String reviewId) {
                return 1;
            }
        };
    }

    private dev.codespire.orchestrator.TestRepositoryProjection projection() {
        return new dev.codespire.orchestrator.TestRepositoryProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
            }

            @Override
            public void updateStage(String reviewId, int stage) {
            }

            @Override
            public void updateStatus(String reviewId, String status, int stage) {
                statuses.add(status);
            }

            @Override
            public void clearScheduledRetry(String reviewId) {
            }

            @Override
            public void setNote(String reviewId, String note) {
                notes.add(note);
            }

            // Step 5: the harness refusal the attention feed reads; the real one opens a database.
            @Override
            public void setHarnessRefusal(String reviewId, String reason) {
                if (reason != null) harnessRefusals.add(reason);
            }

            @Override
            public void recordOutcome(String reviewId, ReviewResult result, int stage) {
            }

            @Override
            public Optional<PriorRun> priorRunFor(String reviewId) {
                return Optional.empty();
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

            @Override
            public void recordCharges(ChargeCall call) {
                charges.add(call);
            }

            @Override
            public void recordPosted(String reviewId, String commit, String summaryCommentId) {
            }

            @Override
            public int openBlockers(String reviewId) {
                return openBlockers;
            }

            @Override
            public void recordPostedVerdict(String reviewId, String commit, ReviewVerdict verdict, String reviewRef,
                    String reviewUrl) {
            }
        };
    }

    private static final dev.codespire.orchestrator.memory.PreferenceFilter NO_PREFERENCES =
            new dev.codespire.orchestrator.memory.PreferenceFilter() {
                @Override
                public Filtered apply(RepoRef repo, ReviewResult result) {
                    return new Filtered(result, List.of());
                }
            };

    private static final dev.codespire.orchestrator.readmodel.FindingProjection SILENT_FINDINGS =
            new dev.codespire.orchestrator.readmodel.FindingProjection() {
                @Override
                public void recordGenerated(String reviewId, int round, String commit, List<Finding> findings) {
                }

                @Override
                public void recordThreadRefs(String reviewId, List<CommentsPosted.PostedInline> posted) {
                }

                @Override
                public void recordVerdicts(String reviewId, int round,
                        List<dev.codespire.contract.review.FindingVerdict> verdicts) {
                }

                @Override
                public void markSuppressed(String reviewId, int round,
                        dev.codespire.orchestrator.readmodel.SuppressionBatch batch) {
                }
            };
}
