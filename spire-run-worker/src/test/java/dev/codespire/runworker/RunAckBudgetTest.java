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

    @Test
    void acceptsAThresholdThatOutlivesARunAndItsAck() {
        assertDoesNotThrow(() -> RunAckBudget.verify(WALL_CLOCK, Duration.ofMinutes(36), 1, 1));
    }

    /**
     * The connector's own default, and the number the earlier version of this channel ran with:
     * the first run past a minute would have killed the consumer.
     */
    @Test
    void refusesTheConnectorsDefaultThreshold() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verify(WALL_CLOCK, Duration.ofMillis(RunAckBudget.DEFAULT_MAX_AGE_MS), 1, 1));
        assertTrue(refused.getMessage().contains("unprocessed-record-max-age"));
    }

    /** Exactly the wall clock is not enough: the ack itself needs room under a slow claim store. */
    @Test
    void refusesAThresholdWithNoRoomForTheAck() {
        assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verify(WALL_CLOCK, WALL_CLOCK, 1, 1));
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
                () -> RunAckBudget.verify(WALL_CLOCK, Duration.ofHours(2), RunAckBudget.DEFAULT_POLL_RECORDS, 1));
        assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verify(WALL_CLOCK, Duration.ofHours(2), 1, RunAckBudget.DEFAULT_QUEUE_FACTOR));
    }
}
