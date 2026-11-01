package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunWorkArtifact;
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
@TestSecurity(user = "TEST-spec-admin", roles = "spire-admin")
class WorkStepsIT extends WorkSpecRunFixture {
    static final String SPEC = WorkSpecRunIT.SPEC;

    @Inject RunResultSaga saga;
    @Inject WorkItemRunBridge bridge;
    @Inject FactoryRunProjection runs;
    @Inject RunCharges charges;
    @Inject WorkStepSummaries summaries;
    @Inject dev.codespire.orchestrator.factory.WorkRunAssembly assembly;

    @BeforeEach
    void stepsSetup() {
        // Verify must be able to pass, so the setup declares the fixture's check.
        defaults.save(repository, new BuildDefaults.Input(defaults.get(repository).revision(), "main", "codex", model, null, null,
                CHECKS, null, BuildDefaults.GENERATED), "TEST-spec-admin");
        twoRunsProfile();
    }

    /**
     * The fixture's profiles, plus TEST-two-runs (autonomous, but at most two build runs per item) and
     * TEST-build-approve (autonomous, but a build waits for a person).
     */
    private void twoRunsProfile() {
        Map<String, WorkPolicyRegistry.Pin> mapping = new HashMap<>();
        profiles.forEach((name, profile) -> mapping.put("TEST-" + name, new WorkPolicyRegistry.Pin(profile.id(), 1)));
        mapping.put("TEST-two-runs", new WorkPolicyRegistry.Pin(stepsProfile("two-runs", "auto", 2).id(), 1));
        mapping.put("TEST-build-approve", new WorkPolicyRegistry.Pin(stepsProfile("build-approve", "approve", 5).id(), 1));
        policies.save(repository, new WorkPolicyRegistry.Input(policies.get(repository).revision(),
                new WorkPolicyRegistry.Pin(profiles.get("autonomous").id(), 1), mapping));
    }

    private WorkPolicy.Profile stepsProfile(String name, String build, int maxRuns) {
        Map<WorkPolicy.Phase, String> modes = new EnumMap<>(WorkPolicy.Phase.class);
        for (WorkPolicy.Phase phase : WorkPolicy.Phase.values()) modes.put(phase, switch (phase) {
            case DELIVER -> "pr"; case LAND -> "auto_if_green"; case BUILD -> build; default -> "auto";
        });
        UUID id = UUID.randomUUID();
        extraProfiles.add(id);
        return policies.createVersion(new WorkPolicy.Profile(id, "TEST-" + name + "-" + id, 1,
                1_700_000_000 + Math.abs(id.hashCode() % 100_000_000), modes,
                new WorkPolicyLimits(3600, maxRuns, 20, 7200, 2_000_000, 40, Set.of("TEST-sensitive/**"))));
    }

    private String plan(int steps) {
        ObjectNode root = mapper.createObjectNode().put("schemaVersion", 2).put("specificationSha256", WorkPreparation.digest(SPEC));
        ArrayNode list = root.putArray("steps");
        for (int n = 1; n <= steps; n++)
            list.addObject().put("id", "TEST-s" + n).put("title", "TEST-step-" + n).put("instruction", "TEST-instruction-" + n).put("doneWhen", "TEST-done-" + n);
        return root.toString();
    }

    private static RunWorkArtifact artifact(RunCommand.ExecuteWorkDraftRun command, String text) {
        return new RunWorkArtifact(command.runId(), command.draft(), text, false, Map.of("INPUT", 10L), 3, List.of());
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
        return answer(id, true);
    }

    private WorkItemTransitions.Outcome answer(String id, boolean approve) {
        WorkGate gate = store.load(id).gate();
        return transitions.answer(gate.id(), gate.version(), "TEST-answer-" + UUID.randomUUID(), approve, "TEST answer", "TEST-spec-admin");
    }

    private static RunResult.RunFailed runFailed(RunCommand.ExecuteWorkRun command, String cause) {
        return new RunResult.RunFailed(command.runId(), cause, "TEST " + cause, false, null, List.of());
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

    /**
     * The prompt each step's build is dispatched with, assembled as the dispatcher assembles it and not a ready the test
     * wrote, tells the agent where its decisions go (review of PR 287, H-1): without it the summaries the later steps
     * read could only ever come from a test.
     */
    @Test
    void everyDispatchedStepIsToldWhereItsDecisionsGo() throws Exception {
        String id = building("autonomous", 219, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");
        passStep(id, "2".repeat(40), "TEST-decision-2");

        assertEquals(3, heldCommands.size());
        for (RunCommand.ExecuteWorkRun command : heldCommands) {
            String prompt = command.execution().prompt();
            assertTrue(prompt.contains("SPIRE_STEP_SUMMARY"), prompt);
            assertTrue(prompt.contains("\"alternativesRejected\""), prompt);
            assertTrue(prompt.contains("also when you changed nothing"), prompt);
        }
    }

    /**
     * The bridge stores a step's summary after the transition that completed the step (review of PR 287, M-1). Stage the
     * crash between the two: the next step's build is pending, the summary is not stored and the ready is not
     * acknowledged. The next step is not dispatched until the replay stores the summary, so its prompt carries it.
     */
    @Test
    void theNextStepWaitsForTheSummaryOfTheStepBefore() throws Exception {
        String id = building("autonomous", 220, 3);
        RunCommand.ExecuteWorkRun first = last();
        saga.on(ready(first, "1".repeat(40), "TEST-decision-1"));
        execute("DELETE FROM work_step_summary WHERE work_item_id=?", id);
        execute("UPDATE work_run_effect SET ready_processed=false WHERE run_id=?", first.runId());
        verifier.drain();
        verifyResults.apply(passed(verifies.getLast()));
        assertEquals(1, store.load(id).steps().index());

        dispatcher.drain();
        bridge.recover();
        dispatcher.drain();

        assertEquals(2, heldCommands.size(), "step 2 is dispatched once");
        assertTrue(last().execution().prompt().contains("TEST-decision-1"), "step 2's prompt carries step 1's decision");
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

        // The step fails as a failed run does: a person decides at the verify gate, and the row leaves the recover window.
        WorkItemEvent item = store.load(id);
        assertState(item, "verify", "waiting_approval", "verify_unverified");
        assertEquals("step_run_failed", item.progress().execution().verification().reason());
        assertEquals("1".repeat(40), item.progress().execution().head(), "the gate stands on the chain's head, not the refused one");
        assertEquals(1, count("SELECT count(*) FROM work_run_effect WHERE run_id=? AND reason='phase_evidence_mismatch'", second.runId()));
        assertEquals(1, count("SELECT count(*) FROM work_run_effect WHERE run_id=? AND ready_processed", second.runId()));
        assertEquals(1, count("SELECT count(*) FROM work_verify_effect WHERE work_item_id=?", id), "only step 1 was verified");
        assertEquals(List.of(0), integers("SELECT step_index FROM work_step_summary WHERE work_item_id=? ORDER BY step_index", id),
                "a refused run's summary is not step 2's");
    }

    @Test
    void aReadmitAfterALaterStepStillAsksBeforeTheFirstBuild() throws Exception {
        String id = started("build-approve", 216);
        saga.on(artifact(dispatched(), SPEC));
        draftSweep.sweep();
        saga.on(artifact(dispatched(), plan(3)));
        assertState(store.load(id), "build", "waiting_approval", "approval_required");
        assertEquals(200, answer(id, true).status());
        dispatcher.drain();
        passStep(id, "1".repeat(40), "TEST-decision-1");
        failVerify(id, "2".repeat(40), "TEST-decision-2");
        assertEquals(200, answer(id, false).status());
        assertState(store.load(id), "verify", "stopped", "verify_stopped_by_operator");
        int dispatched = heldCommands.size();

        assertEquals(200, transitions.resume(id, store.history(id).size(), true).status());
        dispatcher.drain();

        WorkItemEvent item = store.load(id);
        assertEquals(2, item.generation());
        assertState(item, "build", "waiting_approval", "approval_required");
        assertEquals(0, item.steps().index());
        assertEquals(dispatched, heldCommands.size(), "nothing is built before a person approves");
    }

    @Test
    void aLandFixSummaryAfterAPlanDoesNotStandInForAStep() throws Exception {
        String id = building("autonomous", 217, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");
        passStep(id, "2".repeat(40), "TEST-decision-2");
        passStep(id, "3".repeat(40), "TEST-decision-3");
        // A land fix's build has no step index (factory_run.step_index NULL): it builds no step of the plan.
        RunResult.RunWorkReady fix = new RunResult.RunWorkReady("TEST-land-fix-" + UUID.randomUUID(), last().work(), "4".repeat(40),
                List.of("TEST-task.txt"), Map.of("INPUT", 1L), 9).withStepSummary(new StepSummary(List.of("TEST-fix-decision"), List.of(), List.of()));

        try (Connection c = dataSource.getConnection()) {
            summaries.recordBuilt(c, fix, store.load(id));
        }

        List<WorkStepSummaries.Step> steps = summaries.list(id, store.load(id).generation());
        assertEquals(List.of(0, 1, 2), steps.stream().map(WorkStepSummaries.Step::stepIndex).toList());
        assertEquals(List.of("TEST-decision-1"), steps.getFirst().summary().decisions(), "step 1 keeps its own decision");
    }

    /**
     * A land fix after a delivered plan is no plan step (decision 12): its prompt says the whole plan is already built on
     * the branch and lists every step, never "this one prepared task" with step 1 as its only step, and the generated
     * specification is quoted as data (A13).
     */
    @Test
    void aLandFixAfterAPlanIsToldTheWholePlanIsBuilt() throws Exception {
        String id = building("autonomous", 218, 3);
        passStep(id, "1".repeat(40), "TEST-decision-1");
        passStep(id, "2".repeat(40), "TEST-decision-2");
        passStep(id, "3".repeat(40), "TEST-decision-3");
        WorkItemEvent item = store.load(id);
        assertTrue(item.steps().done());
        WorkItemTransitions.Observation observed = transitions.observe(item);
        WorkLandFixes.Fix fix = new WorkLandFixes.Fix(UUID.randomUUID(), "land_checks_failed", "3".repeat(40),
                new dev.codespire.contract.scm.PullRequestRef(918, "https://forge.example.test/TEST-pull/918", false), "spire/work-TEST-fix",
                "main", null, "TEST-fix-instruction\n", Set.of());

        String prompt;
        try (Connection c = dataSource.getConnection()) {
            prompt = assembly.assemble(c, observed.source(), item, observed.artifacts(), fix).command().execution().prompt();
        }

        assertFalse(prompt.contains("Single plan step"), prompt);
        for (int n = 1; n <= 3; n++) assertTrue(prompt.contains("TEST-step-" + n), prompt);
        assertTrue(prompt.contains("TEST-fix-instruction"), prompt);
        assertTrue(prompt.contains(UntrustedText.DATA_HEADING + " The approved specification"), prompt);
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

    /**
     * A plan is accepted only within the runs left (A8, A11), so a 2-step plan fits two runs; a verify retry spends the
     * second, and the next step's build is refused at the cap.
     */
    @Test
    void aStepRunCountsTowardMaxRuns() throws Exception {
        String id = building("two-runs", 207, 2);
        failVerify(id, "1".repeat(40), "TEST-decision-1-first-try");
        assertEquals(200, retry(id).status());
        dispatcher.drain();
        passStep(id, "2".repeat(40), "TEST-decision-1");

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
