package dev.codespire.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The request budget is the one field here that defaults, because it is an operational bound rather
 * than something only the operator can know. Everything else still fails fast (ADR-001).
 */
class LlmConfigTest {

    private static LlmConfig config(Duration timeout) {
        return new LlmConfig("https://llm.example.invalid", "key", "some-model", 0.2, timeout);
    }

    @Test
    void aCallerThatNamesNoTimeoutGetsTheDefault() {
        assertEquals(LlmConfig.DEFAULT_TIMEOUT,
                new LlmConfig("https://llm.example.invalid", "key", "some-model", 0.2).timeout());
    }

    @Test
    void aNullTimeoutFallsBackToTheDefaultRatherThanNpeingAtTheClientBuilder() {
        assertEquals(LlmConfig.DEFAULT_TIMEOUT, config(null).timeout());
    }

    @Test
    void anExplicitTimeoutIsHonoured() {
        assertEquals(Duration.ofSeconds(300), config(Duration.ofSeconds(300)).timeout());
    }

    @Test
    void theDefaultExceedsTheSixtySecondsThatUsedToBeHardcoded() {
        // The old value cut a reasoning model off mid-answer on a real diff. Asserted so that
        // "restore the previous behaviour" cannot happen by accident.
        assertEquals(true, LlmConfig.DEFAULT_TIMEOUT.compareTo(Duration.ofSeconds(60)) > 0);
    }

    @Test
    void aNonPositiveTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> config(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> config(Duration.ofSeconds(-1)));
    }

    @Test
    void credentialsStillHaveNoDefaults() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmConfig("", "key", "some-model", 0.2, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new LlmConfig("https://llm.example.invalid", " ", "some-model", 0.2, Duration.ofSeconds(30)));
    }
}
