package dev.codespire.contract.event;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunResultTest {

    private static RunResult.RunFinished finished(String pushedRef, List<String> blocked,
                                                  Map<String, Long> usage) {
        return new RunResult.RunFinished("run::github:a/b:s:1", pushedRef,
                List.of("src/Foo.java"), blocked, usage);
    }

    @Test
    void unknownUsageIsNullAndSaysSoWithoutASecondFlag() {
        // The shape this replaces was (Long input, Long output, boolean usageUnknown): two
        // representations of one fact, free to disagree. (inputTokens=5, usageUnknown=true) was
        // constructible and meaningless, and a consumer reading the numbers while ignoring the flag
        // would price a run nobody measured.
        assertFalse(finished("refs/heads/x", List.of(), null).usageIsKnown());
        assertTrue(finished("refs/heads/x", List.of(), Map.of("INPUT", 12L)).usageIsKnown());
    }

    @Test
    void anEmptyUsageMapIsNotAMeasurement() {
        // Allowing it would make "measured nothing" and "measured, and it was all zero" the same
        // value — the fabricated zero ADR-023 exists to prevent, arriving through the wire.
        assertThrows(IllegalArgumentException.class,
                () -> finished("refs/heads/x", List.of(), Map.of()));
    }

    @Test
    void aRunCannotHaveBothPushedAndBeenRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> finished("refs/heads/x", List.of(".github/workflows/ci.yml"), null));
    }

    @Test
    void aRefusalIsRecognisableWithoutInspectingTwoFields() {
        RunResult.RunFinished refused = finished(null, List.of(".github/workflows/ci.yml"), null);

        assertTrue(refused.refused());
        assertFalse(finished("refs/heads/x", List.of(), null).refused());
    }

    @Test
    void aFailedRunMustNameItsCause() {
        // "read the logs" is not a failure cause (FR-F9): a failure with no named cause reaches an
        // operator as a row saying only that something went wrong.
        assertThrows(IllegalArgumentException.class,
                () -> new RunResult.RunFailed("run::github:a/b:s:1", " ", "detail", false));
        assertEquals("SANDBOX_LOST",
                new RunResult.RunFailed("run::github:a/b:s:1", "SANDBOX_LOST", "gone", true).cause());
    }

    @Test
    void anExecuteRunNeverPrintsACredential() {
        // A record prints every component, and log.info("dispatching {}", command) is the obvious
        // line to write. These are Tink ciphertext rather than plaintext, but a ciphertext in a log
        // is still a credential in a log: it survives rotation and it is attacker-collectable.
        RunCommand.ExecuteRun command = new RunCommand.ExecuteRun("run::github:a/b:s:1",
                new RepoRef("a", "b"), "https://github.com/a/b.git", "main", "abc123", "spire/run_1", "fix the bug", "CODEX",
                "gpt-5.6", "spire/agent:1", List.of("deploy/**"), 1800,
                "TINK-CIPHERTEXT-SCM", "TINK-CIPHERTEXT-HARNESS");

        String rendered = command.toString();

        assertFalse(rendered.contains("TINK-CIPHERTEXT-SCM"), rendered);
        assertFalse(rendered.contains("TINK-CIPHERTEXT-HARNESS"), rendered);
        assertTrue(rendered.contains("run::github:a/b:s:1"));
        assertTrue(rendered.contains("spire/agent:1"));
    }

    @Test
    void anExecuteRunNeedsAWallClock() {
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.ExecuteRun("r",
                new RepoRef("a", "b"), "https://github.com/a/b.git", "main", "abc", "br", "p", "CODEX", "m", "img", List.of(), 0,
                null, null));
    }

    @Test
    void bothRunCommandsCarryTheirRunId() {
        // The reason this is a separate hierarchy from ActionCommand, which mandates reviewId().
        assertEquals("r1", new RunCommand.CancelRun("r1", "operator asked").runId());
        assertEquals("r2", new RunCommand.ExecuteRun("r2", new RepoRef("a", "b"), "https://github.com/a/b.git", "main", "abc", "br",
                "p", "CODEX", "m", "img", List.of(), 60, null, null).runId());
    }
}
