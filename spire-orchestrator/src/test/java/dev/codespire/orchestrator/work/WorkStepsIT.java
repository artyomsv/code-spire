package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.StepSummary;
import dev.codespire.contract.work.WorkGate;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPolicy;
import dev.codespire.contract.work.WorkPolicyLimits;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.orchestrator.factory.BuildDefaults;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunCharges;
import dev.codespire.orchestrator.factory.RunResultSaga;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A plan built step by step on one chain (M4 slice 3, §6.6, A2, A11, decisions 7, 9, 11 and 12): each step starts from
 * the previous step's checkpoint and must, its prompt carries every earlier decision, a lost checkpoint opens the verify
 * gate and never falls back to the base, and steps are counted when they complete. Only the buses are TEST boundaries:
 * the run worker is played by the results each test delivers.
 */
@QuarkusTest
@TestProfile(GeneratedPreparationProfile.class)
@TestSecurity(user = "TEST-spec-admin", roles = "spire-admin")
class WorkStepsIT extends WorkSpecRunFixture {
    static final String SPEC = WorkSpecRunIT.SPEC;

    @Inject RunResultSaga saga;
    @Inject WorkItemRunBridge bridge;
    @Inject FactoryRunProjection runs;
    @Inject RunCharges charges;

    @BeforeEach
    void stepsSetup() {
        // Verify must be able to pass, so the setup declares the fixture's check.
        defaults.save(repository, new BuildDefaults.Input(defaults.get(repository).revision(), "main", "codex", model, null, null,
                CHECKS, null, BuildDefaults.GENERATED), "TEST-spec-admin");
        twoRunsProfile();
    }

    /** The fixture's profiles, plus TEST-two-runs: autonomous, but at most two build runs per item. */
    private void twoRunsProfile() {
        Map<WorkPolicy.Phase, String> modes = new EnumMap<>(WorkPolicy.Phase.class);
        for (WorkPolicy.Phase phase : WorkPolicy.Phase.values()) modes.put(phase, switch (phase) {
            case DELIVER -> "pr"; case LAND -> "auto_if_green"; default -> "auto";
        });
        UUID id = UUID.randomUUID();
        extraProfiles.add(id);
        WorkPolicy.Profile twoRuns = policies.createVersion(new WorkPolicy.Profile(id, "TEST-two-runs-" + id, 1,
                1_700_000_000 + Math.abs(id.hashCode() % 100_000_000), modes,
                new WorkPolicyLimits(3600, 2, 20, 7200, 2_000_000, 40, Set.of("TEST-sensitive/**"))));
        Map<String, WorkPolicyRegistry.Pin> mapping = new HashMap<>();
        profiles.forEach((name, profile) -> mapping.put("TEST-" + name, new WorkPolicyRegistry.Pin(profile.id(), 1)));
        mapping.put("TEST-two-runs", new WorkPolicyRegistry.Pin(twoRuns.id(), 1));
        policies.save(repository, new WorkPolicyRegistry.Input(policies.get(repository).revision(),
                new WorkPolicyRegistry.Pin(profiles.get("autonomous").id(), 1), mapping));
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
    private String building(String profile, int number, int steps) throws Exception {
        String id = started(profile, number);
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

    /** What the run worker reports when the step's build reached {@code head}, with the step's decision. */
    private RunResult.RunWorkReady ready(RunCommand.ExecuteWorkRun command, String head, String decision) {
        return new RunResult.RunWorkReady(command.runId(), command.work(), head, List.of("TEST-task.txt"), Map.of("INPUT", 1L), 9)
                .withStepSummary(new StepSummary(List.of(decision), List.of(), List.of()))
                .withStartFromHead(command.execution().startFromHead());
    }

    /** The current step's build reaches {@code head}, its verify passes, and the next step (if any) is dispatched. */
    private void passStep(String id, String head, String decision) {
        saga.on(ready(last(), head, decision));
        assertEquals("verify", store.load(id).phase());
        verifier.drain();
        verifyResults.apply(passed(verifies.getLast()));
        dispatcher.drain();
    }

    private void failVerify(String id, String head, String decision) {
        saga.on(ready(last(), head, decision));
        verifier.drain();
        verifyResults.apply(failed(verifies.getLast()));
        assertState(store.load(id), "verify", "waiting_approval", "verify_failed");
    }

    private WorkItemTransitions.Outcome retry(String id) {
        WorkGate gate = store.load(id).gate();
        return transitions.answer(gate.id(), gate.version(), "TEST-retry-" + UUID.randomUUID(), true, "TEST retry", "TEST-spec-admin");
    }

    private static RunResult.RunFailed runFailed(RunCommand.ExecuteWorkRun command, String cause) {
        return new RunResult.RunFailed(command.runId(), cause, "TEST " + cause, false, null);
    }

    private long milestones(String id, String milestone) {
        return store.history(id).stream().map(EventEnvelope::payload).map(WorkItemEvent.class::cast)
                .filter(event -> milestone.equals(event.milestone())).count();
    }

    private List<Integer> integers(String sql, String id) throws Exception {
        List<Integer> values = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) values.add(rs.getInt(1)); }
        }
        return values;
    }

    @Test
    void threeStepsBuildOnOneChain() throws Exception {
        String id = building("autonomous", 201, 3);
        RunCommand.ExecuteWorkRun first = last();
        passStep(id, "1".repeat(40), "TEST-decision-1");
        RunCommand.ExecuteWorkRun second = last();
        passStep(id, "2".repeat(40), "TEST-decision-2");
        RunCommand.ExecuteWorkRun third = last();
        passStep(id, "3".repeat(40), "TEST-decision-3");

        assertEquals(3, heldCommands.size());
        assertNull(first.execution().startFromRunId(), "step 1 starts from the base");
        assertFalse(first.checkpointRequired());
        assertEquals(first.runId(), second.execution().startFromRunId());
        assertEquals("1".repeat(40), second.execution().startFromHead());
        assertTrue(second.checkpointRequired(), "a later step never falls back to the base");
        assertEquals(second.runId(), third.execution().startFromRunId());
        assertEquals("2".repeat(40), third.execution().startFromHead());
        assertTrue(first.emptyAllowed() && second.emptyAllowed() && third.emptyAllowed(), "every plan step may finish empty");
        WorkItemEvent item = store.load(id);
        assertEquals("deliver", item.phase());
        assertEquals(third.runId(), item.progress().execution().runId());
        assertEquals("3".repeat(40), item.progress().execution().head());
        assertEquals(3, item.progress().steps());
        assertEquals(1, count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=? AND run_id=?", id, third.runId()));
        assertEquals(3, milestones(id, "STEP_COMPLETED"));
    }

    @Test
    void eachStepRunRecordsItsIndex() throws Exception {
        String id = building("autonomous", 202, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");
        passStep(id, "2".repeat(40), "TEST-decision-2");
        passStep(id, "3".repeat(40), "TEST-decision-3");

        assertEquals(List.of(0, 1, 2), integers("SELECT step_index FROM factory_run WHERE work_item_id=? AND kind='BUILD' ORDER BY step_index", id));
        assertEquals(List.of(0, 1, 2), integers("SELECT step_index FROM work_step_summary WHERE work_item_id=? ORDER BY step_index", id));
    }

    @Test
    void theStepProjectionFollowsTheCursor() throws Exception {
        String id = building("autonomous", 203, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");

        assertEquals(List.of(1), integers("SELECT step_index FROM work_item WHERE id=?", id));
        assertEquals(List.of(3), integers("SELECT step_total FROM work_item WHERE id=?", id));
    }

    @Test
    void theOrchestratorRefusesAHeadOffTheChain() throws Exception {
        String id = building("autonomous", 204, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");
        RunCommand.ExecuteWorkRun second = last();

        saga.on(ready(second, "2".repeat(40), "TEST-decision-2").withStartFromHead("e".repeat(40)));

        assertState(store.load(id), "build", "active", "phase_started");
        assertEquals(1, count("SELECT count(*) FROM work_run_effect WHERE run_id=? AND reason='phase_evidence_mismatch'", second.runId()));
        assertEquals(1, count("SELECT count(*) FROM work_verify_effect WHERE work_item_id=?", id), "only step 1 was verified");
    }

    @Test
    void aMissingCheckpointOpensTheVerifyGate() throws Exception {
        String id = building("autonomous", 205, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");

        saga.on(runFailed(last(), "CHECKPOINT_MISSING"));

        WorkItemEvent item = store.load(id);
        assertState(item, "verify", "waiting_approval", "verify_unverified");
        assertEquals("checkpoint_missing", item.progress().execution().verification().reason());
        assertEquals(1, item.steps().completed().size(), "the completed step is kept");
    }

    @Test
    void retryingAStepKeepsEarlierSummariesAndCountsOnce() throws Exception {
        String id = building("autonomous", 206, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");
        assertEquals(1, store.load(id).progress().steps());
        RunCommand.ExecuteWorkRun failing = last();
        failVerify(id, "2".repeat(40), "TEST-decision-2-first-try");

        assertEquals(200, retry(id).status());
        dispatcher.drain();

        RunCommand.ExecuteWorkRun retried = last();
        assertNotEquals(failing.runId(), retried.runId());
        assertEquals(failing.runId(), retried.execution().startFromRunId(), "the retry starts from this step's failed checkpoint");
        assertEquals("2".repeat(40), retried.execution().startFromHead());
        assertTrue(retried.execution().prompt().contains("TEST-decision-1"), "step 1's decision is still in the prompt");
        assertTrue(retried.execution().prompt().contains("did not pass verification"), "the failure is in the prompt");
        assertEquals(1, store.load(id).progress().steps(), "a retry counts no step");
        passStep(id, "4".repeat(40), "TEST-decision-2");
        assertEquals(2, store.load(id).progress().steps());
    }

    @Test
    void aStepRunCountsTowardMaxRuns() throws Exception {
        String id = building("two-runs", 207, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");
        passStep(id, "2".repeat(40), "TEST-decision-2");

        assertState(store.load(id), "build", "stopped", "policy_cap_reached");
        assertEquals(2, heldCommands.size());
    }

    @Test
    void aNewStepIgnoresAnEarlierStepsFailedVerify() throws Exception {
        String id = building("autonomous", 208, 3);
        failVerify(id, "1".repeat(40), "TEST-decision-1-first-try");
        assertEquals(200, retry(id).status());
        dispatcher.drain();
        RunCommand.ExecuteWorkRun retried = last();
        passStep(id, "2".repeat(40), "TEST-decision-1");

        RunCommand.ExecuteWorkRun next = last();
        assertNotEquals(retried.runId(), next.runId());
        assertEquals(retried.runId(), next.execution().startFromRunId(), "step 2 starts from step 1's passed run");
        assertEquals("2".repeat(40), next.execution().startFromHead());
        assertFalse(next.execution().prompt().contains("did not pass verification"), "an earlier step's failure is not this step's");
    }

    @Test
    void aLostCheckpointAfterReadyRefusesRetry() throws Exception {
        String id = building("autonomous", 209, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");
        failVerify(id, "2".repeat(40), "TEST-decision-2");
        assertEquals(200, retry(id).status());
        dispatcher.drain();
        RunCommand.ExecuteWorkRun retried = last();
        assertTrue(retried.checkpointRequired());
        saga.on(runFailed(retried, "CHECKPOINT_MISSING"));
        assertState(store.load(id), "verify", "waiting_approval", "verify_unverified");
        long effects = count("SELECT count(*) FROM work_run_effect WHERE work_item_id=?", id);

        WorkItemTransitions.Outcome refused = retry(id);

        assertEquals(409, refused.status());
        assertEquals("step_checkpoint_lost", refused.reason());
        assertEquals("OPEN", store.load(id).gate().state(), "Stop stays open");
        assertEquals(effects, count("SELECT count(*) FROM work_run_effect WHERE work_item_id=?", id), "nothing is dispatched");
    }

    @Test
    void aFailedRunBeforeReadyRestartsFromTheChainRun() throws Exception {
        String id = building("autonomous", 210, 3);
        RunCommand.ExecuteWorkRun first = last();
        passStep(id, "1".repeat(40), "TEST-decision-1");
        saga.on(runFailed(last(), "AGENT_FAILED"));
        WorkItemEvent gated = store.load(id);
        assertState(gated, "verify", "waiting_approval", "verify_unverified");
        assertEquals("step_run_failed", gated.progress().execution().verification().reason());

        assertEquals(200, retry(id).status());
        dispatcher.drain();

        assertEquals(first.runId(), last().execution().startFromRunId(), "the step starts again from where it started");
        assertTrue(last().checkpointRequired());
    }

    @Test
    void aLostStepZeroCheckpointRetriesFromTheBase() throws Exception {
        String id = building("autonomous", 211, 3);
        RunCommand.ExecuteWorkRun first = last();
        failVerify(id, "1".repeat(40), "TEST-decision-1");
        assertEquals(200, retry(id).status());
        dispatcher.drain();
        RunCommand.ExecuteWorkRun retried = last();
        assertEquals(first.runId(), retried.execution().startFromRunId());
        assertFalse(retried.checkpointRequired(), "step 1 starts from the base, so its own start may be the base again");

        // The run worker found the failed checkpoint gone and started from the base, as it does for any such retry.
        saga.on(new RunResult.RunWorkReady(retried.runId(), retried.work(), "5".repeat(40), List.of("TEST-task.txt"), Map.of("INPUT", 1L), 9));

        assertEquals("verify", store.load(id).phase());
        assertNotEquals("failed", store.load(id).workflowStatus());
    }

    @Test
    void aFailedStepRunRetriesFromItsStart() throws Exception {
        String id = building("autonomous", 212, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");
        RunCommand.ExecuteWorkRun second = last();
        passStep(id, "2".repeat(40), "TEST-decision-2");
        saga.on(runFailed(last(), "AGENT_TIMEOUT"));
        assertState(store.load(id), "verify", "waiting_approval", "verify_unverified");

        assertEquals(200, retry(id).status());
        dispatcher.drain();

        RunCommand.ExecuteWorkRun retried = last();
        assertEquals(second.runId(), retried.execution().startFromRunId());
        assertTrue(retried.execution().prompt().contains("TEST-decision-1"));
        assertTrue(retried.execution().prompt().contains("TEST-decision-2"));
    }

    @Test
    void aRedeliveredStepResultAdvancesTheCursorOnce() throws Exception {
        String id = building("autonomous", 213, 3);
        saga.on(ready(last(), "1".repeat(40), "TEST-decision-1"));
        verifier.drain();
        var result = passed(verifies.getLast());

        verifyResults.apply(result);
        verifyResults.apply(result);

        assertEquals(1, milestones(id, "STEP_COMPLETED"));
        assertEquals(1, store.load(id).steps().index());
        assertEquals(2, count("SELECT count(*) FROM work_run_effect WHERE work_item_id=?", id), "step 1's build and one step-2 build");
    }

    @Test
    void aRestartBetweenStepsDispatchesTheNextStepOnce() throws Exception {
        String id = building("autonomous", 214, 3);
        RunResult.RunWorkReady ready = ready(last(), "1".repeat(40), "TEST-decision-1");
        runs.apply(ready);
        charges.record(ready);
        assertTrue(bridge.record(ready));

        bridge.recover();
        bridge.recover();

        assertEquals(1, count("SELECT count(*) FROM work_verify_effect WHERE work_item_id=?", id));
        verifier.drain();
        verifier.drain();
        assertEquals(1, verifies.size());
        verifyResults.apply(passed(verifies.getLast()));
        dispatcher.drain();
        dispatcher.drain();
        assertEquals(2, heldCommands.size());
    }

    @Test
    void aCheckpointMissingBuysNothing() throws Exception {
        String id = building("autonomous", 215, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");
        long calls = store.load(id).progress().calls();

        saga.on(runFailed(last(), "CHECKPOINT_MISSING"));

        assertEquals(calls, store.load(id).progress().calls(), "a start that was gone bought no call");
        assertFalse(store.load(id).progress().usageUnknown());
    }
}
