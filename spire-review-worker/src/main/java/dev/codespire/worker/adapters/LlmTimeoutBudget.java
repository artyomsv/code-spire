package dev.codespire.worker.adapters;

import dev.codespire.llm.LlmConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * How long one LLM request may take, and the invariant that makes raising it safe.
 *
 * <p>The budget used to be 60 seconds hardcoded at three call sites in {@code spire-llm}, which is
 * not enough for a reasoning model on a real diff. Raising it is only safe alongside the Kafka ack
 * threshold, because the two are coupled in a way neither setting mentions: SmallRye fails the whole
 * {@code commands-in} channel when one record goes unacknowledged for longer than
 * {@code throttled.unprocessed-record-max-age.ms}, and its default is 60000 — exactly the old LLM
 * budget. So the slow call the budget explicitly permitted was the call that killed the consumer,
 * and the unacked record was redelivered on every restart and stalled it again.
 *
 * <p><b>The clock starts when the record is POLLED, not when processing starts.</b> SmallRye stamps
 * its {@code receivedAt} as the connector reads a record into its prefetch queue, so a record's age
 * includes the time every record ahead of it takes. That makes the ack budget a function of how many
 * records may be in flight, and at the connector's defaults ({@code max.poll.records} 500 over a
 * queue-size factor of 2) a burst ages out however generous the threshold looks. The channel pins
 * both, and this check reads them rather than assuming them, so a later change to either is caught
 * here instead of surfacing as a stalled consumer.
 *
 * <p>Refusing at startup rather than warning, for the reason this project already applies to
 * {@code trusted-proxies}: the failure it prevents is silent — a stalled consumer logs nothing at
 * all — so a warning in a log nobody reads is not a control. Every default the check needs is
 * declared here as well, so deleting a line from {@code application.yml} is a refusal rather than a
 * silent regression.
 */
@ApplicationScoped
public class LlmTimeoutBudget {

    private static final Logger LOG = Logger.getLogger(LlmTimeoutBudget.class);

    /**
     * Paid calls one command may make before it acknowledges its record. ADR-019 made this two: a
     * follow-up commit runs the reconcile call AND then the review call inside a single
     * {@code GenerateReview}, so an ack budget sized for one of them stalls on the other.
     */
    static final int LLM_CALLS_PER_COMMAND = 2;

    /**
     * Everything in a command that is not a model call: the PR and diff fetches, one thread fetch per
     * prior finding, the retry ladder over all of them, the context blob read, and posting.
     *
     * <p>Must exceed {@code spire.scm.rate-limit-budget-seconds} (180s by default), which is the
     * ceiling {@code ReviewWorker} allows itself to SLEEP inside one PostComments while backing off a
     * rate-limited SCM — an allowance smaller than that would call a pairing safe while a single
     * throttled posting run outran it on its own. {@link #POSTING_BUDGET_FLOOR_MS} pins that
     * relationship rather than leaving the two numbers to drift.
     */
    static final long NON_LLM_OVERHEAD_MS = 300_000L;

    /** The posting path's own sleep ceiling, which {@link #NON_LLM_OVERHEAD_MS} has to cover. */
    static final long POSTING_BUDGET_FLOOR_MS = 180_000L;

    /**
     * Past this multiple of what a command needs, the threshold stops working as a health check.
     * Warned about rather than refused: an over-generous value weakens stall detection, while too
     * small a one breaks the consumer outright, and the two do not deserve the same answer.
     */
    static final int LOOSE_THRESHOLD_FACTOR = 8;

    @ConfigProperty(name = "spire.llm.timeout-seconds", defaultValue = LlmConfig.DEFAULT_TIMEOUT_SECONDS)
    int timeoutSeconds;

    // Read as an int because SmallRye reads the same key as an Integer: a larger value would
    // otherwise pass this check and then fail conversion when the channel starts.
    @ConfigProperty(name = "mp.messaging.incoming.commands-in.throttled.unprocessed-record-max-age.ms",
            defaultValue = "60000")
    int ackMaxAgeMs;

    @ConfigProperty(name = "mp.messaging.incoming.commands-in.max.poll.records", defaultValue = "500")
    int maxPollRecords;

    @ConfigProperty(name = "mp.messaging.incoming.commands-in.max-queue-size-factor", defaultValue = "2")
    int maxQueueSizeFactor;

    /** The per-request budget handed to every LLM client this worker builds. */
    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    void enforceAckHeadroom(@Observes StartupEvent event) {
        int inFlight = inFlightRecords();
        String refusal = refusalFor(timeoutSeconds, ackMaxAgeMs, inFlight);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        long needed = needed(timeoutSeconds, inFlight);
        if (ackMaxAgeMs > needed * LOOSE_THRESHOLD_FACTOR) {
            LOG.warnf("Kafka ack threshold %dms exceeds %dx what a command needs (%dms), so a wedged "
                            + "consumer goes undetected for that long. Lower SPIRE_KAFKA_ACK_MAX_AGE_MS "
                            + "unless the slack is deliberate.",
                    ackMaxAgeMs, LOOSE_THRESHOLD_FACTOR, needed);
        }
        LOG.infof("LLM request budget %ds; up to %d record(s) in flight; Kafka ack threshold %dms "
                + "against %dms needed.", timeoutSeconds, inFlight, ackMaxAgeMs, needed);
    }

    /** Queue capacity the connector may fill ahead of the ordered, blocking dispatcher. */
    int inFlightRecords() {
        return Math.max(1, maxPollRecords) * Math.max(1, maxQueueSizeFactor);
    }

    /** What one record may cost between being polled and being acknowledged. */
    static long needed(int timeoutSeconds, int inFlightRecords) {
        return ((long) timeoutSeconds * 1000L * LLM_CALLS_PER_COMMAND + NON_LLM_OVERHEAD_MS)
                * inFlightRecords;
    }

    /**
     * Pure so the invariant is testable without booting the container. Null when the pairing is safe.
     *
     * <p>Every failure returns a message rather than throwing, so the startup observer has a single
     * error channel and every refusal clears the same actionability bar.
     */
    static String refusalFor(int timeoutSeconds, int ackMaxAgeMs, int inFlightRecords) {
        if (timeoutSeconds <= 0) {
            return "spire.llm.timeout-seconds is " + timeoutSeconds + ", which is not a usable request "
                    + "budget. Set SPIRE_LLM_TIMEOUT_SECONDS to a positive number of seconds.";
        }
        if (ackMaxAgeMs <= 0) {
            // SmallRye documents 0 as disabling this monitoring — the same practical outcome as an
            // absurdly large value, reached by a different route, and neither is a pairing this can
            // vouch for.
            return "mp.messaging.incoming.commands-in.throttled.unprocessed-record-max-age.ms is "
                    + ackMaxAgeMs + ", which disables stall monitoring on the commands channel "
                    + "entirely. Set SPIRE_KAFKA_ACK_MAX_AGE_MS to a positive number of milliseconds.";
        }
        long needed = needed(timeoutSeconds, inFlightRecords);
        if (ackMaxAgeMs > needed) {
            return null;
        }
        return "mp.messaging.incoming.commands-in.throttled.unprocessed-record-max-age.ms is "
                + ackMaxAgeMs + "ms, which does not exceed the " + needed + "ms a record may spend "
                + "between being polled and being acknowledged (" + inFlightRecords + " record(s) in "
                + "flight, each up to " + LLM_CALLS_PER_COMMAND + " LLM call(s) at "
                + "spire.llm.timeout-seconds=" + timeoutSeconds + ", plus " + NON_LLM_OVERHEAD_MS
                + "ms of fetches, retries and posting). A review that takes its full budget would go "
                + "unacknowledged, fail the commands-in channel, and stall every later command until "
                + "the consumer group is seeked past it. Raise SPIRE_KAFKA_ACK_MAX_AGE_MS above "
                + needed + ", lower the LLM timeout, or reduce how many records may be in flight.";
    }
}
