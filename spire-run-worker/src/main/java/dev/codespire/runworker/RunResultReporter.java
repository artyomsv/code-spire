package dev.codespire.runworker;

import dev.codespire.contract.event.RunResult;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes a terminal result for a run this worker is not currently executing.
 *
 * <p>The dispatcher has its own publish path, and deliberately so: it can compact a result the broker
 * refused for being too large, using facts only it holds. This is the smaller case — a reclaimed
 * orphan, whose result is a bare failure that cannot be too large — and it exists because the
 * watchdog runs on a timer, outside any command's handling, so there is no dispatcher call in
 * progress to borrow.
 *
 * <p>Never throws. A scheduled sweep that dies on a broker refusal stops reclaiming anything, and the
 * sandbox it was reporting on is still there to be found next tick.
 */
@ApplicationScoped
public class RunResultReporter {

    private static final Logger LOG = Logger.getLogger(RunResultReporter.class);

    @Inject
    @Channel("run-results-out")
    Emitter<Record<String, RunResult>> results;

    /**
     * How long to wait for the broker.
     *
     * <p>No inline default: the value is declared once in {@code application.yml} and read by both
     * publish paths, so the dispatcher's wait and this one cannot drift. Refused at startup if it
     * is not positive — a zero makes every {@code get} time out immediately, so every reclamation
     * report is lost while the configuration looks set.
     */
    @ConfigProperty(name = "spire.run.result-ack-seconds")
    long ackSeconds;

    void check(@Observes StartupEvent event) {
        if (ackSeconds <= 0) {
            throw new IllegalStateException("spire.run.result-ack-seconds is " + ackSeconds
                    + "; a non-positive wait times out instantly, so every result would be reported"
                    + " as unpublishable while the configuration looked correct.");
        }
    }

    /** Publish, awaiting the broker's acknowledgement, and report rather than throw on a refusal. */
    /**
     * @return true when the broker acknowledged the result; false when it did not.
     *
     * <p>An answer rather than {@code void}, because the caller may have taken a once-only claim
     * BEFORE calling — and a claim burnt by a broker outage leaves the run with no automated path
     * to a terminal result at all. The watchdog is that caller.
     */
    public boolean report(RunResult result) {
        try {
            results.send(Record.of(result.runId(), result))
                    .toCompletableFuture()
                    .get(ackSeconds, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.errorf(e, "run %s: reporting its reclamation was interrupted", result.runId());
            return false;
        } catch (RuntimeException | ExecutionException | TimeoutException e) {
            // The caller decides what to do about it. This used to say "the sandbox is already
            // destroyed by this point" — it is not: the watchdog reports BEFORE destroy, and on
            // the not-salvaged branch the unit is deliberately kept. Said out loud because the
            // run's row stays open until either a retry or an operator clears it.
            LOG.errorf(e, "run %s: its reclamation could not be reported, so the run's row stays"
                    + " open", result.runId());
            return false;
        }
    }
}
