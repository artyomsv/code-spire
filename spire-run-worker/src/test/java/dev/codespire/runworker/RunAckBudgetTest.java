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

    /**
     * The control channel needs the same guard for a different reason.
     *
     * <p>It shipped declaring only its topic, group and offset reset, so it inherited the same
     * 60-second default the work channel was explicitly raised from -- and its handler is a docker
     * call with no timeout of its own. The moment an operator most needs to cancel runs is when the
     * daemon is wedged, which is exactly when that default would age the record out and fail the
     * channel, ending cancellation for every run.
     */
    @Test
    void refusesAControlChannelLeftOnTheConnectorDefault() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verifyControl(Duration.ofMillis(RunAckBudget.DEFAULT_MAX_AGE_MS), 1, 1));

        assertTrue(refused.getMessage().contains("run-control-in"));
    }

    @Test
    void refusesAPrefetchQueueOnTheControlChannelToo() {
        // A prefetched cancel ages behind the one in hand, and the connector fails the channel on
        // the record that ages out -- taking every later cancel with it.
        assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verifyControl(Duration.ofHours(2), RunAckBudget.DEFAULT_POLL_RECORDS, 1));
        assertThrows(IllegalStateException.class,
                () -> RunAckBudget.verifyControl(Duration.ofHours(2), 1, RunAckBudget.DEFAULT_QUEUE_FACTOR));
    }

    /**
     * The review lane holds its record for a review's whole run like the build lane does (harness reviews, step 4), so
     * the same wall clock + drain + allowance rule applies to {@code review-runs-in}. Every other setting here is valid,
     * so only the review lane's threshold can be what is refused.
     */
    @Test
    void theReviewLaneMustOutliveItsLongestRun() {
        RunAckBudget budget = budgetWithValidBuildAndControlLanes();
        budget.reviewMaxAgeMs = WALL_CLOCK.toMillis();
        budget.reviewPollRecords = 1;
        budget.reviewQueueSizeFactor = 1;

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> budget.check(null));
        assertTrue(refusal.getMessage().contains("review-runs-in"), refusal.getMessage());

        budget.reviewMaxAgeMs = Duration.ofMinutes(41).toMillis();
        assertDoesNotThrow(() -> budget.check(null), "a threshold that outlives a run, its drain and its ack is enough");
    }

    /** A prefetched review ages behind the one in hand exactly as a build does. */
    @Test
    void theReviewLaneMayNotPrefetch() {
        RunAckBudget budget = budgetWithValidBuildAndControlLanes();
        budget.reviewMaxAgeMs = Duration.ofMinutes(41).toMillis();
        budget.reviewPollRecords = RunAckBudget.DEFAULT_POLL_RECORDS;
        budget.reviewQueueSizeFactor = 1;

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> budget.check(null));
        assertTrue(refusal.getMessage().contains("review-runs-in"), refusal.getMessage());
    }

    private static RunAckBudget budgetWithValidBuildAndControlLanes() {
        RunLauncherTest.FakeRuntime arm = new RunLauncherTest.FakeRuntime();
        arm.drainWindow = DRAIN;
        RunAckBudget budget = new RunAckBudget();
        budget.runtime = arm;
        budget.maxWallClockSeconds = WALL_CLOCK.toSeconds();
        budget.maxAgeMs = Duration.ofMinutes(41).toMillis();
        budget.pollRecords = 1;
        budget.queueSizeFactor = 1;
        budget.controlMaxAgeMs = Duration.ofMinutes(15).toMillis();
        budget.controlPollRecords = 1;
        budget.controlQueueSizeFactor = 1;
        return budget;
    }

    @Test
    void acceptsTheShippedControlSettings() {
        // The half that keeps the guard from being unconditional.
        RunAckBudget.verifyControl(Duration.ofMinutes(15), 1, 1);
    }
}
