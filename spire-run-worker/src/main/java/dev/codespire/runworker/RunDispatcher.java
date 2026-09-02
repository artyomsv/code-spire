package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.runtime.RunHandle;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    

    private static final CompletionStage<Void> DONE = CompletableFuture.completedFuture(null);

    @Inject
    RunClaimStore claims;

    @Inject
    RunLauncher launcher;

    @Inject
    WorkspaceLeases leases;

    @Inject
    RunRegistry registry;

    /**
     * One ack budget for both publish paths.
     *
     * <p>It was a constant here and a property on the reclamation reporter -- two numbers for one
     * broker wait, free to drift, which is the shape this module keeps having to fix.
     */
    @ConfigProperty(name = "spire.run.result-ack-seconds")
    long resultAckSeconds;

    @Inject
    RunFailures failures;

    @Inject
    @Channel("run-results-out")
    Emitter<Record<String, RunResult>> results;

    /** The one writer to the transcript channel; the control listener shares it. */
    @Inject
    RunTranscript transcript;

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

        // The lease BEFORE the unit. A crash between the two leaves a lease with no unit, which
        // the watchdog can reconcile against the daemon; the reverse leaves a sandbox holding a
        // credential that nothing knows exists.
        //
        // Refused rather than swallowed, uniquely among the lease writes: every other one happens
        // AFTER the money is spent, where throwing would discard a terminal result to protect
        // bookkeeping. Here there is no result yet, so continuing without a lease would produce
        // exactly the forbidden state for the whole life of the run. One un-run command is cheaper.
        if (!leases.take(execute.runId())) {
            emit(failures.of(execute, RunFailureCause.WORKER_FAILED.name(),
                    "the run's lease could not be taken, so no unit was created"));
            return DONE;
        }

        LeaseKeeper keeper = new LeaseKeeper(execute.runId(), execute.harness());
        RunResult result;
        try {
            result = launcher.launch(execute, keeper);
        } catch (RuntimeException e) {
            // Never let an unexpected failure leave a run with no terminal result: a run that
            // reports nothing is indistinguishable from one still working.
            // Logged without the exception: its message is the text RunFailures is about to scrub,
            // and a stack trace beside a redacted detail defeats the redaction.
            LOG.errorf("run %s failed unexpectedly (%s)", execute.runId(), e.getClass().getName());
            result = failures.of(execute, "WORKER_FAILED",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // Read BEFORE the registry forgets the run, and applied before the result is published:
        // the terminal result is the only thing that reaches the orchestrator, so a cancellation
        // not reflected here is a run an operator stopped being reported as one that broke.
        emit(asCancellationIfCancelled(execute, result));
        registry.forget(execute.runId());
        keeper.settle();
        return DONE;
    }

    /**
     * Keeps the run's lease in step with what the launcher reports about its sandbox.
     *
     * <p>The lease used to be released by re-deriving "was the unit destroyed" from the run's wire
     * result, and a review found four paths where that inference was wrong in the leaking
     * direction: an init-container failure the Docker arm deliberately leaves behind, a salvage
     * that throws after the publisher reported a push failure, a {@code destroy} that itself
     * throws, and anything escaping the observation loop. On each, a sandbox holding a live model
     * credential survived with no lease naming it.
     *
     * <p>So this acts on knowledge instead. <b>Silence means the unit is still there</b>, and the
     * default therefore leaks a row rather than a container — a stale row costs one reconcile
     * against the daemon, a missing row costs a credential nobody can find.
     */
    private final class LeaseKeeper implements RunObserver {

        private final String runId;

        /** Recorded with the run, because a steer arrives carrying only a run id. */
        private final String harness;

        private boolean unitExists;

        private boolean unitGone;

        private LeaseKeeper(String runId, String harness) {
            this.runId = runId;
            this.harness = harness;
        }

        @Override
        public void event(RunEventRecord record) {
            transcript.emit(record, (sent, refused) -> {
                if (refused != null) {
                    // The channel does not wait for write completion, so a broker refusal arrives
                    // here and nowhere else. Discarding this stage meant the stream's own "gap"
                    // warning could not fire for the most likely loss.
                    LOG.warnf("run %s: transcript event %d was refused by the broker (%s)",
                            record.runId(), record.sequence(), refused.getClass().getSimpleName());
                }
            });
        }

        @Override
        public void unitCreated(String unitId) {
            unitExists = true;
            // Registered the instant the sandbox exists, so the window in which a cancel cannot
            // reach the run is the container's creation and nothing more.
            registry.register(runId, harness, new RunHandle(runId, unitId));
            // The lease is recorded BEFORE the started event is emitted, deliberately: the cheap
            // local write goes first, so a broker that stalls cannot leave the lease unable to name
            // the unit an operator would then be looking for.
            leases.recordUnit(runId, unitId);
            // RunStarted is emitted HERE rather than before the launch, because only here does a
            // unit id exist. It used to carry the run id twice, so the one field meant to point an
            // operator at a preserved sandbox pointed at nothing.
            emit(new RunResult.RunStarted(runId, unitId));
        }

        @Override
        public void unitReleased() {
            unitGone = true;
        }

        /**
         * Release the lease if the sandbox is gone, stamp it preserved if one is still there.
         *
         * <p>A preserved unit KEEPS its lease — it is exactly what the orphan watchdog exists to
         * find — but it is STAMPED rather than merely left alone, which stops the heartbeat from
         * refreshing it forever and so lets it become findable at all.
         */
        private void settle() {
            if (!unitExists || unitGone) {
                leases.release(runId);
                return;
            }
            LOG.infof("run %s: its unit was not destroyed, so the lease is kept for the watchdog", runId);
            leases.preserve(runId);
        }
    }

    /**
     * Relabel a cancelled run's outcome.
     *
     * <p>Cancelling kills the agent, so the launcher sees a non-zero exit and classifies an
     * ordinary agent failure. That is true of what it observed and wrong about what happened —
     * an operator who stopped a run must not be told it broke, and CANCELLED is not retryable
     * while AGENT_FAILED's neighbours are.
     *
     * <p>A run that FINISHED is left alone. A cancel racing the last second of a successful push
     * did not undo the push, and rewriting a delivered branch as cancelled would lose it.
     */
    private RunResult asCancellationIfCancelled(RunCommand.ExecuteRun execute, RunResult result) {
        if (!registry.wasCancelled(execute.runId()) || !(result instanceof RunResult.RunFailed failed)) {
            return result;
        }
        return failures.of(execute, RunFailureCause.CANCELLED.name(),
                "cancelled while running; the agent reported " + failed.cause())
                .withUsage(failed.tokenUsage());
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
                    RunFailureCause.RESULT_UNPUBLISHABLE.isRetryable(),
                    // The agent's spend survives the result being too large to publish. Dropping
                    // it here would lose the charge for a run that definitely ran, and this
                    // failure is the ONLY record of it that reaches the orchestrator.
                    finished.tokenUsage());
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
                    .get(resultAckSeconds, TimeUnit.SECONDS);
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
            LOG.errorf("no broker acknowledgment within %ds for %s", resultAckSeconds, describe(result));
            return false;
        }
    }

    private static String describe(RunResult result) {
        return result.getClass().getSimpleName() + " for run " + result.runId();
    }
}
