package dev.codespire.runworker;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup refusal that keeps the run channel's three settings honest.
 *
 * <p>Driven with values that match nothing shipped, on purpose: a guard tested only against the
 * configuration it ships with passes whether or not it reads that configuration.
 */
class RunAckBudgetTest {

    private static final Duration WALL_CLOCK = Duration.ofMinutes(30);

    /** Deliberately not the Docker arm's 300s: the rule is tested, not the shipped number. */
    private static final Duration DRAIN = Duration.ofMinutes(5);

    @Test
    void acceptsAThresholdThatOutlivesARunItsDrainAndItsAck() {
        assertDoesNotThrow(() -> RunAckBudget.verify(WALL_CLOCK, DRAIN, Duration.ofMinutes(41), 1, 1));
    }

    @Test
    void theDrainWindowIsPartOfTheBudget() {
        // The handler holds the channel for wall clock + the publisher's drain + the ack. The drain
        // went 30s to 300s and a guard of wallClock + 5min accepted a threshold it no longer
        // covered: 36 minutes here was "safe" and now leaves the publisher 4 minutes short.
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verify(WALL_CLOCK, DRAIN, Duration.ofMinutes(36), 1, 1));
        assertTrue(refusal.getMessage().contains("drain"), refusal.getMessage());
    }

    /**
     * The drain is the ARM's number, read through the SPI at startup. A constant of the budget's
     * own — the shape this replaced, which also named the Docker class in a core module — passes
     * every test above and drifts the first time an arm changes its window.
     */
    @Test
    void theDrainComesFromTheRuntimeArm() {
        RunLauncherTest.FakeRuntime arm = new RunLauncherTest.FakeRuntime();
        arm.drainWindow = Duration.ofMinutes(20);
        RunAckBudget budget = new RunAckBudget();
        budget.runtime = arm;
        budget.maxWallClockSeconds = WALL_CLOCK.toSeconds();
        budget.maxAgeMs = Duration.ofMinutes(41).toMillis();   // enough for a 5-minute drain, not 20
        budget.pollRecords = 1;
        budget.queueSizeFactor = 1;

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> budget.check(null));
        assertTrue(refusal.getMessage().contains("1200s drain"), refusal.getMessage());
    }

    /**
     * The connector's own default, and the number the earlier version of this channel ran with:
     * the first run past a minute would have killed the consumer.
     */
    @Test
    void refusesTheConnectorsDefaultThreshold() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verify(WALL_CLOCK, DRAIN, Duration.ofMillis(RunAckBudget.DEFAULT_MAX_AGE_MS), 1, 1));
        assertTrue(refused.getMessage().contains("unprocessed-record-max-age"));
    }

    /** Exactly the wall clock is not enough: the ack itself needs room under a slow claim store. */
    @Test
    void refusesAThresholdWithNoRoomForTheAck() {
        assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verify(WALL_CLOCK, DRAIN, WALL_CLOCK, 1, 1));
    }

    /**
     * A prefetched record ages behind the running unit however fast the handler acks. Either
     * setting alone leaves a queue: poll.records bounds a poll, the queue factor bounds what is
     * held between polls, and the review worker's own comment records that the first does not
     * cover the second.
     */
    @Test
    void refusesAnyPrefetchQueue() {
        assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verify(WALL_CLOCK, DRAIN, Duration.ofHours(2), RunAckBudget.DEFAULT_POLL_RECORDS, 1));
        assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verify(WALL_CLOCK, DRAIN, Duration.ofHours(2), 1, RunAckBudget.DEFAULT_QUEUE_FACTOR));
    }
}
