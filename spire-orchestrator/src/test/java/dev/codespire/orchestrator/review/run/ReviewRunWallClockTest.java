package dev.codespire.orchestrator.review.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A review run may not be allowed longer than a build: the run-worker would refuse its unit after it was paid for. */
class ReviewRunWallClockTest {

    @Test
    void aWallClockAboveTheRunWorkersBoundIsRefusedAtStartup() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> ReviewRunWallClock.check(1801, 1800));

        assertTrue(refused.getMessage().contains("spire.factory.review-wall-clock-seconds"), refused.getMessage());
        assertDoesNotThrow(() -> ReviewRunWallClock.check(1800, 1800), "the bound itself is allowed");
        assertDoesNotThrow(() -> ReviewRunWallClock.check(900, 1800));
    }
}
