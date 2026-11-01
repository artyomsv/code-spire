package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.RunWorkArtifact;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunResultSaga;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A specification or plan run that finishes after its item was taken over still ran and still bought what it bought
 * (review of PR 287, H-2): its wall time, calls and cost reach the item's counters once, and a run whose cost is unknown
 * makes the item's usage unknown, while its artifact, the gate and the phase stay as the takeover left them.
 */
@QuarkusTest
@TestSecurity(user = "TEST-spec-admin", roles = "spire-admin")
class WorkDraftLateUsageIT extends WorkSpecRunFixture {
    private static final long WALL_SECONDS = 3;

    @Inject RunResultSaga saga;
    @Inject WorkDraftResults draftResults;
    @Inject WorkItemControl control;
    @Inject FactoryRunProjection runs;

    private static RunWorkArtifact artifact(RunCommand.ExecuteWorkDraftRun command) {
        return new RunWorkArtifact(command.runId(), command.draft(), WorkSpecRunIT.SPEC, false, Map.of("INPUT", 10L), WALL_SECONDS, java.util.List.of());
    }

    /** The item's specification run dispatched, then the item taken over by a person while the run is out. */
    private RunCommand.ExecuteWorkDraftRun takenOver(String id) {
        RunCommand.ExecuteWorkDraftRun command = dispatched();
        control.suspend(id, "tracker", "TEST-takeover-" + UUID.randomUUID(), "900123", null);
        assertEquals("suspended", store.load(id).workflowStatus());
        return command;
    }

    private long lateResults(String id) {
        return store.history(id).stream().map(EventEnvelope::payload).map(WorkItemEvent.class::cast)
                .filter(event -> "LATE_DRAFT_RESULT".equals(event.milestone())).count();
    }

    @Test
    void measuredUsageAfterATakeoverIsCounted() throws Exception {
        String id = started("spec-approve", 141);
        RunCommand.ExecuteWorkDraftRun command = takenOver(id);
        WorkItemEvent before = store.load(id);

        saga.on(artifact(command));

        WorkItemEvent after = store.load(id);
        FactoryRunProjection.RunView run = runs.find(command.runId()).orElseThrow();
        assertTrue(run.cost().isKnown(), "the run was charged");
        assertEquals(before.progress().calls() + 1, after.progress().calls());
        assertEquals(before.progress().wallSeconds() + WALL_SECONDS, after.progress().wallSeconds());
        assertEquals(before.progress().costMillicents() + run.cost().millicents(), after.progress().costMillicents());
        assertFalse(after.progress().usageUnknown());
        assertState(after, before.phase(), "suspended", before.reason());
        assertEquals(before.gate(), after.gate());
        assertNull(after.draft().specification(), "the late artifact is not the item's");
        assertEquals(0, count("SELECT count(*) FROM work_item_artifact WHERE work_item_id=?", id));
        assertEquals("refused", effectState(command.draft().attemptId()));
    }

    @Test
    void unknownUsageAfterATakeoverMakesTheItemsUsageUnknown() throws Exception {
        String id = started("spec-approve", 142);
        RunCommand.ExecuteWorkDraftRun command = takenOver(id);
        long calls = store.load(id).progress().calls();
        RunWorkArtifact result = artifact(command);
        // Projected but never charged: the ledger has no measurement, so the run's cost is unknown, not zero.
        runs.apply(result);
        assertFalse(runs.find(command.runId()).orElseThrow().cost().isKnown());

        assertTrue(draftResults.accept(result));

        WorkItemEvent after = store.load(id);
        assertTrue(after.progress().usageUnknown());
        assertEquals(calls + 1, after.progress().calls());
        assertEquals("suspended", after.workflowStatus());
    }

    @Test
    void aLateResultDeliveredTwiceIsCountedOnce() throws Exception {
        String id = started("spec-approve", 143);
        RunCommand.ExecuteWorkDraftRun command = takenOver(id);
        long calls = store.load(id).progress().calls();
        saga.on(artifact(command));
        // The crash window after the item counted the usage but before the inbox acknowledged the result.
        executeWith("UPDATE work_prepare_effect SET result_processed=false WHERE run_id=?", command.runId());

        draftResults.recover();
        saga.on(artifact(command));

        assertEquals(calls + 1, store.load(id).progress().calls());
        assertEquals(1, lateResults(id));
    }
}
