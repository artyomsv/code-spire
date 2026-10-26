package dev.codespire.contract.work;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The plan cursor (M4 slice 3, §6.6, A17, decision 9): consecutive, bounded, and naming where the next step starts. */
class WorkStepsTest {
    static final String BASE = "a".repeat(40);

    /** A step run's execution verified PASSED at {@code head}. */
    static WorkExecution passed(String run, String head) {
        UUID attempt = UUID.randomUUID();
        WorkVerification verification = new WorkVerification(attempt, head, WorkVerification.Outcome.PASSED, null,
                List.of(new WorkVerification.CheckResult("TEST-check", 0, 1, "")));
        return new WorkExecution(run, new WorkRunBinding("TEST-item", 1, UUID.randomUUID(), "c".repeat(64)), head, null, null, null, null)
                .verified(verification);
    }

    static WorkSteps.StepResult step(int index, String run, String head) {
        return new WorkSteps.StepResult(index, passed(run, head), false, true);
    }

    @Test
    void indicesStayConsecutive() {
        WorkSteps steps = WorkSteps.of(3);

        assertThrows(IllegalArgumentException.class, () -> steps.completedWith(step(1, "TEST-run-2", "b".repeat(40))));
        assertThrows(IllegalArgumentException.class, () -> new WorkSteps(3, 1, List.of(step(1, "TEST-run-2", "b".repeat(40))), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorkSteps(3, 2, List.of(step(0, "TEST-run-1", "b".repeat(40))), Set.of()));
    }

    @Test
    void aPlanHasOneToFiftySteps() {
        assertThrows(IllegalArgumentException.class, () -> WorkSteps.of(0));
        assertThrows(IllegalArgumentException.class, () -> WorkSteps.of(WorkSteps.MAX_STEPS + 1));
        assertEquals(WorkSteps.MAX_STEPS, WorkSteps.of(WorkSteps.MAX_STEPS).total());
    }

    @Test
    void aStepThatChangedSomethingNeedsAPassedVerification() {
        WorkExecution unverified = new WorkExecution("TEST-run-1", new WorkRunBinding("TEST-item", 1, UUID.randomUUID(), "c".repeat(64)),
                "b".repeat(40), null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> new WorkSteps.StepResult(0, unverified, false, true));
    }

    @Test
    void theNextStepStartsFromTheNewestRunThatHoldsABundle() {
        WorkSteps steps = WorkSteps.of(3).completedWith(step(0, "TEST-run-1", "b".repeat(40))).completedWith(step(1, "TEST-run-2", "c".repeat(40)));

        assertEquals("TEST-run-2", steps.chainRun().orElseThrow().execution().runId());
        assertEquals("TEST-run-2", steps.previous().orElseThrow().execution().runId());
        assertEquals("c".repeat(40), steps.startHead(BASE));
        assertEquals(2, steps.index());
        assertFalse(steps.done());
    }

    @Test
    void nothingCompletedStartsFromTheBase() {
        WorkSteps steps = WorkSteps.of(2);

        assertTrue(steps.chainRun().isEmpty());
        assertEquals(BASE, steps.startHead(BASE));
    }

    @Test
    void theLastStepFinishesTheCursor() {
        WorkSteps steps = WorkSteps.of(1).completedWith(step(0, "TEST-run-1", "b".repeat(40)));

        assertTrue(steps.done());
        assertThrows(IllegalStateException.class, () -> steps.completedWith(step(1, "TEST-run-2", "c".repeat(40))));
    }

    @Test
    void aRestartForgetsTheGeneration() {
        WorkSteps steps = WorkSteps.of(3).completedWith(step(0, "TEST-run-1", "b".repeat(40))).lostCheckpoint("TEST-run-1");

        WorkSteps restarted = steps.restarted();

        assertEquals(new WorkSteps(3, 0, List.of(), Set.of()), restarted);
        assertEquals(Set.of("TEST-run-1"), steps.lost());
    }
}
