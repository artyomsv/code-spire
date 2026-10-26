package dev.codespire.contract.work;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The step loop of a binding-6 build (M4 slice 3, §6.6, A17, decisions 7, 9, 12 and 13): a passed step builds the next
 * one or delivers, a failed later step opens the verify gate, a land fix is no step, and no gate opens between steps.
 */
class WorkStepLifecycleTest {
    static final String BASE = "a".repeat(40);
    static final WorkPolicyLimits LIMITS = new WorkPolicyLimits(60, 5, 20, 7200, 2_000_000, 40, Set.of());
    final WorkItemEvent.Authority authority = new WorkItemEvent.Authority(UUID.randomUUID(), 1, 1, 1);

    static WorkPreparation preparation(int binding) {
        return new WorkPreparation(WorkDraftTest.stored('d'), WorkDraftTest.stored('e'), "main", BASE, "codex", "TEST-model",
                "system:draft@1", binding, null, null, List.of("TEST-check"), 60);
    }

    WorkItemEvent item(String build, String verify, WorkSteps steps) {
        Map<WorkPolicy.Phase, String> modes = new EnumMap<>(WorkPolicy.Phase.class);
        for (WorkPolicy.Phase phase : WorkPolicy.Phase.values()) modes.put(phase, switch (phase) {
            case DELIVER -> "pr"; case LAND -> "auto_if_green"; case BUILD -> build; case VERIFY -> verify; default -> "auto";
        });
        WorkPolicy.Profile profile = new WorkPolicy.Profile(UUID.randomUUID(), "TEST-steps", 1, 1, modes, LIMITS);
        WorkPolicy.Selection selection = new WorkPolicy.Selection(profile, profile.modes(), List.of(), "policy_selected", List.of(), profile, profile.limits());
        return new WorkItemEvent("TEST-item", UUID.randomUUID(), UUID.randomUUID(), WorkDraftTest.ISSUE, 1, 1, profile, profile.modes(), authority,
                selection, "build", "awaiting_input", "artifacts_registered").prepared(preparation(WorkPreparation.STEPS_BINDING)).stepped(steps);
    }

    static WorkItemEvent at(WorkItemEvent item, String phase, String status, String reason, WorkProgress progress) {
        return item.decision(item.policyRevision(), item.authority(), item.policy(), phase, status, reason, "TEST", null, progress);
    }

    /** The item as a passed verify of the cursor's current step leaves it, before the step loop decides. */
    WorkItemEvent verifyPassed(WorkItemEvent item, String run, String head) {
        WorkProgress progress = new WorkProgress(UUID.randomUUID(), "verify", "completed", false, Instant.EPOCH, 3, 1, 0, 0, 0)
                .withExecution(WorkStepsTest.passed(run, head));
        return at(item, "verify", "awaiting_input", "phase_completed", progress);
    }

    /** The cursor's current step building, with its run's attempt started. */
    static WorkItemEvent building(WorkItemEvent item) {
        return at(item, "build", "active", "phase_started", new WorkProgress(UUID.randomUUID(), "build", "started", true, Instant.EPOCH, 2, 1, 0, 0, 0));
    }

    static WorkSteps completed(int total, int done) {
        WorkSteps steps = WorkSteps.of(total);
        for (int i = 0; i < done; i++) steps = steps.completedWith(WorkStepsTest.step(i, "TEST-run-" + (i + 1), Integer.toString(i + 1).repeat(40)));
        return steps;
    }

    static WorkStepLifecycle.RunFailure failure(UUID attempt, boolean checkpointMissing, String lostRun) {
        return new WorkStepLifecycle.RunFailure(attempt, checkpointMissing, lostRun, new WorkDraftLifecycle.RunUsage(5, 0, 1, true));
    }

    static WorkItemEvent enter(WorkItemEvent item) {
        return WorkItemLifecycle.enter(item, item, 4, false, Instant.EPOCH, true, UUID.randomUUID(), WorkStepLifecycle.entryMode(item, false));
    }

    @Test
    void aPassedStepGoesBackToBuild() {
        WorkItemEvent passed = verifyPassed(item("auto", "auto", WorkSteps.of(3)), "TEST-run-1", "b".repeat(40));

        WorkItemEvent next = WorkStepLifecycle.next(passed);

        assertEquals("STEP_COMPLETED", next.milestone());
        assertEquals("build", next.phase());
        assertEquals(1, next.steps().index());
        assertEquals(passed.progress().steps() + 1, next.progress().steps());
        assertEquals("TEST-run-1", next.steps().chainRun().orElseThrow().execution().runId());
    }

    @Test
    void theLastPassedStepGoesToDeliver() {
        WorkItemEvent passed = verifyPassed(item("auto", "auto", completed(3, 2)), "TEST-run-3", "b".repeat(40));

        WorkItemEvent next = WorkStepLifecycle.next(passed);

        assertEquals("deliver", next.phase());
        assertEquals("STEP_COMPLETED", next.milestone());
        assertTrue(next.steps().done());
        assertEquals("TEST-run-3", next.progress().execution().runId());
        assertEquals("b".repeat(40), next.progress().execution().head());
        assertTrue(next.progress().execution().verification().passed());
    }

    @Test
    void aStepDoneCursorRefusesNext() {
        WorkItemEvent passed = verifyPassed(item("auto", "auto", completed(3, 3)), "TEST-run-4", "b".repeat(40));

        assertThrows(IllegalStateException.class, () -> WorkStepLifecycle.next(passed));
    }

    @Test
    void aStepRunFailureKeepsCompletedSteps() {
        WorkItemEvent running = building(item("auto", "auto", completed(4, 1)));
        assertTrue(WorkStepLifecycle.routesRunFailure(running, false));

        WorkItemEvent gated = WorkStepLifecycle.stepRunFailed(running, failure(running.progress().attemptId(), false, null),
                new WorkItemLifecycle.GateOpening(4, Instant.EPOCH, UUID.randomUUID()));

        assertEquals("verify / waiting_approval / verify_unverified", gated.phase() + " / " + gated.workflowStatus() + " / " + gated.reason());
        assertEquals("step_run_failed", gated.progress().execution().verification().reason());
        assertEquals("1".repeat(40), gated.progress().execution().head(), "the gate shows the head the step started from");
        assertEquals(1, gated.steps().completed().size());
        assertEquals("verify", gated.gate().phase());
        assertEquals("completed", gated.progress().attemptState(), "the failed build attempt is finished");
    }

    @Test
    void aMissingCheckpointRecordsTheLostRun() {
        WorkItemEvent running = building(item("auto", "auto", completed(4, 1)));

        WorkItemEvent gated = WorkStepLifecycle.stepRunFailed(running, failure(running.progress().attemptId(), true, "TEST-run-1"),
                new WorkItemLifecycle.GateOpening(4, Instant.EPOCH, UUID.randomUUID()));

        assertEquals("checkpoint_missing", gated.progress().execution().verification().reason());
        assertEquals(Set.of("TEST-run-1"), gated.steps().lost());
    }

    @Test
    void aFirstStepRunFailureStillFails() {
        WorkItemEvent running = building(item("auto", "auto", WorkSteps.of(3)));

        assertFalse(WorkStepLifecycle.routesRunFailure(running, false));
        assertThrows(IllegalStateException.class, () -> WorkStepLifecycle.stepRunFailed(running, failure(running.progress().attemptId(), false, null),
                new WorkItemLifecycle.GateOpening(4, Instant.EPOCH, UUID.randomUUID())));
    }

    @Test
    void aStepRunFailureAfterOnlyEmptyBaseStepsDoesNotWedge() {
        WorkExecution emptyFromBase = new WorkExecution("TEST-run-1", new WorkRunBinding("TEST-item", 1, UUID.randomUUID(), "c".repeat(64)),
                BASE, null, null, null, null);
        WorkSteps steps = WorkSteps.of(3).completedWith(new WorkSteps.StepResult(0, emptyFromBase, true, false));
        WorkItemEvent running = building(item("auto", "auto", steps));

        assertFalse(WorkStepLifecycle.routesRunFailure(running, false), "no run holds a bundle, so there is nothing to put under a gate");
    }

    @Test
    void aLandFixAfterAPlanDeliversTheFixHead() {
        WorkItemEvent fixPassed = verifyPassed(item("auto", "auto", completed(3, 3)), "TEST-fix-run", "f".repeat(40));

        assertFalse(WorkStepLifecycle.asks(fixPassed, true), "a land fix completes no step");
        assertFalse(WorkStepLifecycle.asks(fixPassed, false), "a finished cursor completes no step either");
        assertEquals(WorkItemLifecycle.EntryMode.LAND_FIX, WorkStepLifecycle.entryMode(fixPassed, true));
        assertEquals(fixPassed.progress(), WorkStepLifecycle.composedStepCompleted(fixPassed, fixPassed.progress(), true));
        assertEquals(fixPassed.progress(), WorkStepLifecycle.composedStepCompleted(fixPassed, fixPassed.progress(), false),
                "an item with a cursor counts its steps through the loop, never as one composed step");
    }

    @Test
    void aLaterStepOpensNoPermissionGateUnderBuildApprove() {
        WorkItemEvent passed = verifyPassed(item("approve", "approve", WorkSteps.of(3)), "TEST-run-1", "b".repeat(40));
        WorkItemEvent stepped = WorkStepLifecycle.next(passed);

        WorkItemEvent built = enter(stepped);

        assertEquals("PHASE_STARTED", built.milestone());
        assertEquals("build", built.phase());
        assertNull(built.gate());
        WorkItemEvent ready = at(built, "verify", "awaiting_input", "phase_completed",
                built.progress().finish(1, 0, 1).withExecution(new WorkExecution("TEST-run-2",
                        new WorkRunBinding("TEST-item", 1, built.progress().attemptId(), "c".repeat(64)), "c".repeat(40), null, null, null, null)));
        WorkItemEvent verifying = enter(ready);
        assertEquals("verify / active / phase_started", verifying.phase() + " / " + verifying.workflowStatus() + " / " + verifying.reason());
        assertNull(verifying.gate());
    }

    @Test
    void theFirstStepStillAsksUnderBuildApprove() {
        WorkItemEvent registered = item("approve", "auto", WorkSteps.of(3));

        WorkItemEvent entered = enter(registered);

        assertEquals("waiting_approval / approval_required", entered.workflowStatus() + " / " + entered.reason());
    }

    @Test
    void aComposedItemCountsItsOneStep() {
        WorkItemEvent composed = verifyPassed(item("auto", "auto", null), "TEST-run-1", "b".repeat(40));

        assertEquals(composed.progress().steps() + 1, WorkStepLifecycle.composedStepCompleted(composed, composed.progress(), false).steps());
        assertEquals(composed.progress().steps(), WorkStepLifecycle.composedStepCompleted(composed, composed.progress(), true).steps(),
                "a land fix completes no step");
    }

    /** The item as its current step's build left it: ready at {@code head}, before the step loop decides (§6.7). */
    static WorkItemEvent buildReady(WorkItemEvent item, String run, String head) {
        WorkProgress progress = new WorkProgress(UUID.randomUUID(), "build", "completed", false, Instant.EPOCH, 3, item.steps().index(), 0, 0, 0)
                .withExecution(new WorkExecution(run, new WorkRunBinding("TEST-item", 1, UUID.randomUUID(), "c".repeat(64)), head, null, null, null, null));
        return at(item, "build", "awaiting_input", "phase_completed", progress);
    }

    static WorkSteps withEmpty(WorkSteps steps, String run, String head, boolean holdsBundle) {
        WorkExecution execution = new WorkExecution(run, new WorkRunBinding("TEST-item", 1, UUID.randomUUID(), "c".repeat(64)), head, null, null, null, null);
        return steps.completedWith(new WorkSteps.StepResult(steps.index(), execution, true, holdsBundle));
    }

    @Test
    void anIdleLaterStepIsEmpty() {
        WorkItemEvent ready = buildReady(item("auto", "auto", completed(3, 1)), "TEST-run-2", "1".repeat(40));

        WorkItemEvent next = WorkStepLifecycle.next(ready);

        assertNotNull(next, "a head equal to the step's start is an empty step, decided by the step loop");
        assertEquals("STEP_EMPTY", next.milestone());
        assertEquals("build", next.phase(), "verify is skipped: the next step builds");
        assertEquals(2, next.steps().index());
        WorkSteps.StepResult empty = next.steps().completed().get(1);
        assertTrue(empty.empty());
        assertTrue(empty.holdsBundle(), "a checkpoint-started run holds the chain, so the next step starts from it");
        assertEquals("TEST-run-2", next.steps().chainRun().orElseThrow().execution().runId());
        assertEquals(ready.progress().steps() + 1, next.progress().steps(), "an empty step completes and counts");
    }

    @Test
    void anEmptyStepSkipsVerify() {
        WorkItemEvent ready = buildReady(item("auto", "auto", WorkSteps.of(3)), "TEST-run-1", BASE);

        WorkItemEvent next = WorkStepLifecycle.next(ready);

        assertNotNull(next);
        assertEquals("build / awaiting_input / STEP_EMPTY", next.phase() + " / " + next.workflowStatus() + " / " + next.milestone());
        assertFalse(next.steps().completed().getFirst().holdsBundle(), "a base-started empty run holds nothing");
        assertTrue(next.steps().chainRun().isEmpty(), "so the next step starts from the base");
    }

    @Test
    void aStepThatCommittedStillVerifies() {
        assertNull(WorkStepLifecycle.next(buildReady(item("auto", "auto", completed(3, 1)), "TEST-run-2", "b".repeat(40))));
    }

    @Test
    void everyStepEmptyProducesNoChange() {
        WorkSteps twoEmpty = withEmpty(withEmpty(WorkSteps.of(3), "TEST-run-1", BASE, false), "TEST-run-2", BASE, false);

        WorkItemEvent next = WorkStepLifecycle.next(buildReady(item("auto", "auto", twoEmpty), "TEST-run-3", BASE));

        assertEquals("build / awaiting_input / plan_produced_no_change", next.phase() + " / " + next.workflowStatus() + " / " + next.reason());
        assertEquals("PLAN_PRODUCED_NO_CHANGE", next.milestone());
        assertTrue(next.steps().done());
    }

    @Test
    void anEmptyLastStepDeliversTheLastChange() {
        WorkItemEvent next = WorkStepLifecycle.next(buildReady(item("auto", "auto", completed(3, 2)), "TEST-run-3", "2".repeat(40)));

        assertEquals("deliver", next.phase());
        WorkExecution delivered = next.progress().execution();
        assertEquals("TEST-run-3", delivered.runId(), "the newest run holding a bundle publishes the chain");
        assertEquals("2".repeat(40), delivered.head(), "the last change, never the empty step's own");
        assertTrue(delivered.verification().passed(), "with that change's passed verification");
    }

    @Test
    void anEmptyReadyIsAcceptedOnlyOnAStepsStart() {
        WorkItemEvent building = building(item("auto", "auto", completed(3, 1)));

        assertTrue(WorkStepLifecycle.acceptsEmpty(building, false, "1".repeat(40)));
        assertFalse(WorkStepLifecycle.acceptsEmpty(building, false, "b".repeat(40)), "an empty ready off the step's start");
        assertFalse(WorkStepLifecycle.acceptsEmpty(building, true, "1".repeat(40)), "a land fix is never empty");
        assertFalse(WorkStepLifecycle.acceptsEmpty(building(item("auto", "auto", null)), false, BASE), "a composed build is never empty");
        assertFalse(WorkStepLifecycle.acceptsEmpty(building(item("auto", "auto", completed(3, 3))), false, "3".repeat(40)), "a finished cursor");
    }

    @Test
    void onlyABindingSixPlanGetsACursor() {
        WorkItemEvent stepsPlan = item("auto", "auto", null);
        WorkItemEvent composed = stepsPlan.prepared(preparation(WorkPreparation.VERIFY_BINDING));

        assertEquals(WorkSteps.of(3), WorkStepLifecycle.planRegistered(stepsPlan, 3).steps());
        assertNull(WorkStepLifecycle.planRegistered(composed, 3).steps());
    }
}
