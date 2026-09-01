package dev.codespire.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalOutcomeTest {

    @Test
    void aFailureAlwaysNamesItsCause() {
        TerminalOutcome failure = TerminalOutcome.failure(FailureCause.TIMED_OUT, "wall clock exceeded");

        assertFalse(failure.succeeded());
        assertEquals(FailureCause.TIMED_OUT, failure.cause().orElseThrow());
    }

    @Test
    void aSuccessCarriesNoCause() {
        assertTrue(TerminalOutcome.success("clean exit").succeeded());
        assertTrue(TerminalOutcome.success("clean exit").cause().isEmpty());
    }

    @Test
    void theTwoHalvesCannotDisagree() {
        // "read the logs" is not a failure cause (FR-F9), and neither is a failure with no cause at
        // all. The canonical constructor is what a future caller reaches for by accident, so it is
        // the one that has to refuse the contradiction.
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalOutcome(false, java.util.Optional.empty(), "something went wrong"));
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalOutcome(true, java.util.Optional.of(FailureCause.TIMED_OUT), "?"));
    }
}
