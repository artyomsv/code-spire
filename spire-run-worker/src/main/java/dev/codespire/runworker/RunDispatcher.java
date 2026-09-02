package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunFailureCause;
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
 * Consumes {@code cs.run-commands}: claim, ack, launch, report.
 *
 * <p>The claim on {@code run_claim} is the sole idempotency mechanism — Kafka redelivers, the claim
 * refuses. The record is acked the moment the claim is written and BEFORE the run, so its age never
 * reaches the connector's threshold while the agent works for an hour; everything after the ack is
 * idempotent by the claim. That ordering has one consequence this class must respect: nothing after
 * the ack may throw. The record is settled and the claim is taken, so a throw here would only
 * dead-letter a command that has already run — a phantom the operator is invited to replay into a
 * claim that drops it.
 */
@ApplicationScoped
public class RunDispatcher {

    private static final Logger LOG = Logger.getLogger(RunDispatcher.class);

    static final String EXECUTE_SLOT = "execute";

    static final String RUN_ID_MDC = "runId";

    private static final long RESULT_ACK_SECONDS = 30;

    private static final CompletionStage<Void> DONE = CompletableFuture.completedFuture(null);

    @Inject
    RunClaimStore claims;

    @Inject
    RunLauncher launcher;

    @Inject
    RunFailures failures;

    @Inject
    @Channel("run-results-out")
    Emitter<Record<String, RunResult>> results;

    @Incoming("run-commands-in")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    @Blocking(ordered = true)
    public CompletionStage<Void> onCommand(Message<RunCommand> message) {
        RunCommand command = message.getPayload();
        if (command == null) {
            LOG.warn("nacking an unreadable run command; the failure strategy dead-letters it");
            return message.nack(new IllegalArgumentException("unreadable run command"));
        }
        // The run id on the MDC for the WHOLE handling, cancel and redelivery included: a cancel
        // and a redelivery are the two lines an operator most needs to find by run id.
        MDC.put(RUN_ID_MDC, command.runId());
        try {
            return handle(message, command);
        } finally {
            MDC.remove(RUN_ID_MDC);
        }
    }

    private CompletionStage<Void> handle(Message<RunCommand> message, RunCommand command) {
        if (command instanceof RunCommand.CancelRun cancel) {
            LOG.infof("cancel requested: %s", cancel.reason());
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
            LOG.info("already claimed; this is a redelivery");
            ack(message);
            return DONE;
        }
        ack(message);

        emit(new RunResult.RunStarted(execute.runId(), execute.runId()));
        RunResult result;
        try {
            result = launcher.launch(execute);
        } catch (RuntimeException e) {
            // Never let an unexpected failure leave a run with no terminal result: a run that
            // reports nothing is indistinguishable from one still working.
            // Logged without the exception: its message is the text RunFailures is about to scrub,
            // and a stack trace beside a redacted detail defeats the redaction.
            LOG.errorf("run %s failed unexpectedly (%s)", execute.runId(), e.getClass().getName());
            result = failures.of(execute, "WORKER_FAILED",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        emit(result);
        return DONE;
    }

    private static void ack(Message<RunCommand> message) {
        message.ack().toCompletableFuture().join();
    }

    /**
     * Publishes with a bounded wait for the broker's acknowledgment. A result the broker refuses is
     * logged at ERROR and replaced, once, by a compact {@code RunFailed} that carries no path lists —
     * the shape that gets refused is a huge one — so the orchestrator still learns the run ended.
     * Never rethrown: see the class comment.
     */
    private void emit(RunResult result) {
        if (publish(result)) {
            return;
        }
        if (result instanceof RunResult.RunFinished finished) {
            // Its own cause rather than an alias of WORKER_FAILED, which answers "retryable". The
            // broker refused this result twice, the second time compacted, so re-running the agent
            // produces a result it refuses again for the same reason. An operator raises the record
            // limit; nobody re-runs anything. Different person, different action, different value.
            RunResult compact = new RunResult.RunFailed(result.runId(), "RESULT_UNPUBLISHABLE",
                    "the broker refused the run's full result (" + finished.changedPaths().size()
                            + " changed paths, " + finished.blockedPaths().size() + " blocked); the branch"
                            + (finished.pushedRef() == null ? " was not pushed" : " is at " + finished.pushedRef()),
                    RunFailureCause.RESULT_UNPUBLISHABLE.isRetryable());
            if (publish(compact)) {
                return;
            }
        }
        LOG.errorf("run %s is complete but unreported: the broker refused its result twice", result.runId());
    }

    private boolean publish(RunResult result) {
        try {
            // send() itself can throw synchronously — a channel with no subscriber, or one whose
            // downstream was cancelled at shutdown — and that throw would be the one escaping after
            // the ack. Inside the guard with the rest.
            results.send(Record.of(result.runId(), result)).toCompletableFuture()
                    .get(RESULT_ACK_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (IllegalStateException e) {
            LOG.errorf(e, "the channel refused %s", describe(result));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.errorf(e, "interrupted publishing %s", describe(result));
            return false;
        } catch (ExecutionException e) {
            LOG.errorf(e.getCause(), "the broker did not accept %s", describe(result));
            return false;
        } catch (TimeoutException e) {
            LOG.errorf("no broker acknowledgment within %ds for %s", RESULT_ACK_SECONDS, describe(result));
            return false;
        }
    }

    private static String describe(RunResult result) {
        return result.getClass().getSimpleName() + " for run " + result.runId();
    }
}
