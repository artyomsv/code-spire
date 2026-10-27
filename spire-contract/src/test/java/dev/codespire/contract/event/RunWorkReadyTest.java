package dev.codespire.contract.event;

import dev.codespire.contract.work.StepSummary;
import dev.codespire.contract.work.WorkRunBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ready result's wither (M4 slice 8): it carries the step summary and drops no other component. */
class RunWorkReadyTest {

    private static final WorkRunBinding WORK = new WorkRunBinding("TEST-work", 3,
            UUID.fromString("00000000-0000-4000-8000-000000000008"), "a".repeat(64));

    private static final StepSummary SUMMARY = new StepSummary(List.of("TEST-decision"), List.of("TEST-alternative"),
            List.of("TEST-deviation"));

    @Test
    void withStepSummaryEnumeratesEveryComponent() {
        RunResult.RunWorkReady ready = new RunResult.RunWorkReady("TEST-run", WORK, "b".repeat(40),
                List.of("TEST-file"), Map.of("INPUT", 7L), 12);

        RunResult.RunWorkReady carried = ready.withStepSummary(SUMMARY);

        assertEquals("TEST-run", carried.runId());
        assertEquals(WORK, carried.work());
        assertEquals("b".repeat(40), carried.head());
        assertEquals(List.of("TEST-file"), carried.changedPaths());
        assertEquals(Map.of("INPUT", 7L), carried.tokenUsage());
        assertEquals(12, carried.activeWallSeconds());
        assertEquals(SUMMARY, carried.stepSummary());
    }

    @Test
    void theSixComponentConstructorCarriesNoSummary() {
        assertNull(new RunResult.RunWorkReady("TEST-run", WORK, "b".repeat(40), List.of(), null, 0).stepSummary());
    }

    /** An empty ready reports the head its build started from (M4 slice 3, §6.7): any other head committed something. */
    @Test
    void anEmptyReadyMustStandOnItsStart() {
        RunResult.RunWorkReady fromCheckpoint = new RunResult.RunWorkReady("TEST-run", WORK, "b".repeat(40), List.of(), null, 0)
                .withStartFromHead("c".repeat(40));

        assertThrows(IllegalArgumentException.class, () -> fromCheckpoint.withEmpty(true));
        assertTrue(fromCheckpoint.withStartFromHead("b".repeat(40)).withEmpty(true).empty(), "an empty ready on its start");
        assertTrue(new RunResult.RunWorkReady("TEST-run", WORK, "b".repeat(40), List.of(), null, 0).withEmpty(true).empty(),
                "a build from the base names no start, and its head is the base the orchestrator knows");
    }

    @Test
    void withEmptyEnumeratesEveryComponent() {
        RunResult.RunWorkReady ready = new RunResult.RunWorkReady("TEST-run", WORK, "b".repeat(40), List.of("TEST-file"), Map.of("INPUT", 7L), 12)
                .withStepSummary(SUMMARY).withStartFromHead("b".repeat(40));

        RunResult.RunWorkReady empty = ready.withEmpty(true);

        assertTrue(empty.empty());
        assertEquals(ready, empty.withEmpty(false));
        assertTrue(empty.withStepSummary(null).empty(), "withStepSummary keeps it");
        assertTrue(empty.withStartFromHead("b".repeat(40)).empty(), "withStartFromHead keeps it");
        assertFalse(new RunResult.RunWorkReady("TEST-run", WORK, "b".repeat(40), List.of(), null, 0).empty());
    }
}
