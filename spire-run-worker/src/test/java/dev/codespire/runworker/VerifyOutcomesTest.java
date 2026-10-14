package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.contract.work.WorkVerification;
import dev.codespire.runtime.VerifyRun;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VerifyOutcomesTest {
    final UUID attempt = UUID.randomUUID();
    final String head = "b".repeat(40);

    RunCommand.VerifyWork command(String... commands) {
        return new RunCommand.VerifyWork("TEST-run", new WorkRunBinding("TEST-item", 1, UUID.randomUUID(), "a".repeat(64)),
                attempt, head, List.of(commands), 60);
    }
    VerifyRun.Check exit(Integer code) { return new VerifyRun.Check(code, 5, List.of("TEST-line")); }
    VerifyRun prepared(boolean timedOut, VerifyRun.Check... checks) { return new VerifyRun(head, VerifyRun.Prepare.PREPARED, List.of(checks), timedOut); }
    WorkVerification classify(VerifyRun run, String... commands) { return VerifyOutcomes.classify(command(commands), run); }

    @Test void everyCheckExitingZeroPasses() {
        var v = classify(prepared(false, exit(0), exit(0)), "TEST-a", "TEST-b");
        assertTrue(v.passed());
        assertEquals(List.of("TEST-a", "TEST-b"), v.checks().stream().map(WorkVerification.CheckResult::command).toList());
    }
    @Test void aNonZeroExitFails() {
        var v = classify(prepared(false, exit(0), exit(2)), "TEST-a", "TEST-b", "TEST-c");
        assertEquals(WorkVerification.Outcome.FAILED, v.outcome());
        assertEquals("check_failed", v.reason());
        assertEquals("TEST-line", v.checks().get(1).outputTail());
    }
    @Test void exits126And127AreAMissingTool() {
        assertEquals("tool_missing", classify(prepared(false, exit(127)), "TEST-a").reason());
        assertEquals("tool_missing", classify(prepared(false, exit(126)), "TEST-a").reason());
    }
    @Test void aTimeoutIsUnverifiedNotFailed() {
        var v = classify(prepared(true, exit(null)), "TEST-a");
        assertEquals(WorkVerification.Outcome.UNVERIFIED, v.outcome());
        assertEquals("timed_out", v.reason());
    }
    @Test void aCheckThatNeverStartedCouldNotRun() {
        assertEquals("verify_could_not_run", classify(prepared(false, exit(null)), "TEST-a").reason());
    }
    @Test void noCommandsIsUnverifiedEvenWhenNothingFailed() {
        assertEquals("no_checks_declared", classify(prepared(false)).reason());
    }
    @Test void aMissingCheckpointIsNamed() {
        assertEquals("checkpoint_missing", classify(new VerifyRun(null, VerifyRun.Prepare.CHECKPOINT_MISSING, List.of(), false), "TEST-a").reason());
    }
    @Test void aPrepareThatFailedCouldNotRun() {
        assertEquals("verify_could_not_run", classify(new VerifyRun(null, VerifyRun.Prepare.FAILED, List.of(), false), "TEST-a").reason());
        assertEquals("timed_out", classify(new VerifyRun(null, VerifyRun.Prepare.FAILED, List.of(), true), "TEST-a").reason());
    }
    @Test void aPreparedHeadOtherThanAskedIsNeverPassed() {
        var other = new VerifyRun("c".repeat(40), VerifyRun.Prepare.PREPARED, List.of(exit(0)), false);
        assertEquals("checkpoint_missing", classify(other, "TEST-a").reason());
    }
    @Test void twentyLongOutputsStayWellUnderTheBrokersRecordLimit() {
        String longLine = "x".repeat(1000);
        List<String> loud = java.util.Collections.nCopies(200, longLine);
        VerifyRun.Check[] checks = new VerifyRun.Check[20];
        String[] commands = new String[20];
        for (int i = 0; i < 20; i++) {
            checks[i] = new VerifyRun.Check(i == 19 ? 1 : 0, 5, loud);
            commands[i] = "TEST-check-" + i;
        }
        var v = classify(prepared(false, checks), commands);
        int total = v.checks().stream().mapToInt(c -> c.outputTail().length()).sum();
        assertTrue(total <= 19 * VerifyOutcomes.PASSING_TAIL_CHARS + WorkVerification.MAX_TAIL_CHARS, "total " + total);
        assertEquals(WorkVerification.MAX_TAIL_CHARS, v.checks().get(19).outputTail().length(), "the failing check keeps its whole tail");
        assertEquals(VerifyOutcomes.PASSING_TAIL_CHARS, v.checks().getFirst().outputTail().length());
    }
    @Test void fewerResultsThanCommandsWithoutAFailureCouldNotRun() {
        assertEquals("verify_could_not_run", classify(prepared(false, exit(0)), "TEST-a", "TEST-b").reason());
    }
}
