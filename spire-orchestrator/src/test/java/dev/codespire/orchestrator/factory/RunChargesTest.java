package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeCapability;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.ChargeSubject;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run's spend reaching the ledger the spend cap reads.
 *
 * <p>The risk this closes is not a missing number on a page. Until a run writes here, the deployment
 * can run the factory all day, spend real money, and the cap that exists to stop it never moves —
 * the same shape as the LLM circuit breaker that once recorded a failed future as a success: a
 * control that installs cleanly and never fires.
 */
class RunChargesTest {

    private static final String RUN_ID = "run::github:TEST-acme/app:s:1";

    /** Records what reached the one ledger writer, without touching a database. */
    private static class RecordingLedger extends ReviewProjection {
        final List<ChargeCall> calls = new ArrayList<>();

        @Override
        public void recordCharges(ChargeCall call) {
            calls.add(call);
        }
    }

    /** Answers the model a run was dispatched with, the way factory_run does. */
    private static final class StubRuns extends FactoryRunProjection {
        String model = "TEST-model";
        java.util.UUID credential;

        @Override
        public Optional<String> modelOf(String runId) {
            return Optional.ofNullable(model);
        }

        /**
         * Overridden deliberately rather than left to the parent.
         *
         * <p>The FIFTH time this repository has hit the same trap: an un-overridden method on a
         * subclass fake opens a real database connection from a plain unit test, and the failure
         * arrives as the swallowed catch in the class under test rather than as anything naming
         * the fake. Every method RunCharges reaches has to be answered here, on purpose.
         */
        @Override
        public Optional<java.util.UUID> harnessCredentialOf(String runId) {
            return Optional.ofNullable(credential);
        }
    }

    /** Prices everything at a flat metered rate, so a line's presence is the thing under test. */
    private static final class StubPricer extends LlmModelPricer {
        @Override
        public List<ChargeLine> priceCall(String model, ModelUsage usage) {
            if (usage == null || usage.counts().isEmpty()) {
                return List.of(ChargeLine.unknown(TokenType.TOTAL, 0));
            }
            return usage.counts().stream()
                    .map(count -> ChargeLine.metered(count.type(), count.tokens(), 1L))
                    .toList();
        }
    }

    private final RecordingLedger ledger = new RecordingLedger();
    private final StubRuns runs = new StubRuns();
    private final StubPricer pricer = new StubPricer();
    private final RunCharges charges = charges();

    private RunCharges charges() {
        RunCharges c = new RunCharges();
        c.ledger = ledger;
        c.runs = runs;
        c.pricer = pricer;
        // Stated rather than left at the field default. Outside CDI a long field is 0, and these
        // tests are about what gets charged -- not about a ceiling nobody set.
        c.maxReportedTokens = RunTokenUsage.UNBOUNDED;
        return c;
    }

    private static RunResult.RunFinished finished(String runId, Map<String, Long> usage) {
        return new RunResult.RunFinished(runId, "refs/heads/spire/s", List.of(), List.of(), usage, false);
    }

    @Test
    void aFinishedRunWritesOneChargeLinePerTokenType() {
        charges.record(finished(RUN_ID, Map.of("INPUT", 1200L, "OUTPUT", 340L)));

        assertEquals(1, ledger.calls.size(), "one call, written atomically — a partial call cannot be flagged");
        ChargeCall call = ledger.calls.getFirst();
        assertEquals(2, call.lines().size());
        assertEquals("TEST-model", call.model());
    }

    @Test
    void aRunsChargeIsRecordedAgainstTheRunNotAReview() {
        // The writer used to hardcode 'REVIEW' with a comment explaining why relying on the column
        // default would mislabel every row. That reasoning is right and the value is now wrong for
        // half its callers: a run charged as a review is money attributed to the wrong thing, and
        // it would surface on some unrelated pull request's cost card.
        charges.record(finished(RUN_ID, Map.of("INPUT", 10L)));

        ChargeCall call = ledger.calls.getFirst();
        assertEquals(ChargeSubject.RUN, call.subjectKind());
        assertEquals(RUN_ID, call.subjectId());
        assertEquals(ChargeKind.BUILD, call.kind());
        assertEquals(ChargeCapability.BUILD, call.capability(),
                "the capability pack that caused the spend, which ADR-035 says cannot be backfilled");
    }

    @Test
    void aRunsChargeIsKeyedSoARedeliveryCannotChargeTwice() {
        // The ledger's UNIQUE (call_ref, token_type) does the work; this asserts the key it is given
        // is deterministic for the run, which is the half that lives in code.
        charges.record(finished(RUN_ID, Map.of("INPUT", 10L)));
        charges.record(finished(RUN_ID, Map.of("INPUT", 10L)));

        assertEquals(ledger.calls.get(0).callRef(), ledger.calls.get(1).callRef(),
                "a redelivered result must reproduce the same key so the ledger discards it");
        assertEquals(CallRefs.forRun(RUN_ID, "agent"), ledger.calls.getFirst().callRef());
    }

    @Test
    void aSecondAttemptChargesUnderItsOwnKey() {
        // The opposite half, and getting the pair backwards is the difference between silently-lost
        // money and silently-inflated money. The run id carries the attempt, so a genuine second run
        // has a different subject and a different key without anything here counting anything.
        String retry = "run::github:TEST-acme/app:s:2";
        charges.record(finished(RUN_ID, Map.of("INPUT", 10L)));
        charges.record(finished(retry, Map.of("INPUT", 10L)));

        assertFalse(ledger.calls.get(0).callRef().equals(ledger.calls.get(1).callRef()),
                "a genuine second run must not collide with the first, or its charges are discarded");
    }

    @Test
    void aRunThatFailedAfterSpendingIsStillCharged() {
        // An agent can work for an hour and then have its push rejected. The tokens were bought.
        // Losing that spend leaves the cap blind to exactly the runs most likely to be run again,
        // so the deployment is under-counted precisely where it is about to be charged twice.
        charges.record(new RunResult.RunFailed(RUN_ID, "PUSH_TRANSPORT_FAILED", "remote hung up",
                true, Map.of("INPUT", 900L)));

        assertEquals(1, ledger.calls.size());
        assertEquals(ChargeSubject.RUN, ledger.calls.getFirst().subjectKind());
    }

    @Test
    void aPostAgentFailureWithNoUsageIsStillCharged() {
        // The agent ran; only the measurement is missing. A call that vanishes takes the
        // unpriced-call count down with it, so the deployment reads as cheaper than it is with
        // nothing on any surface to notice.
        charges.record(new RunResult.RunFailed(RUN_ID, "AGENT_TIMEOUT", "clock", false, null));

        assertEquals(1, ledger.calls.size());
        assertTrue(ledger.calls.getFirst().lines().stream().allMatch(l -> l.tokens() == 0));
    }

    @Test
    void aFailureRaisedBeforeTheAgentIsNotCharged() {
        // These are raised before runtime.create returns, so no agent, no container, no tokens.
        // A zero-token row for them is not a harmless extra: the deployment-wide cap counts
        // COUNT(DISTINCT call_ref) as well as summing money, so a daemon outage failing every
        // dispatch in seconds would spend the whole call budget on runs that bought nothing --
        // and that budget refuses REVIEWS too. A control firing for the wrong reason, which is
        // the same defect as one that never fires.
        charges.record(new RunResult.RunFailed(RUN_ID, "RUNTIME_UNAVAILABLE", "daemon down", true, null));
        charges.record(new RunResult.RunFailed(RUN_ID, "BAD_COMMAND", "no such harness", false, null));
        charges.record(new RunResult.RunFailed(RUN_ID, "CLONE_FAILED", "remote refused", true, null));

        assertTrue(ledger.calls.isEmpty(),
                "a run that never reached its agent has no spend to record and must not consume"
                        + " the call axis of a cap that also gates the reviewer");
    }

    @Test
    void aFailureBeforeTheAgentThatSomehowReportedUsageIsStillCharged() {
        // The skip is narrow on purpose. Measured tokens outrank the taxonomy's expectation --
        // if something reported spend, something was bought, and refusing to record it is the
        // under-count this whole task exists to remove.
        charges.record(new RunResult.RunFailed(RUN_ID, "RUNTIME_UNAVAILABLE", "daemon down", true,
                Map.of("INPUT", 40L)));

        assertEquals(1, ledger.calls.size());
    }

    @Test
    void anUnrecognisedCauseIsChargedRatherThanSkipped() {
        // A cause the taxonomy does not know resolves to UNCLASSIFIED, which answers "may have
        // spent". Defaulting the other way would let a producer silently opt every one of its
        // runs out of the ledger by spelling its cause differently.
        charges.record(new RunResult.RunFailed(RUN_ID, "SOMETHING_NEW", "?", false, null));

        assertEquals(1, ledger.calls.size());
    }

    @Test
    void aRunWhoseModelCannotBeReadIsStillCharged() {
        // The model column is NOT NULL, so a missing one used to be a reason to skip the write.
        // Skipping loses the spend; naming it honestly does not.
        runs.model = null;

        charges.record(finished(RUN_ID, Map.of("INPUT", 10L)));

        assertEquals(1, ledger.calls.size());
        assertEquals(RunCharges.UNRECORDED_MODEL, ledger.calls.getFirst().model());
    }

    @Test
    void aStartedRunIsNotCharged() {
        // Nothing has been spent yet, and a charge here would be priced against usage nobody reported.
        charges.record(new RunResult.RunStarted(RUN_ID, "unit-1"));

        assertTrue(ledger.calls.isEmpty());
    }

    @Test
    void aLedgerFaultDoesNotLoseTheRunsOutcome() {
        // The projection has already written the run's terminal status by the time this runs.
        // Throwing here dead-letters the result and replays it, which re-applies the projection —
        // so a broken ledger would turn every finished run into a redelivery loop.
        AtomicBoolean attempted = new AtomicBoolean();
        RunCharges throwing = charges();
        throwing.ledger = new RecordingLedger() {
            @Override
            public void recordCharges(ChargeCall call) {
                attempted.set(true);
                throw new IllegalStateException("ledger is down");
            }
        };

        throwing.record(finished(RUN_ID, Map.of("INPUT", 10L)));

        assertTrue(attempted.get(),
                "the swallow must be EXERCISED, not skipped -- without this the test stays green"
                        + " if record() is later changed to not attempt the write at all");
    }

}
