package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunEventRecord;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Consumes {@code cs.run-events} into the transcript, and trims it on a timer (FR-F5, ADR-034).
 *
 * <p><b>This channel has no dead-letter route, deliberately.</b> Every other consumer in this system
 * dead-letters what it cannot process, because losing a command or a result is unacceptable. A
 * transcript event is the opposite: it is not replayable, nothing derives state from it, and a
 * dead-letter queue exists for things that must not be lost. Routing an unbounded, TTL'd convenience
 * into it would fill a queue meant for real work with lines nobody will ever replay.
 */
@ApplicationScoped
public class RunEventConsumer {

    private static final Logger LOG = Logger.getLogger(RunEventConsumer.class);

    @Inject
    RunEventProjection transcript;

    /**
     * How long a run's transcript is kept.
     *
     * <p>Short by design (ADR-034): this tier exists for the live tail and for debugging a run
     * someone is looking at now, not as a record of what happened. What must outlive it — the
     * outcome, the cause, the charge — is durable elsewhere.
     */
    @ConfigProperty(name = "spire.run.transcript-retention-days", defaultValue = "7")
    int retentionDays;

    @Incoming("run-events-in")
    @Blocking
    public void onEvent(RunEventRecord event) {
        if (event == null) {
            // The deserializer answers null rather than throwing. A poison record must not stall
            // every other run's tail for the sake of one unreadable line.
            LOG.debug("an unreadable run event was skipped");
            return;
        }
        transcript.record(event);
    }

    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void trim() {
        transcript.sweep(Duration.ofDays(retentionDays));
    }
}
