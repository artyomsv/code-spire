package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunResultSagaTest {

    private static final class RecordingProjection extends FactoryRunProjection {
        final List<RunResult> applied = new ArrayList<>();

        @Override
        public void apply(RunResult result) {
            applied.add(result);
        }
    }

    private static RunResultSaga saga(RecordingProjection projection) {
        RunResultSaga saga = new RunResultSaga();
        saga.projection = projection;
        return saga;
    }

    @Test
    void everyReadableResultReachesTheProjection() {
        RecordingProjection projection = new RecordingProjection();
        RunResult started = new RunResult.RunStarted("run::github:TEST-acme/app:s:1", "unit-1");
        RunResult failed = new RunResult.RunFailed("run::github:TEST-acme/app:s:1", "WORKER_FAILED", "x", true);

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
}
