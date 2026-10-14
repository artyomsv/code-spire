package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkVerification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkVerifyHistoryTest {
    WorkVerification failed(String tail) {
        return new WorkVerification(UUID.randomUUID(), "b".repeat(40), WorkVerification.Outcome.FAILED, "check_failed",
                List.of(new WorkVerification.CheckResult("TEST-pass", 0, 1, "TEST-quiet"), new WorkVerification.CheckResult("TEST-check", 1, 1, tail)));
    }

    @Test void theSectionIsBounded() {
        assertTrue(WorkVerifyHistory.promptSection(failed("x".repeat(60_000))).length() <= WorkVerifyHistory.MAX_PROMPT_SECTION_CHARS);
    }

    @Test void onlyAFailedCheckShowsItsOutput() {
        String section = WorkVerifyHistory.promptSection(failed("TEST-why"));
        assertTrue(section.contains("TEST-why"));
        assertTrue(section.contains("Command: TEST-pass"));
        assertFalse(section.contains("TEST-quiet"), "a passing check's output is noise in a retry prompt");
    }

    @Test void anUnverifiedResultNamesItsReason() {
        var unverified = new WorkVerification(UUID.randomUUID(), "b".repeat(40), WorkVerification.Outcome.UNVERIFIED, "timed_out", List.of());
        assertTrue(WorkVerifyHistory.promptSection(unverified).contains("Reason: timed_out"));
        assertTrue(WorkVerifyHistory.checkpointReadable(unverified));
    }
}
