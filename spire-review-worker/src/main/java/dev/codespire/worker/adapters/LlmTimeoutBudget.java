package dev.codespire.worker.adapters;

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
 * <p>Refusing at startup rather than warning, for the reason this project already applies to
 * {@code trusted-proxies}: the failure it prevents is silent (a stalled consumer logs nothing at
 * all), so a warning in a log nobody reads is not a control. The ack default is declared here too,
 * so deleting the line from {@code application.yml} is a refusal rather than a silent regression.
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

    @ConfigProperty(name = "spire.llm.timeout-seconds", defaultValue = "180")
    int timeoutSeconds;

    @ConfigProperty(name = "mp.messaging.incoming.commands-in.throttled.unprocessed-record-max-age.ms",
            defaultValue = "60000")
    long ackMaxAgeMs;

    /** The per-request budget handed to every LLM client this worker builds. */
    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    void enforceAckHeadroom(@Observes StartupEvent event) {
        String refusal = refusalFor(timeoutSeconds, ackMaxAgeMs);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        LOG.infof("LLM request budget %ds; Kafka ack threshold %dms (headroom for %d call(s) per command).",
                timeoutSeconds, ackMaxAgeMs, LLM_CALLS_PER_COMMAND);
    }

    /** Pure so the invariant is testable without booting the container. Null when the pairing is safe. */
    static String refusalFor(int timeoutSeconds, long ackMaxAgeMs) {
        if (timeoutSeconds <= 0) {
            throw new IllegalStateException(
                    "spire.llm.timeout-seconds must be positive, got " + timeoutSeconds + ".");
        }
        long needed = (long) timeoutSeconds * 1000L * LLM_CALLS_PER_COMMAND;
        if (ackMaxAgeMs > needed) {
            return null;
        }
        return "mp.messaging.incoming.commands-in.throttled.unprocessed-record-max-age.ms is "
                + ackMaxAgeMs + "ms, which does not exceed the " + needed + "ms a command may spend on "
                + LLM_CALLS_PER_COMMAND + " LLM call(s) at spire.llm.timeout-seconds=" + timeoutSeconds
                + ". A review that takes its full budget would go unacknowledged, fail the commands-in "
                + "channel, and stall every later command until the consumer group is seeked past it. "
                + "Raise SPIRE_KAFKA_ACK_MAX_AGE_MS above " + needed + ", or lower the LLM timeout.";
    }
}
