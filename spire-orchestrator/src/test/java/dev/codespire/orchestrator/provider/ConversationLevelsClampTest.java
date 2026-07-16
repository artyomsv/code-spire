package dev.codespire.orchestrator.provider;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic coverage for the {@code app_setting}-backed clamp helpers: absent/unparseable values fall
 * back to the code default, and out-of-range values clamp to the documented bounds on read.
 */
class ConversationLevelsClampTest {

    @Test
    void absentValueFallsBackToTheDefault() {
        assertEquals(4, ConversationLevels.clampInt(Optional.empty(), 4, 1, 50));
        assertEquals(5, ConversationLevels.clampInt(Optional.empty(), 5, 1, 10));
        assertEquals(2000L, ConversationLevels.clampLong(Optional.empty(), 2000L, 100L, 60000L));
        assertEquals(2.0, ConversationLevels.clampDouble(Optional.empty(), 2.0, 1.0, 5.0));
    }

    @Test
    void unparseableValueFallsBackToTheDefault() {
        assertEquals(4, ConversationLevels.clampInt(Optional.of("not-a-number"), 4, 1, 50));
        assertEquals(2000L, ConversationLevels.clampLong(Optional.of("nope"), 2000L, 100L, 60000L));
        assertEquals(2.0, ConversationLevels.clampDouble(Optional.of("nope"), 2.0, 1.0, 5.0));
    }

    @Test
    void turnCapClampsToTheUpperBound() {
        assertEquals(50, ConversationLevels.clampInt(Optional.of("999"), 4, 1, 50));
    }

    @Test
    void turnCapClampsToTheLowerBound() {
        assertEquals(1, ConversationLevels.clampInt(Optional.of("0"), 4, 1, 50));
        assertEquals(1, ConversationLevels.clampInt(Optional.of("-5"), 4, 1, 50));
    }

    @Test
    void maxAttemptsClampsToTenAndOne() {
        assertEquals(10, ConversationLevels.clampInt(Optional.of("999"), 5, 1, 10));
        assertEquals(1, ConversationLevels.clampInt(Optional.of("0"), 5, 1, 10));
    }

    @Test
    void withinRangeValuesPassThroughUnchanged() {
        assertEquals(12, ConversationLevels.clampInt(Optional.of("12"), 4, 1, 50));
        assertEquals(7, ConversationLevels.clampInt(Optional.of("7"), 5, 1, 10));
    }

    @Test
    void backoffBaseMsClampsToItsBounds() {
        assertEquals(60000L, ConversationLevels.clampLong(Optional.of("999999"), 2000L, 100L, 60000L));
        assertEquals(100L, ConversationLevels.clampLong(Optional.of("1"), 2000L, 100L, 60000L));
        assertEquals(5000L, ConversationLevels.clampLong(Optional.of("5000"), 2000L, 100L, 60000L));
    }

    @Test
    void backoffFactorClampsToItsBounds() {
        assertEquals(5.0, ConversationLevels.clampDouble(Optional.of("99.9"), 2.0, 1.0, 5.0));
        assertEquals(1.0, ConversationLevels.clampDouble(Optional.of("0.1"), 2.0, 1.0, 5.0));
        assertEquals(3.5, ConversationLevels.clampDouble(Optional.of("3.5"), 2.0, 1.0, 5.0));
    }
}
