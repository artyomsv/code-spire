package dev.codespire.harness;

import org.junit.jupiter.api.Test;

import java.util.Optional;

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

    // The two halves below are separate tests on purpose. As one method with two assertThrows calls
    // they were indistinguishable under mutation: deleting either guard failed the same single test,
    // so a reader could not tell which guard the suite actually proved.

    @Test
    void aFailureWithNoCauseIsRefused() {
        // "read the logs" is not a failure cause (FR-F9), and neither is a failure with no cause at
        // all. The canonical constructor is what a future caller reaches for by accident, so it is
        // the one that has to refuse the contradiction.
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalOutcome(false, Optional.empty(), "something went wrong"));
    }

    @Test
    void aSuccessNamingACauseIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalOutcome(true, Optional.of(FailureCause.TIMED_OUT), "?"));
    }

    @Test
    void neitherHalfMayBeNull() {
        assertThrows(NullPointerException.class,
                () -> new TerminalOutcome(true, null, "detail"));
        assertThrows(NullPointerException.class,
                () -> new TerminalOutcome(true, Optional.empty(), null));
    }
}
