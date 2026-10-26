package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.StepSummary;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.orchestrator.factory.BuildDefaults;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunCharges;
import dev.codespire.orchestrator.factory.RunResultSaga;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A plan step that commits nothing (M4 slice 3, §6.7, A3, decisions 9 and 14): it completes as empty and skips verify,
 * the next step starts from the newest run holding a bundle, every step empty delivers nothing, and deliver publishes the
 * last change through the newest step run. Only the buses are TEST boundaries: the run worker is played by the results
 * each test delivers.
 */
@QuarkusTest
@TestProfile(GeneratedPreparationProfile.class)
@TestSecurity(user = "TEST-spec-admin", roles = "spire-admin")
class WorkEmptyStepsIT extends WorkSpecRunFixture {
    static final String SPEC = WorkSpecRunIT.SPEC;

    @Inject RunResultSaga saga;
    @Inject WorkItemRunBridge bridge;
    @Inject FactoryRunProjection runs;
    @Inject RunCharges charges;
    @Inject WorkStepsResource stepsView;

    @BeforeEach
    void emptyStepsSetup() {
        // Verify must be able to pass, so the setup declares the fixture's check.
        defaults.save(repository, new BuildDefaults.Input(defaults.get(repository).revision(), "main", "codex", model, null, null,
                CHECKS, null, BuildDefaults.GENERATED), "TEST-spec-admin");
    }

    private String plan(int steps) {
        ObjectNode root = mapper.createObjectNode().put("schemaVersion", 2).put("specificationSha256", WorkPreparation.digest(SPEC));
        ArrayNode list = root.putArray("steps");
        for (int n = 1; n <= steps; n++)
            list.addObject().put("id", "TEST-s" + n).put("title", "TEST-step-" + n).put("instruction", "TEST-instruction-" + n).put("doneWhen", "TEST-done-" + n);
        return root.toString();
    }

    private static RunResult.RunWorkArtifact artifact(RunCommand.ExecuteWorkDraftRun command, String text) {
        return new RunResult.RunWorkArtifact(command.runId(), command.draft(), text, false, Map.of("INPUT", 10L), 3);
    }

    /** A generated item of {@code steps} steps, written, approved, registered, and its first step dispatched. */
    private String building(int number, int steps) throws Exception {
        String id = started("autonomous", number);
        saga.on(artifact(dispatched(), SPEC));
        draftSweep.sweep();
        saga.on(artifact(dispatched(), plan(steps)));
        assertState(store.load(id), "build", "active", "phase_started");
        dispatcher.drain();
        return id;
    }

    private RunCommand.ExecuteWorkRun last() {
        return heldCommands.getLast();
    }

    private String base(String id) {
        return store.load(id).preparation().baseCommit();
    }

    /** What the run worker reports when the step's build reached {@code head}. */
    private static RunResult.RunWorkReady ready(RunCommand.ExecuteWorkRun command, String head) {
        return new RunResult.RunWorkReady(command.runId(), command.work(), head, List.of("TEST-task.txt"), Map.of("INPUT", 1L), 9)
                .withStepSummary(new StepSummary(List.of("TEST-decision"), List.of(), List.of()))
                .withStartFromHead(command.execution().startFromHead());
    }

    /** A step started from the base whose agent committed nothing: the held publisher's empty checkpoint at the base. */
    private RunResult.RunWorkReady emptyFromBase(String id, RunCommand.ExecuteWorkRun command) {
        return new RunResult.RunWorkReady(command.runId(), command.work(), base(id), List.of(), Map.of("INPUT", 1L), 9).withEmpty(true);
    }

    private void passStep(String id, String head) {
        saga.on(ready(last(), head));
        assertEquals("verify", store.load(id).phase());
        verifier.drain();
        verifyResults.apply(passed(verifies.getLast()));
        dispatcher.drain();
    }

    private long milestones(String id, String milestone) {
        return store.history(id).stream().map(EventEnvelope::payload).map(WorkItemEvent.class::cast)
                .filter(event -> milestone.equals(event.milestone())).count();
    }

    @Test
    void everyStepEmptyDeliversNothing() throws Exception {
        String id = building(301, 3);
        for (int step = 0; step < 3; step++) {
            saga.on(emptyFromBase(id, last()));
            dispatcher.drain();
        }

        assertState(store.load(id), "build", "awaiting_input", "plan_produced_no_change");
        assertEquals(1, milestones(id, "PLAN_PRODUCED_NO_CHANGE"));
        assertEquals(3, heldCommands.size(), "nothing is built after the last step");
        assertEquals(0, count("SELECT count(*) FROM work_verify_effect WHERE work_item_id=?", id), "an empty step is never verified");
        assertEquals(0, count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=?", id), "the unchanged base is never delivered");
        assertEquals(409, transitions.resume(id, store.history(id).size(), false).status(), "a recheck builds nothing more; Readmit or Stop");
    }

    @Test
    void deliverUsesTheLastNonEmptyStep() throws Exception {
        String id = building(302, 3);
        passStep(id, "1".repeat(40));
        passStep(id, "2".repeat(40));
        WorkExecution second = store.load(id).steps().completed().get(1).execution();
        RunCommand.ExecuteWorkRun third = last();

        // An idle checkpoint-started agent still bundles its start: the orchestrator sees the step's start head.
        saga.on(ready(third, "2".repeat(40)));

        WorkItemEvent item = store.load(id);
        assertEquals("deliver", item.phase(), item.reason());
        assertEquals(third.runId(), item.progress().execution().runId(), "the newest run holding a bundle publishes the chain");
        assertEquals("2".repeat(40), item.progress().execution().head());
        assertEquals(second.verificationAttempt(), item.progress().execution().verificationAttempt());
        assertEquals(second.verification(), item.progress().execution().verification(), "step 2's passed verification");
        assertEquals(1, count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=? AND run_id=?", id, third.runId()));
        assertEquals(2, count("SELECT count(*) FROM work_verify_effect WHERE work_item_id=?", id), "the empty step was not verified");
        assertEquals(1, milestones(id, "STEP_EMPTY"));
    }

    @Test
    void anEmptyFirstStepStartsTheNextFromTheBase() throws Exception {
        String id = building(303, 3);

        saga.on(emptyFromBase(id, last()));
        dispatcher.drain();

        RunCommand.ExecuteWorkRun second = last();
        assertEquals(2, heldCommands.size());
        assertNull(second.execution().startFromRunId(), "the empty first run holds no bundle to start from");
        assertFalse(second.checkpointRequired());
        assertTrue(second.emptyAllowed());
        assertEquals("build", store.load(id).phase());
        assertEquals(1, store.load(id).steps().index());
    }

    @Test
    void anEmptyLaterStepChainsThroughItsOwnRun() throws Exception {
        String id = building(304, 3);
        passStep(id, "1".repeat(40));
        RunCommand.ExecuteWorkRun second = last();

        saga.on(ready(second, "1".repeat(40)));
        dispatcher.drain();

        RunCommand.ExecuteWorkRun third = last();
        assertNotEquals(second.runId(), third.runId());
        assertEquals(second.runId(), third.execution().startFromRunId(), "step 1's run was retired once step 2 was ready");
        assertEquals("1".repeat(40), third.execution().startFromHead());
        assertTrue(third.checkpointRequired());
        assertEquals(1, count("SELECT count(*) FROM work_verify_effect WHERE work_item_id=?", id), "only step 1 was verified");
    }

    @Test
    void aStoredReadyKeepsItsEmptyFlag() throws Exception {
        String id = building(305, 3);
        RunResult.RunWorkReady empty = emptyFromBase(id, last());
        runs.apply(empty);
        charges.record(empty);
        assertTrue(bridge.record(empty));

        // As after a restart: the stored payload is applied, twice, and the step completes once.
        bridge.recover();
        bridge.recover();

        assertEquals(1, milestones(id, "STEP_EMPTY"));
        assertEquals(1, store.load(id).steps().index());
        assertEquals(0, count("SELECT count(*) FROM work_verify_effect WHERE work_item_id=?", id));
    }

    @Test
    void anEmptyReadyOffItsStartIsRefused() throws Exception {
        String id = building(306, 3);
        RunCommand.ExecuteWorkRun first = last();

        saga.on(new RunResult.RunWorkReady(first.runId(), first.work(), "9".repeat(40), List.of(), Map.of("INPUT", 1L), 9).withEmpty(true));

        assertState(store.load(id), "build", "failed", "phase_failed");
        assertEquals(1, count("SELECT count(*) FROM work_run_effect WHERE run_id=? AND reason='phase_evidence_mismatch'", first.runId()));
        assertEquals(0, count("SELECT count(*) FROM work_verify_effect WHERE work_item_id=?", id));
    }

    @Test
    void theRunAndTheStepsViewNameEachStep() throws Exception {
        String id = building(307, 3);
        passStep(id, "1".repeat(40));
        RunCommand.ExecuteWorkRun second = last();
        saga.on(ready(second, "1".repeat(40)));
        dispatcher.drain();

        FactoryRunProjection.RunPublication publication = runs.find(second.runId()).orElseThrow().publication();
        assertEquals(1, publication.stepIndex(), "run detail says Plan step 2");
        assertEquals(3, publication.stepTotal(), "of 3");
        List<WorkStepsResource.Row> rows = stepsView.read(id).rows();
        assertEquals(List.of("TEST-step-1", "TEST-step-2", "TEST-step-3"), rows.stream().map(WorkStepsResource.Row::title).toList());
        assertEquals(List.of("passed", "empty", "building"), rows.stream().map(WorkStepsResource.Row::state).toList());
        assertEquals("1".repeat(40), rows.getFirst().head());
        assertEquals(List.of("TEST-decision"), rows.getFirst().summary().decisions());
    }
}
