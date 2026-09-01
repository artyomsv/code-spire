package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Claim, ack, then run.
 *
 * <p><b>The order is the design, and this class is where it is implemented rather than described.</b>
 * An hour-long run cannot ride an ordered-blocking channel with the framework acking after the
 * handler returns: that pairing once stalled a consumer which re-stalled on every restart and
 * needed a manual offset seek. So the ack is MANUAL and happens the moment the claim row is
 * written — before the run — and Kafka therefore does not stop a redelivery; {@link RunClaimStore}
 * does. The claim goes first because the reverse loses the command entirely on a crash between
 * them.
 *
 * <p>An earlier version stated all of this in three comments and implemented none of it: no
 * acknowledgment strategy, no {@code @Blocking}, and the connector's default 60-second
 * unprocessed-record threshold — so the first run longer than a minute would have killed the
 * consumer, on a channel whose whole purpose is runs that last up to the wall clock.
 *
 * <p>The handler is {@code @Blocking} because a run IS blocking, for its whole wall clock; on the
 * event loop that would stall every other channel in the process. {@code ordered = true} keeps one
 * run per consumer at a time, which is what the claim's per-run slot assumes.
 *
 * <p>A poison record arrives as null, because a deserializer that throws kills the consumer and the
 * record is then redelivered on every restart. It is nacked so the connector's failure strategy
 * dead-letters it, and dropped with a log.
 */
@ApplicationScoped
public class RunDispatcher {

    private static final Logger LOG = Logger.getLogger(RunDispatcher.class);

    static final String EXECUTE_SLOT = "execute";

    /** How long to wait for the broker to acknowledge a terminal result before calling it lost. */
    private static final long RESULT_ACK_SECONDS = 30;

    @Inject
    RunClaimStore claims;

    @Inject
    RunLauncher launcher;

    @Inject
    @Channel("run-results-out")
    Emitter<Record<String, RunResult>> results;

    /**
     * A handler that consumes a {@code Message} must return a {@code CompletionStage<Void>} — the
     * connector refuses to deploy a void one (SRMSG00051). The stage completes when the method
     * returns; the ack it is normally used for has already happened inside, by hand.
     */
    private static final CompletionStage<Void> DONE = CompletableFuture.completedFuture(null);

    @Incoming("run-commands-in")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    @Blocking(ordered = true)
    public CompletionStage<Void> onCommand(Message<RunCommand> message) {
        RunCommand command = message.getPayload();
        if (command == null) {
            LOG.warn("nacking an unreadable run command; the failure strategy dead-letters it");
            return message.nack(new IllegalArgumentException("unreadable run command"));
        }
        if (command instanceof RunCommand.CancelRun cancel) {
            LOG.infof("cancel requested for %s: %s", cancel.runId(), cancel.reason());
            ack(message);
            return DONE;
        }
        if (!(command instanceof RunCommand.ExecuteRun execute)) {
            ack(message);
            return DONE;
        }
        if (!claims.claim(execute.runId(), EXECUTE_SLOT)) {
            // A redelivery. Not an error, and NOT a reason to re-run the agent: the first delivery
            // either finished or is finishing, and a second unit would spend money twice.
            LOG.infof("run %s is already claimed; this is a redelivery", execute.runId());
            ack(message);
            return DONE;
        }
        // Claimed. Ack NOW, before the run, so the record's age never reaches the connector's
        // threshold while the agent works. Everything after this line is idempotent by the claim.
        ack(message);

        // The run id on the MDC rather than in each message, the way the review worker carries its
        // reviewId (structured JSON logs in prod, one field to filter on).
        MDC.put(RUN_ID_MDC, execute.runId());
        try {
            emit(new RunResult.RunStarted(execute.runId(), execute.runId()));
            RunResult result;
            try {
                result = launcher.launch(execute);
            } catch (RuntimeException e) {
                // Never let an unexpected failure leave a run with no terminal result: a run that
                // reports nothing is indistinguishable from one still working, and the operator's
                // only signal would be its eventual absence.
                LOG.error("run failed unexpectedly", e);
                result = new RunResult.RunFailed(execute.runId(), "WORKER_FAILED",
                        e.getClass().getSimpleName() + ": " + e.getMessage(), true);
            }
            emit(result);
            return DONE;
        } finally {
            MDC.remove(RUN_ID_MDC);
        }
    }

    static final String RUN_ID_MDC = "runId";

    private static void ack(Message<RunCommand> message) {
        message.ack().toCompletableFuture().join();
    }

    /**
     * Publishes and WAITS for the broker's acknowledgment.
     *
     * <p>The earlier version discarded the {@code CompletionStage}, so a publish the broker never
     * accepted was silent — and because the claim row was already taken, a redelivery did nothing,
     * leaving the run at {@code running} forever with no signal anywhere. The record is already
     * acked by the time a terminal result exists, so there is no nack path left to a dead-letter
     * topic; what remains is to make the loss LOUD, with the result in the log, rather than let the
     * only trace be the run's eventual absence.
     */
    private void emit(RunResult result) {
        CompletionStage<Void> sent = results.send(Record.of(result.runId(), result));
        try {
            sent.toCompletableFuture().get(RESULT_ACK_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing " + describe(result), e);
        } catch (ExecutionException e) {
            LOG.errorf(e.getCause(), "the broker did not accept %s — the run is complete but unreported",
                    describe(result));
            throw new IllegalStateException("could not publish " + describe(result), e.getCause());
        } catch (TimeoutException e) {
            LOG.errorf("no broker acknowledgment within %ds for %s — the run is complete but unreported",
                    RESULT_ACK_SECONDS, describe(result));
            throw new IllegalStateException("timed out publishing " + describe(result), e);
        }
    }

    private static String describe(RunResult result) {
        return result.getClass().getSimpleName() + " for run " + result.runId();
    }
}
