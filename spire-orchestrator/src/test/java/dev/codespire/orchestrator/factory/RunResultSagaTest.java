package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunResultSagaTest {

    private static final String RUN_ID = "run::github:TEST-acme/app:s:1";

    private static class RecordingProjection extends FactoryRunProjection {
        final List<RunResult> applied = new ArrayList<>();

        @Override
        public void apply(RunResult result) {
            applied.add(result);
        }
    }

    /** Records what was charged, so the saga's own wiring is asserted rather than stubbed away. */
    private static class RecordingCharges extends RunCharges {
        final List<RunResult> charged = new ArrayList<>();

        @Override
        public void record(RunResult result) {
            charged.add(result);
        }
    }

    private static RunResultSaga saga(RecordingProjection projection) {
        return saga(projection, new RecordingCharges());
    }

    private static RunResultSaga saga(RecordingProjection projection, RecordingCharges charges) {
        RunResultSaga saga = new RunResultSaga();
        saga.projection = projection;
        // Overridden deliberately rather than left null. An un-overridden method on a saga test
        // fake opens a real database connection from a plain unit test -- a trap this repository
        // has hit four times in one milestone.
        saga.charges = charges;
        return saga;
    }

    @Test
    void everyReadableResultReachesTheProjection() {
        RecordingProjection projection = new RecordingProjection();
        RunResult started = new RunResult.RunStarted("run::github:TEST-acme/app:s:1", "unit-1");
        RunResult failed = new RunResult.RunFailed("run::github:TEST-acme/app:s:1", "WORKER_FAILED", "x", true, null);

        saga(projection).on(started);
        saga(projection).on(failed);

        assertEquals(List.of(started, failed), projection.applied);
    }

    @Test
    void aPoisonRecordIsSkippedNotProjected() {
        // The never-throw deserializer yields null for a record it cannot read; it is already on
        // cs.dlq. Projecting null would be an NPE that dead-letters a second copy of the same record.
        RecordingProjection projection = new RecordingProjection();

        saga(projection).on(null);

        assertTrue(projection.applied.isEmpty());
    }

    @Test
    void everyTerminalResultIsCharged() {
        // The wiring, not the pricing. Until this existed the saga read a finished run, wrote its
        // status and dropped the usage on the floor -- so a deployment could run the factory all
        // day, spend real money, and the rolling window the spend cap reads never moved.
        //
        // A FAILED run is charged too: an agent can work for an hour and then have its push
        // rejected, and those tokens were bought.
        RecordingCharges charges = new RecordingCharges();
        RunResult finished = new RunResult.RunFinished(RUN_ID, "refs/heads/spire/x",
                List.of(), List.of(), Map.of("INPUT", 10L), false);
        RunResult failed = new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "x", false, Map.of("INPUT", 5L));

        RunResultSaga saga = saga(new RecordingProjection(), charges);
        saga.on(finished);
        saga.on(failed);

        assertEquals(List.of(finished, failed), charges.charged);
    }

    @Test
    void aPoisonRecordIsNotCharged() {
        // Null reaches here from the never-throw deserializer. Charging it would be an NPE that
        // dead-letters a second copy of a record already on cs.dlq.
        RecordingCharges charges = new RecordingCharges();

        saga(new RecordingProjection(), charges).on(null);

        assertTrue(charges.charged.isEmpty());
    }

    @Test
    void theOutcomeIsProjectedBeforeTheChargeIsRecorded() {
        // The run's outcome is the fact an operator is waiting on, and the ledger write is
        // best-effort by design. Ordering the charge first would let a ledger outage delay a
        // terminal status that is already known.
        List<String> order = new ArrayList<>();
        RecordingProjection projection = new RecordingProjection() {
            @Override
            public void apply(RunResult result) {
                order.add("project");
            }
        };
        RecordingCharges charges = new RecordingCharges() {
            @Override
            public void record(RunResult result) {
                order.add("charge");
            }
        };

        saga(projection, charges).on(new RunResult.RunFinished(RUN_ID, "refs/heads/spire/x",
                List.of(), List.of(), Map.of("INPUT", 10L), false));

        assertEquals(List.of("project", "charge"), order);
    }
}
