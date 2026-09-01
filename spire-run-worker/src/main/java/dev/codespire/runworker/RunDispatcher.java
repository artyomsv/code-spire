package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Claim, ack, then run.
 *
 * <p><b>The order is the design.</b> This channel acks on receipt, because an hour-long run cannot
 * ride an ordered-blocking channel — that pairing once stalled a consumer which re-stalled on every
 * restart and needed a manual offset seek. Kafka therefore does not stop a redelivery;
 * {@link RunClaimStore} does, and the claim must be written BEFORE the ack. The reverse loses the
 * command entirely on a crash between them.
 *
 * <p>A poison record arrives as null, because a deserializer that throws kills the consumer and the
 * record is then redelivered on every restart. It is dropped with a log rather than retried.
 */
@ApplicationScoped
public class RunDispatcher {

    private static final Logger LOG = Logger.getLogger(RunDispatcher.class);

    static final String EXECUTE_SLOT = "execute";

    @Inject
    RunClaimStore claims;

    @Inject
    RunLauncher launcher;

    @Inject
    @Channel("run-results-out")
    Emitter<Record<String, RunResult>> results;

    @Incoming("run-commands-in")
    public void onCommand(RunCommand command) {
        if (command == null) {
            LOG.warn("dropping an unreadable run command; it is on cs.dlq");
            return;
        }
        if (command instanceof RunCommand.CancelRun cancel) {
            LOG.infof("cancel requested for %s: %s", cancel.runId(), cancel.reason());
            return;
        }
        if (!(command instanceof RunCommand.ExecuteRun execute)) {
            return;
        }
        if (!claims.claim(execute.runId(), EXECUTE_SLOT)) {
            // A redelivery. Not an error, and NOT a reason to re-run the agent: the first delivery
            // either finished or is finishing, and a second unit would spend money twice.
            LOG.infof("run %s is already claimed; this is a redelivery", execute.runId());
            return;
        }

        emit(new RunResult.RunStarted(execute.runId(), execute.runId()));
        RunResult result;
        try {
            result = launcher.launch(execute);
        } catch (RuntimeException e) {
            // Never let an unexpected failure leave a run with no terminal result: a run that
            // reports nothing is indistinguishable from one still working, and the operator's only
            // signal would be its eventual absence.
            LOG.errorf(e, "run %s failed unexpectedly", execute.runId());
            result = new RunResult.RunFailed(execute.runId(), "WORKER_FAILED",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
        emit(result);
    }

    private void emit(RunResult result) {
        results.send(Record.of(result.runId(), result));
    }
}
