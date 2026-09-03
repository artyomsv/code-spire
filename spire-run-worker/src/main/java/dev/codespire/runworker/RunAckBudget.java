package dev.codespire.runworker;

import dev.codespire.runtime.RunRuntime;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Refuses to start when the command channel's settings cannot survive one run.
 *
 * <p>The design says the channel acks on receipt so a run's length never matters to Kafka. That
 * is true of the ack, and it is not the whole story: SmallRye stamps a record's age when it is
 * <b>polled</b>, not when its handler starts, so any record sitting in a prefetch queue behind a
 * running unit ages for that unit's whole wall clock — and the throttled strategy fails the channel
 * when one exceeds {@code throttled.unprocessed-record-max-age.ms}. The review worker learned this
 * live: its consumer died on the first slow call, and the record was redelivered on every restart
 * and killed it again, until a manual offset seek.
 *
 * <p>So three settings hold the design together, and this checks all three rather than trusting
 * the YAML they are written in: no prefetch beyond the record in hand, and a threshold that would
 * still survive a run's full wall clock in the one case the manual ack is late — a claim-store
 * outage, which delays the ack by however long the database takes to answer. The check declares the
 * connector's own defaults, so deleting a line from {@code application.yml} is a refusal to start
 * rather than a silent regression; that is the shape {@code LlmTimeoutBudget} already has.
 */
@ApplicationScoped
public class RunAckBudget {

    /** SmallRye's default; declared here so an absent setting is checked against what applies. */
    static final long DEFAULT_MAX_AGE_MS = 60_000L;

    static final int DEFAULT_POLL_RECORDS = 500;

    static final int DEFAULT_QUEUE_FACTOR = 2;

    /** Head-room over the wall clock for the ack itself to land under a slow claim store. */
    static final Duration ACK_ALLOWANCE = Duration.ofMinutes(5);

    /**
     * The arm in use, asked for its own drain window so the two numbers cannot drift apart
     * silently. Read through the SPI rather than off the Docker class: a core module must not name
     * an arm (the neutrality scan refuses it), and the Kubernetes arm will have a window of its own.
     */
    @Inject
    RunRuntime runtime;

    @ConfigProperty(name = "spire.run.max-wall-clock-seconds")
    long maxWallClockSeconds;

    @ConfigProperty(name = "mp.messaging.incoming.run-commands-in.throttled.unprocessed-record-max-age.ms",
            defaultValue = "" + DEFAULT_MAX_AGE_MS)
    long maxAgeMs;

    @ConfigProperty(name = "mp.messaging.incoming.run-commands-in.max.poll.records",
            defaultValue = "" + DEFAULT_POLL_RECORDS)
    int pollRecords;

    @ConfigProperty(name = "mp.messaging.incoming.run-commands-in.max-queue-size-factor",
            defaultValue = "" + DEFAULT_QUEUE_FACTOR)
    int queueSizeFactor;

    @ConfigProperty(name = "mp.messaging.incoming.run-control-in.throttled.unprocessed-record-max-age.ms",
            defaultValue = "" + DEFAULT_MAX_AGE_MS)
    long controlMaxAgeMs;

    @ConfigProperty(name = "mp.messaging.incoming.run-control-in.max.poll.records",
            defaultValue = "" + DEFAULT_POLL_RECORDS)
    int controlPollRecords;

    @ConfigProperty(name = "mp.messaging.incoming.run-control-in.max-queue-size-factor",
            defaultValue = "" + DEFAULT_QUEUE_FACTOR)
    int controlQueueSizeFactor;

    void check(@Observes StartupEvent event) {
        verify(Duration.ofSeconds(maxWallClockSeconds), runtime.drainWindow(), Duration.ofMillis(maxAgeMs),
                pollRecords, queueSizeFactor);
        verifyControl(Duration.ofMillis(controlMaxAgeMs), controlPollRecords, controlQueueSizeFactor);
    }

    /**
     * The control channel needs the same guard for a different reason.
     *
     * <p>It shipped declaring only its topic, group and offset reset, and so inherited the same
     * 60-second default the work channel was explicitly raised from after the incident this class
     * exists to prevent. Its handler is a docker call with no timeout of its own, and the moment an
     * operator most needs to cancel runs is when the daemon is wedged — so the default would fail
     * the channel exactly when cancellation is the thing being relied on.
     *
     * <p>The budget is not the work channel's: a cancel does not wait out a wall clock or a drain,
     * it waits out one unresponsive runtime call. {@link #ACK_ALLOWANCE} is that floor.
     */
    static void verifyControl(Duration maxAge, int pollRecords, int queueSizeFactor) {
        if (pollRecords != 1 || queueSizeFactor != 1) {
            throw new IllegalStateException("run-control-in must poll one record with no prefetch "
                    + "(max.poll.records=1, max-queue-size-factor=1); found " + pollRecords + " and "
                    + queueSizeFactor + ". A prefetched cancel ages behind the one in hand, and the "
                    + "connector fails the channel on the record that ages out — which would end "
                    + "cancellation for every run.");
        }
        if (maxAge.compareTo(ACK_ALLOWANCE) < 0) {
            throw new IllegalStateException("run-control-in throttled.unprocessed-record-max-age.ms is "
                    + maxAge.toMillis() + ", under the " + ACK_ALLOWANCE.toSeconds() + "s a control "
                    + "record may need while the runtime is unresponsive. Below this the channel dies "
                    + "on a wedged daemon, which is when cancelling matters most.");
        }
    }

    /**
     * The rule, separable from CDI so a test can drive it with values that match nothing shipped.
     *
     * @param drain the arm's {@link RunRuntime#drainWindow()}: how long salvage may hold the handler
     *              after the wall clock, which is part of the budget rather than head-room
     */
    static void verify(Duration wallClock, Duration drain, Duration maxAge, int pollRecords, int queueSizeFactor) {
        if (pollRecords != 1 || queueSizeFactor != 1) {
            throw new IllegalStateException("run-commands-in must poll one record with no prefetch "
                    + "(max.poll.records=1, max-queue-size-factor=1); found " + pollRecords + " and "
                    + queueSizeFactor + ". A prefetched record ages behind the running unit for its whole "
                    + "wall clock and fails the channel however promptly the handler acks.");
        }
        // The handler holds the ordered channel for the wall clock, then the publisher's drain
        // window, then the allowance for the ack to land. The drain is part of the budget: it went
        // from 30s to 300s once and the guard did not notice.
        Duration needed = wallClock.plus(drain).plus(ACK_ALLOWANCE);
        if (maxAge.compareTo(needed) < 0) {
            throw new IllegalStateException("run-commands-in throttled.unprocessed-record-max-age.ms is "
                    + maxAge.toMillis() + " but a run may take " + wallClock.toSeconds() + "s plus the "
                    + "publisher's " + drain.toSeconds() + "s drain plus " + ACK_ALLOWANCE.toSeconds()
                    + "s for the ack to land; the consumer would die on the first run that outlives it "
                    + "and be redelivered to die again on every restart.");
        }
    }
}
