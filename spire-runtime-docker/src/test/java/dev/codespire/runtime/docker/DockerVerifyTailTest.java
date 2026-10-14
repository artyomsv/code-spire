package dev.codespire.runtime.docker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The output a failed check leaves for the operator and the retry prompt: its END, which names the failure. */
class DockerVerifyTailTest {

    private static final int BUDGET = DockerRunRuntime.VERIFY_TAIL_CHARS + DockerRunRuntime.VERIFY_LINE_CHARS;

    /** Review of PR #184 (M-5): output past the budget drops its oldest text, never the final error. */
    @Test
    void theLastLineSurvivesOutputLongerThanTheBudget() {
        DockerVerifyUnits.RollingTail tail = new DockerVerifyUnits.RollingTail(BUDGET);
        for (int frame = 0; frame < 100; frame++) tail.append("x".repeat(999) + "\n");
        tail.append("TEST-final-error\n");

        List<String> lines = tail.lines();

        assertEquals("TEST-final-error", lines.getLast());
        assertTrue(lines.stream().mapToInt(line -> line.length() + 1).sum() <= DockerRunRuntime.VERIFY_TAIL_CHARS);
    }

    /** A line cut where the oldest text was dropped is not shown as if it were whole. */
    @Test
    void aLineCutByTheBudgetIsDropped() {
        DockerVerifyUnits.RollingTail tail = new DockerVerifyUnits.RollingTail(10);
        tail.append("TEST-first-line\nTEST-b\n");

        assertEquals(List.of("TEST-b"), tail.lines());
    }

    @Test
    void shortOutputIsKeptWhole() {
        DockerVerifyUnits.RollingTail tail = new DockerVerifyUnits.RollingTail(BUDGET);
        tail.append("TEST-a\n");
        tail.append("TEST-b\n");

        assertEquals(List.of("TEST-a", "TEST-b"), tail.lines());
    }
}
