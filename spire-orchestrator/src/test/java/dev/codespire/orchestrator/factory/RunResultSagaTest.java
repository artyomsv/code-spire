package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunWorkArtifact;
import dev.codespire.orchestrator.factory.RunResultSagaFixture.RecordingCharges;
import dev.codespire.orchestrator.factory.RunResultSagaFixture.RecordingProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.codespire.orchestrator.factory.RunResultSagaFixture.RUN_ID;
import static dev.codespire.orchestrator.factory.RunResultSagaFixture.saga;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunResultSagaTest {

    /**
     * The proposal reads the row the projection has just written — its branch, its base and the
     * task line stored at dispatch. Called first it would read the QUEUED row, whose pushed ref is
     * still null, and propose nothing for a run that had in fact pushed.
     */
    @Test
    void theProposalRunsAfterTheProjectionHasWrittenTheRow() {
        RecordingProjection projection = new RecordingProjection();
        List<Integer> appliedWhenProposed = new ArrayList<>();
        RunResultSaga saga = saga(projection, new RecordingCharges());
        // Named apart from the field it would otherwise shadow: inside the subclass below,
        // `projection` resolves to FactoryPullRequests' own injected field, not to this local.
        RecordingProjection recorder = projection;
        saga.pullRequests = new FactoryPullRequests() {
            @Override
            public void propose(RunResult result) {
                appliedWhenProposed.add(recorder.applied.size());
            }
        };

        saga.on(new RunResult.RunFinished("run::github:TEST-acme/app:s:1", "refs/heads/spire/s",
                List.of("src/Foo.java"), List.of(), Map.of("input", 10L), false, List.of()));

        assertEquals(List.of(1), appliedWhenProposed, "the row must exist before it is read");
    }

    @Test
    void everyReadableResultReachesTheProjection() {
        RecordingProjection projection = new RecordingProjection();
        RunResult started = new RunResult.RunStarted("run::github:TEST-acme/app:s:1", "unit-1");
        RunResult failed = new RunResult.RunFailed("run::github:TEST-acme/app:s:1", "WORKER_FAILED", "x", true, null, List.of());

        saga(projection).on(started);
        saga(projection).on(failed);

        assertEquals(List.of(started, failed), projection.applied);
    }
    @Test void itemResultsAreProjectedAndChargedBeforeTheirExclusiveContinuation() {
        RecordingProjection projection=new RecordingProjection();RecordingCharges charges=new RecordingCharges();RunResultSaga saga=saga(projection,charges);
        List<RunResult> continued=new ArrayList<>(),proposed=new ArrayList<>();
        saga.workItems=new dev.codespire.orchestrator.work.WorkItemRunBridge(){@Override public boolean accept(RunResult result){
            assertEquals(List.of(result),projection.applied);assertEquals(List.of(result),charges.charged);continued.add(result);return true;
        }};
        saga.pullRequests=new FactoryPullRequests(){@Override public void propose(RunResult result){proposed.add(result);}};
        var result=new RunResult.RunFinished(RUN_ID,"refs/heads/spire/TEST-item",List.of("TEST-file"),List.of(),Map.of("INPUT",1L),false, List.of());saga.on(result);
        assertEquals(List.of(result),continued);assertTrue(proposed.isEmpty(),"An associated result belongs exclusively to the item continuation");
    }

    /**
     * A specification or plan run (M4 slice 3) is projected and charged, and goes no further: it pushed nothing to
     * propose, and the work-run inbox has no row for it (slice 6 gives drafts their own).
     */
    @Test
    void anArtifactIsNeverProposedAsAPullRequest() {
        RecordingProjection projection = new RecordingProjection();
        RecordingCharges charges = new RecordingCharges();
        RunResultSaga saga = saga(projection, charges);
        List<RunResult> continued = new ArrayList<>();
        List<RunResult> proposed = new ArrayList<>();
        saga.workItems = new dev.codespire.orchestrator.work.WorkItemRunBridge() {
            @Override
            public boolean accept(RunResult result) {
                continued.add(result);
                return false;
            }
        };
        saga.pullRequests = new FactoryPullRequests() {
            @Override public void propose(RunResult result) { proposed.add(result); }
        };
        RunResult artifact = new RunWorkArtifact(RUN_ID, new dev.codespire.contract.work.WorkDraftBinding(
                "TEST-item", 1, java.util.UUID.fromString("00000000-0000-4000-8000-000000000005"),
                dev.codespire.contract.work.WorkDraftBinding.Kind.SPEC, "d".repeat(64)),
                "TEST-specification", false, Map.of("INPUT", 3L), 2, List.of());

        saga.on(artifact);

        assertEquals(List.of(artifact), projection.applied);
        assertEquals(List.of(artifact), charges.charged);
        assertTrue(proposed.isEmpty(), "an artifact pushed nothing to propose");
        assertTrue(continued.isEmpty(), "a draft run has no work-run row to continue");
    }

    /**
     * A draft run's result (M4 slice 3) belongs to the draft inbox alone: after the projection and the charge, neither
     * the work-run bridge nor the pull request proposal sees it, a failure included.
     */
    @Test
    void aDraftResultGoesNoFurtherThanItsInbox() {
        RecordingProjection projection = new RecordingProjection();
        RecordingCharges charges = new RecordingCharges();
        RunResultSaga saga = saga(projection, charges);
        List<RunResult> drafted = new ArrayList<>();
        List<RunResult> continued = new ArrayList<>();
        List<RunResult> proposed = new ArrayList<>();
        saga.drafts = new dev.codespire.orchestrator.work.WorkDraftResults() {
            @Override public boolean acceptsBinding(RunResult result) { return true; }
            @Override public boolean accept(RunResult result) {
                assertEquals(List.of(result), projection.applied);
                assertEquals(List.of(result), charges.charged);
                drafted.add(result);
                return true;
            }
        };
        saga.workItems = new dev.codespire.orchestrator.work.WorkItemRunBridge() {
            @Override public boolean accept(RunResult result) { continued.add(result); return false; }
        };
        saga.pullRequests = new FactoryPullRequests() {
            @Override public void propose(RunResult result) { proposed.add(result); }
        };
        RunResult failed = new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 5L), List.of());

        saga.on(failed);

        assertEquals(List.of(failed), drafted);
        assertTrue(continued.isEmpty(), "a draft run has no work-run row");
        assertTrue(proposed.isEmpty(), "a draft run is never proposed");
    }

    /** A draft result whose binding is not the effect row's is neither projected nor charged nor applied. */
    @Test
    void aForeignDraftBindingStopsTheResult() {
        RecordingProjection projection = new RecordingProjection();
        RecordingCharges charges = new RecordingCharges();
        RunResultSaga saga = saga(projection, charges);
        List<RunResult> drafted = new ArrayList<>();
        saga.drafts = new dev.codespire.orchestrator.work.WorkDraftResults() {
            @Override public boolean acceptsBinding(RunResult result) { return false; }
            @Override public boolean accept(RunResult result) { drafted.add(result); return true; }
        };

        saga.on(new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, Map.of("INPUT", 5L), List.of()));

        assertTrue(projection.applied.isEmpty());
        assertTrue(charges.charged.isEmpty());
        assertTrue(drafted.isEmpty());
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
                List.of(), List.of(), Map.of("INPUT", 10L), false, List.of());
        RunResult failed = new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "x", false, Map.of("INPUT", 5L), List.of());

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
                List.of(), List.of(), Map.of("INPUT", 10L), false, List.of()));

        assertEquals(List.of("project", "charge"), order);
    }
}
