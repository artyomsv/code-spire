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
import dev.codespire.orchestrator.llm.ReviewRuns;
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

    /** Which run the fake {@link ReviewRuns} reports; a test bumps it to stand for a re-run. */
    private int run = ReviewRuns.FIRST_RUN;

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

    /**
     * A review result with no token usage must not take the whole handler down.
     *
     * <p>{@code charge} dereferences the usage for the model name, and this was the one of three call
     * sites with no null check — so a usage-less result dead-lettered the result event, losing the
     * findings, the reconciliation and the PostComments command along with the charge. Nothing is
     * charged (there is no model to name, and {@code llm_charge.model} is NOT NULL), but everything
     * else on the path still runs.
     */
    @Test
    void aReviewResultWithNoUsageStillCompletesWithoutCharging() {
        ResultSaga saga = sagaFor("TEST-MODEL", true);

        saga.on(reviewGenerated(REVIEW_ID, COMMIT, null));

        assertTrue(projection.recordedCalls().isEmpty(), "there is no model to charge this under");
        assertEquals(1, emitted.size(), "the rest of the path must still run — this is not a failure");
        assertInstanceOf(ActionCommand.PostComments.class, emitted.get(0));
    }

    /**
     * A re-run's call is charged under its own ref, on the very same commit.
     *
     * <p>The Re-run button clears the worker's cached result so the LLM genuinely runs again, which
     * makes the commit alone the wrong identity: run 2 resolved to run 1's {@code call_ref} and
     * {@code recordCharges}' {@code ON CONFLICT DO NOTHING} dropped the whole call. As with the
     * conversation-turn defect above, the fake projection records what it is handed, so the flaw shows
     * here as two calls sharing one ref and in production as a missing row.
     */
    @Test
    void aSecondRunOnTheSameCommitIsChargedUnderItsOwnRef() {
        ResultSaga saga = sagaFor("TEST-MODEL", true);
        ModelUsage usage = ModelUsage.of("TEST-MODEL", 1_000_000, 500_000);

        saga.on(reviewGenerated(REVIEW_ID, COMMIT, usage));
        run = 2; // the operator pressed Re-run: same commit, cached result dropped, LLM runs again
        saga.on(reviewGenerated(REVIEW_ID, COMMIT, usage));

        assertEquals(List.of("review::TEST-WS/TEST-REPO#1|TESTSHA00000|REVIEW",
                        "review::TEST-WS/TEST-REPO#1|TESTSHA00000#run2|REVIEW"),
                projection.recordedCalls().stream().map(ChargeCall::callRef).toList(),
                "run 1 keeps the bare commit; run 2 must not reuse it");
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

    /**
     * A follow-up is keyed to its thread AND the comment it answers — the pair the worker claims
     * under, not just the thread half of it.
     */
    @Test
    void aFollowUpIsChargedUnderItsThreadAndTriggeringComment() {
        ResultSaga saga = sagaFor("TEST-MODEL", true);
        ModelUsage usage = ModelUsage.of("TEST-MODEL", 300_000, 150_000);

        saga.on(followUp("TEST-THREAD-1", "TEST-COMMENT-1", usage));

        assertEquals("review::TEST-WS/TEST-REPO#1|TEST-THREAD-1:TEST-COMMENT-1|FOLLOWUP",
                projection.recordedCalls().get(0).callRef());
    }

    /**
     * Every turn of one conversation is its own paid call, so every turn needs its own ledger identity.
     *
     * <p>The worker claims per (thread, triggering comment) and permits turn 2 to spend; the
     * orchestrator derived its {@code call_ref} from the thread alone, so turns 2..N resolved to turn
     * 1's ref and {@code recordCharges}' {@code ON CONFLICT DO NOTHING} dropped them — no row, no log,
     * no attention row. With a default turn cap of 4 (and no cap at all once the bot is @-mentioned)
     * that is most of a conversation's spend missing from the total an operator reads.
     *
     * <p>The DISTINCT count is the assertion that matters: the fake projection records every call it
     * is handed, where the real {@code recordCharges} drops a colliding one at the database. So the
     * defect shows here as two calls sharing one ref, and in production as a missing row.
     */
    @Test
    void twoTurnsOfOneConversationAreTwoDistinctCharges() {
        ResultSaga saga = sagaFor("TEST-MODEL", true);

        saga.on(followUp("TEST-THREAD-2", "TEST-COMMENT-1", ModelUsage.of("TEST-MODEL", 300_000, 150_000)));
        saga.on(followUp("TEST-THREAD-2", "TEST-COMMENT-2", ModelUsage.of("TEST-MODEL", 400_000, 200_000)));

        assertEquals(2, projection.recordedCalls().stream().map(ChargeCall::callRef).distinct().count(),
                "two turns sharing one call_ref means the second is discarded as a redelivery");
        assertEquals(2, projection.recordedCalls().size(),
                "each turn is a separate paid call and must be charged separately");
    }

    /**
     * The same turn delivered twice must still collapse — that is what the derived ref is FOR. Without
     * this, "make the ref unique per turn" could be satisfied by making it unique per delivery, which
     * would double-charge on every redelivery.
     */
    @Test
    void theSameTurnRedeliveredKeepsOneChargeIdentity() {
        ResultSaga saga = sagaFor("TEST-MODEL", true);
        ModelUsage usage = ModelUsage.of("TEST-MODEL", 300_000, 150_000);

        saga.on(followUp("TEST-THREAD-3", "TEST-COMMENT-9", usage));
        saga.on(followUp("TEST-THREAD-3", "TEST-COMMENT-9", usage));

        assertEquals(1, projection.recordedCalls().stream().map(ChargeCall::callRef).distinct().count(),
                "a redelivered turn is the same call and must resolve to the same ref");
    }

    /**
     * A legacy event — recorded before {@code triggeringCommentId} existed — must still charge rather
     * than fail. It falls back to the thread ref, which is the identity it was written under, so a
     * replay reproduces its original {@code call_ref} instead of a new one.
     */
    @Test
    void aLegacyFollowUpWithoutATriggeringCommentFallsBackToItsThreadRef() {
        ResultSaga saga = sagaFor("TEST-MODEL", true);

        saga.on(followUp("TEST-THREAD-4", null, ModelUsage.of("TEST-MODEL", 300_000, 150_000)));

        assertEquals("review::TEST-WS/TEST-REPO#1|TEST-THREAD-4|FOLLOWUP",
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
        saga.runs = new ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return run;
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

    private static FollowUpGenerated followUp(String threadRef, String triggeringCommentId, ModelUsage usage) {
        return new FollowUpGenerated(REVIEW_ID, new ThreadRef(threadRef), "because it leaks a resource",
                usage, triggeringCommentId);
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
