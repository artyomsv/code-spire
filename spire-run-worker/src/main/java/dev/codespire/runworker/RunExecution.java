package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * One run on one lane: claim, ack, cancel read, lease, launch, report. Shared by the build lane and the review lane
 * (harness reviews, step 4), so a review run is deduplicated, cancelled and leased by the same lines as a build.
 *
 * <p>The claim on {@code run_claim} is the sole idempotency mechanism — Kafka redelivers, the claim refuses. The record
 * is acked the moment the claim is written and BEFORE the run, so its age never reaches the connector's threshold while
 * the agent works; everything after the ack is idempotent by the claim. So nothing after the ack may throw: the record
 * is settled and the claim is taken, and a throw would only dead-letter a command that has already run.
 */
final class RunExecution {

    private static final Logger LOG = Logger.getLogger(RunExecution.class);

    static final CompletionStage<Void> DONE = CompletableFuture.completedFuture(null);

    private final RunLane lane;

    private final LaneResults results;

    RunExecution(RunLane lane) {
        this.lane = lane;
        this.results = new LaneResults(lane.results(), lane.resultAckSeconds());
    }

    /** @param launch starts the run's unit for this lane, reporting the unit to the observer it is given */
    CompletionStage<Void> execute(Message<RunCommand> message, RunCommand.ExecuteRun execute,
                                  Function<RunObserver, RunResult> launch) {
        if (!lane.claims().claim(execute.runId(), RunDispatcher.EXECUTE_SLOT)) {
            // A redelivery. Not an error, and NOT a reason to re-run the agent: the first delivery
            // either finished or is finishing, and a second unit would spend money twice.
            LOG.info("already claimed; this is a redelivery");
            ack(message);
            return DONE;
        }
        ack(message);
        if (refusedBeforeStart(execute)) {
            return DONE;
        }
        run(execute, launch);
        return DONE;
    }

    /**
     * Before ANY of it — the unit, the lease, the credentials, the money — a cancel that arrived while this record was
     * still on the topic, recorded in the claim table because no replica had the run in memory to stop.
     *
     * <p>GUARDED, because this is past the ack: {@code RunClaimStore.taken} fails closed by throwing, and a throw here
     * would leave the run queued for ever with its execute slot claimed. Then the lease BEFORE the unit: a crash between
     * the two leaves a lease with no unit, which the watchdog can reconcile, where the reverse leaves a sandbox holding a
     * credential that nothing knows exists. Refused rather than swallowed, because no money is spent yet.
     */
    private boolean refusedBeforeStart(RunCommand.ExecuteRun execute) {
        boolean cancelledBeforeStart;
        try {
            cancelledBeforeStart = lane.claims().taken(execute.runId(), RunDispatcher.CANCEL_SLOT);
        } catch (RuntimeException e) {
            LOG.errorf("run %s: its cancel claim could not be read (%s); no unit is created",
                    execute.runId(), e.getClass().getSimpleName());
            return refuse(execute, RunFailureCause.WORKER_FAILED,
                    "the run's cancel claim could not be read, so no sandbox was created");
        }
        if (cancelledBeforeStart) {
            LOG.info("cancelled before it started; no unit is created");
            return refuse(execute, RunFailureCause.CANCELLED, "cancelled before the run started, so no sandbox was created");
        }
        if (!lane.leases().take(execute.runId())) {
            return refuse(execute, RunFailureCause.WORKER_FAILED, "the run's lease could not be taken, so no unit was created");
        }
        return false;
    }

    private boolean refuse(RunCommand.ExecuteRun execute, RunFailureCause cause, String detail) {
        results.emit(lane.failures().of(execute, cause.name(), detail));
        return true;
    }

    private void run(RunCommand.ExecuteRun execute, Function<RunObserver, RunResult> launch) {
        // From here the run's credentials are in a container that may log them back; see LiveSecrets.
        LiveSecrets.register(execute.runId(), () -> lane.failures().scrubFor(execute));
        RunLeaseKeeper keeper = new RunLeaseKeeper(lane, results, execute);
        RunResult result = launched(execute, launch, keeper);
        // Read BEFORE the registry forgets the run, and applied before the result is published: a cancellation not
        // reflected here is a run an operator stopped being reported as one that broke.
        try {
            results.emit(asCancellationIfCancelled(execute, result));
        } finally {
            // In a finally because a leaked registry entry never expires, and isExecuting() is the watchdog's one
            // absolute exemption, so a throw between the emit and the forget would make a credential-bearing sandbox
            // permanently unreclaimable by the mechanism that exists to reclaim it.
            lane.registry().forget(execute.runId());
            // A preserved unit may still log; the watchdog forgets it when it reaps the unit.
            if (keeper.settle()) LiveSecrets.forget(execute.runId());
        }
    }

    private RunResult launched(RunCommand.ExecuteRun execute, Function<RunObserver, RunResult> launch,
                               RunLeaseKeeper keeper) {
        try {
            return launch.apply(keeper);
        } catch (RuntimeException e) {
            // Never let an unexpected failure leave a run with no terminal result: a run that reports nothing is
            // indistinguishable from one still working. Logged without the exception: its message is the text
            // RunFailures is about to scrub, and a stack trace beside a redacted detail defeats the redaction.
            LOG.errorf("run %s failed unexpectedly (%s)", execute.runId(), e.getClass().getName());
            return lane.failures().of(execute, "WORKER_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Relabel a cancelled run's outcome.
     *
     * <p>Cancelling kills the agent, so the launcher sees a non-zero exit and classifies an ordinary agent failure. That
     * is true of what it observed and wrong about what happened: an operator who stopped a run must not be told it
     * broke, and CANCELLED is not retryable. A run that FINISHED is left alone: a cancel racing the last second of a
     * successful push did not undo the push, and a review's answer that arrived is still the answer it paid for.
     */
    private RunResult asCancellationIfCancelled(RunCommand.ExecuteRun execute, RunResult result) {
        if (!lane.registry().wasCancelled(execute.runId()) || !(result instanceof RunResult.RunFailed failed)) {
            return result;
        }
        return lane.failures().of(execute, RunFailureCause.CANCELLED.name(),
                "cancelled while running; the agent reported " + failed.cause())
                .withUsage(failed.tokenUsage()).withEgress(failed.egress());
    }

    static void ack(Message<RunCommand> message) {
        message.ack().toCompletableFuture().join();
    }
}
