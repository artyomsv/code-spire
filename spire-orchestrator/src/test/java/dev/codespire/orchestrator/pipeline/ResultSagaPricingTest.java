package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.FollowUpGenerated;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two behaviours, at the two points where a pricing decision is still possible.
 *
 * <p>Before the spend: an unpriceable model must not produce a GenerateReview at all. After it:
 * whatever the call cost must be recorded as charge lines keyed so redelivery is a no-op.
 *
 * <p>Collaborators are field-injected fakes, same posture as {@link ResultSagaRetryTest} — no CDI
 * container, no mocking framework.
 */
class ResultSagaPricingTest {

    private static final String REVIEW_ID = "review::TEST-WS/TEST-REPO#1";
    private static final String COMMIT = "TESTSHA00000";

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final FakeProjection projection = new FakeProjection();

    @Test
    void contextAssembledDoesNotGenerateAReviewWhenTheModelCannotBePriced() {
        ResultSaga saga = sagaFor("TEST-UNPRICEABLE", false);

        saga.on(contextAssembled(REVIEW_ID, COMMIT));

        assertTrue(emitted.isEmpty(), "no paid command may be emitted for an unpriceable model");
        assertTrue(projection.note().contains("pricing"),
                "the dashboard must say WHY nothing ran, not leave a silent stall");
    }

    @Test
    void contextAssembledGeneratesAReviewWhenTheModelIsPriceable() {
        ResultSaga saga = sagaFor("TEST-PRICEABLE", true);

        saga.on(contextAssembled(REVIEW_ID, COMMIT));

        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.GenerateReview.class, emitted.get(0));
    }

    @Test
    void reviewGeneratedRecordsChargeLinesUnderADeterministicCallRef() {
        ResultSaga saga = sagaFor("TEST-MODEL", true);

        saga.on(reviewGenerated(REVIEW_ID, COMMIT, ModelUsage.of("TEST-MODEL", 1_000_000, 500_000)));

        assertEquals(1, projection.recordedCalls().size());
        assertEquals("review::TEST-WS/TEST-REPO#1|TESTSHA00000|REVIEW",
                projection.recordedCalls().get(0).callRef());
    }

    /** The reconcile call is its own charge, under its own ref, so it cannot collide with the review. */
    @Test
    void aReconcileCallIsChargedSeparatelyFromTheReviewCall() {
        ResultSaga saga = sagaFor("TEST-MODEL", true);
        ModelUsage reviewUsage = ModelUsage.of("TEST-MODEL", 800_000, 400_000);
        ModelUsage reconcileUsage = ModelUsage.of("TEST-MODEL", 200_000, 100_000);
        ReviewResult result = new ReviewResult(List.of(), "reconciled", reviewUsage);

        saga.on(new ReviewGenerated(REVIEW_ID, 1L, COMMIT, result, List.of(), reconcileUsage));

        assertEquals(2, projection.recordedCalls().size());
        assertTrue(projection.recordedCalls().stream()
                .anyMatch(c -> c.callRef().endsWith("|RECONCILE")));
    }

    /** A follow-up is keyed to its thread, matching the slot the worker claims under. */
    @Test
    void aFollowUpIsChargedUnderItsThreadRef() {
        ResultSaga saga = sagaFor("TEST-MODEL", true);
        ModelUsage usage = ModelUsage.of("TEST-MODEL", 300_000, 150_000);

        saga.on(new FollowUpGenerated(REVIEW_ID, new ThreadRef("TEST-THREAD-1"),
                "because it leaks a resource", usage));

        assertEquals("review::TEST-WS/TEST-REPO#1|TEST-THREAD-1|FOLLOWUP",
                projection.recordedCalls().get(0).callRef());
    }

    // ---- wiring --------------------------------------------------------------

    /** A saga wired for the ContextAssembled/ReviewGenerated/FollowUpGenerated paths, with the
     *  registry's priceability fixed to {@code priceable} and its default model to {@code model}. */
    private ResultSaga sagaFor(String model, boolean priceable) {
        ResultSaga saga = new ResultSaga();
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                return List.of();
            }

            @Override
            public ReviewState currentState(String reviewId) {
                return new ReviewState(reviewId, null, 1L, ReviewState.Status.REVIEWING,
                        COMMIT, Set.of(), null, java.util.Map.of());
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        saga.projection = projection;
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("packed-scm-cred");
            }
        };
        saga.workerLlmCredentials = new WorkerLlmCredentials() {
            @Override
            public Optional<String> packDefault(String workspace) {
                return Optional.of("packed-llm-cred");
            }

            @Override
            public Optional<String> defaultModelName() {
                return Optional.of(model);
            }
        };
        saga.llmModels = new LlmModelRegistry() {
            @Override
            public boolean isPriceable(String candidate) {
                return priceable;
            }

            @Override
            public List<ChargeLine> priceCall(String candidate, ModelUsage usage) {
                return List.of(ChargeLine.metered(dev.codespire.contract.review.TokenType.TOTAL,
                        usage.reportedTotal(), 1L));
            }
        };
        saga.promptTemplates = new WorkerPromptTemplates() {
            @Override
            public dev.codespire.contract.llm.PromptTemplate forKind(dev.codespire.contract.llm.PromptKind kind) {
                return null;
            }
        };
        return saga;
    }

    private static ContextAssembled contextAssembled(String reviewId, String commit) {
        return new ContextAssembled(reviewId, 1L, commit, "context-ref", Set.of(), Set.of());
    }

    private static ReviewGenerated reviewGenerated(String reviewId, String commit, ModelUsage usage) {
        ReviewResult result = new ReviewResult(List.of(), "summary", usage);
        return new ReviewGenerated(reviewId, 1L, commit, result);
    }

    /**
     * A hand-rolled {@link ReviewProjection} fake, extended (per the pattern in
     * {@link ResultSagaRetryTest}) with a {@code note()} accessor and a {@code recordCharges} capture
     * — the concrete real method would otherwise run its real, DataSource-backed body against an
     * instance that was never given one.
     */
    private static final class FakeProjection extends ReviewProjection {
        private String note = "";
        private final List<ChargeCall> recordedCalls = new ArrayList<>();

        String note() {
            return note;
        }

        List<ChargeCall> recordedCalls() {
            return recordedCalls;
        }

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
        public void setNote(String reviewId, String note) {
            this.note = note;
        }

        @Override
        public Optional<PriorRun> priorRunFor(String reviewId) {
            return Optional.empty();
        }

        @Override
        public void recordOutcome(String reviewId, ReviewResult result, int stage) {
        }

        @Override
        public void recordReconciliation(String reviewId, List<FindingVerdict> verdicts,
                List<PriorFinding> priorFindings) {
        }

        @Override
        public void recordOpenFindings(String reviewId, ReviewResult result, List<FindingVerdict> verdicts,
                List<PriorFinding> priorFindings) {
        }

        @Override
        public void touch(String reviewId) {
        }

        @Override
        public void recordCharges(ChargeCall call) {
            recordedCalls.add(call);
        }
    }
}
