package dev.codespire.contract.work;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The one rule the build setup, the preparation and the verify command share (review of PR #184). */
class VerifyCommandsTest {

    @Test
    void upToTwentySingleLinesAreValid() {
        assertTrue(VerifyCommands.valid(List.of()));
        assertTrue(VerifyCommands.valid(Collections.nCopies(VerifyCommands.MAX_COMMANDS, "test -f README.md")));
        assertTrue(VerifyCommands.valid(List.of("x".repeat(VerifyCommands.MAX_COMMAND_CHARS))));
    }

    @Test
    void anythingElseIsRefusedWithoutQuotingTheCommand() {
        assertFalse(VerifyCommands.valid(Collections.nCopies(VerifyCommands.MAX_COMMANDS + 1, "true")));
        assertFalse(VerifyCommands.valid(List.of("x".repeat(VerifyCommands.MAX_COMMAND_CHARS + 1))));
        assertFalse(VerifyCommands.valid(List.of("true\nrm -rf TEST")));
        assertFalse(VerifyCommands.valid(List.of("true\rTEST")));
        assertFalse(VerifyCommands.valid(List.of(" ")));
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertFalse(VerifyCommands.valid(withNull));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> VerifyCommands.requireValid(List.of("TEST-secret\nline")));
        assertFalse(refused.getMessage().contains("TEST-secret"));
    }
}
