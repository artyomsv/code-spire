package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
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

    /**
     * The transcript line a cancel writes, spelled the same as {@link RunControlListener}'s.
     *
     * <p>An operator reading a run that simply stopped cannot tell a deliberate stop from a fault,
     * and the two places that can stop a run must not label it differently.
     */
    static final String CANCELLED_NOTE = "CANCELLED";

    /**
     * Recorded by {@link RunControlListener} when a cancel arrives for a run no replica is
     * executing yet, and read TWICE here — before anything is created, and again the moment the
     * unit becomes addressable.
     *
     * <p><b>It is durable because the window it covers is.</b> The registry a cancel normally
     * reaches is in memory and is only populated once {@code create} RETURNS. Before that point a
     * run may be queued on the topic, cloning, or dispatch-uncertain with its record unconsumed,
     * and in every one of those the listener found nothing live, wrote a debug line, and the run
     * then started anyway and spent its whole wall clock. The endpoint had already answered 202.
     *
     * <p><b>One read only MOVED the window; it takes two to close it.</b> {@code create} blocks on
     * the image pull and the init clone — bounded at ten and fifteen minutes on the Docker arm —
     * so a cancel arriving after the first read and before registration found an empty registry,
     * wrote this claim, and nothing read it again. Up to twenty-five minutes in which a cancel was
     * accepted and silently dropped: the exact defect this slot exists to remove, relocated rather
     * than closed. The second read sits immediately after {@link RunRegistry#register}, and the
     * pairing is what makes it airtight — the listener writes this claim only after finding the
     * registry EMPTY, so whichever side wins the race, one of the two reads sees it. A cancel
     * arriving after registration reaches the registry directly and needs no claim at all.
     *
     * <p>Three reviews each closed a different half of this one hole — the executing case, the
     * dispatch-uncertain case, then the queued case — before anyone read the whole path.
     *
     * <p>READ, never claimed, by the dispatcher. Consuming it would let a redelivery of the same
     * command start the run the operator cancelled.
     */
    static final String CANCEL_SLOT = "cancel";

    static final String RUN_ID_MDC = "runId";

    private static final CompletionStage<Void> DONE = CompletableFuture.completedFuture(null);

    @Inject
    RunClaimStore claims;

    /**
     * Only to stop a unit cancelled while it was being created — see
     * {@code stopIfCancelledWhileCreating}. Everything else about placement belongs to the
     * launcher; this dispatcher does not create, salvage or destroy.
     */
    @Inject
    RunRuntime runtime;

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
            // Loud, because this branch cannot cancel anything: control rides cs.run-control into a
            // listener beside this executor, and a cancel read HERE queues behind the very run it
            // means to stop. The old line said "cancel requested" and acked, which reads as though
            // something happened — the same silent no-op the control topic was created to remove,
            // reachable now only by a DLQ replay onto the wrong topic or a stale producer.
            // RunControlListener warns about the mirror-image case for the same reason.
            LOG.warnf("run %s: a cancel arrived on cs.run-commands, where it cannot take effect and"
                            + " was NOT applied (reason: %s). Control rides cs.run-control; re-send it"
                            + " there.", cancel.runId(), cancel.reason());
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

        // Before ANY of it: the unit, the lease, the credentials, the money. A cancel that
        // arrived while this record was still on the topic is recorded in the claim table and
        // nowhere else, because no replica had the run in memory to stop.
        //
        // GUARDED, because this is past the ack. RunClaimStore.taken fails closed by throwing,
        // which is the right direction -- a database fault must not start a run somebody
        // cancelled -- but an exception escaping HERE breaks this class's own rule that nothing
        // after the ack may throw: the record is settled, the execute slot is claimed, and the run
        // would sit in `queued` for ever with no terminal result, refusing every redelivery. The
        // lease guard eleven lines below already had this shape; this one did not.
        boolean cancelledBeforeStart;
        try {
            cancelledBeforeStart = claims.taken(execute.runId(), CANCEL_SLOT);
        } catch (RuntimeException e) {
            LOG.errorf("run %s: its cancel claim could not be read (%s); no unit is created",
                    execute.runId(), e.getClass().getSimpleName());
            emit(failures.of(execute, RunFailureCause.WORKER_FAILED.name(),
                    "the run's cancel claim could not be read, so no sandbox was created"));
            return DONE;
        }
        if (cancelledBeforeStart) {
            LOG.info("cancelled before it started; no unit is created");
            emit(failures.of(execute, RunFailureCause.CANCELLED.name(),
                    "cancelled before the run started, so no sandbox was created"));
            return DONE;
        }

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
        try {
            emit(asCancellationIfCancelled(execute, result));
        } finally {
            // In a finally because a leaked registry entry never expires, and isExecuting() is the
            // watchdog's one absolute exemption — "this process is running it, no lease state can
            // outrank that". A throw between the emit and the forget would therefore make a
            // credential-bearing sandbox permanently unreclaimable by the mechanism that exists to
            // reclaim it. emit is guarded, but asCancellationIfCancelled reaches SecretScrub, which
            // is not, so "nothing after the ack may throw" was a rule this line did not enforce.
            registry.forget(execute.runId());
            keeper.settle();
        }
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
        public void unitCreated(String unitId, RunNotes notes) {
            unitExists = true;
            // Registered the instant the sandbox exists. From here a cancel reaches the run through
            // the registry like any other; before here it could only have been recorded as a claim,
            // which is what stopIfCancelledWhileCreating reads below. The run's transcript is
            // registered with it, so a control action writes into the run's own numbered stream
            // rather than a second counter that would collide with the agent's.
            registry.register(runId, harness, new RunHandle(runId, unitId), notes);
            // The lease is recorded BEFORE the started event is emitted, deliberately: the cheap
            // local write goes first, so a broker that stalls cannot leave the lease unable to name
            // the unit an operator would then be looking for.
            leases.recordUnit(runId, unitId);
            // RunStarted is emitted HERE rather than before the launch, because only here does a
            // unit id exist. It used to carry the run id twice, so the one field meant to point an
            // operator at a preserved sandbox pointed at nothing.
            emit(new RunResult.RunStarted(runId, unitId));
            stopIfCancelledWhileCreating(unitId, notes);
        }

        /**
         * The second read of {@link #CANCEL_SLOT}, and the one that closes the window rather than
         * moving it.
         *
         * <p>{@code create} blocks for as long as an image pull and an init clone take. A cancel
         * arriving in there found an empty registry and wrote the claim, and the dispatcher's
         * pre-create read had already happened. Reading again HERE — after registration, so the two
         * orderings interleave safely — is what leaves no window.
         *
         * <p>Ordered after the lease and the started event on purpose. A unit stopped before its
         * lease names it is a sandbox the watchdog cannot attribute, which is the state the lease
         * ordering above exists to prevent; stopping a fully accounted-for unit costs one extra
         * event and loses nothing.
         *
         * <p>Everything here is best-effort and swallowed, deliberately and twice over. The caller
         * ({@code RunLauncher.announce}) already discards anything thrown from this callback, so a
         * throw would be invisible rather than fatal; and a database fault at this instant must not
         * cost a RUNNING unit its outcome. Failing to stop it degrades to the previous behaviour —
         * the run finishes and reports honestly — which is why this is ERROR-logged rather than
         * rethrown.
         */
        private void stopIfCancelledWhileCreating(String unitId, RunNotes notes) {
            try {
                if (!claims.taken(runId, CANCEL_SLOT)) {
                    return;
                }
                LOG.warnf("run %s: cancelled while its unit was being created; stopping it now", runId);
                // Through the registry, so the terminal result is relabelled CANCELLED instead of
                // reporting the agent failure a killed process produces.
                registry.cancel(runId);
                notes.note(CANCELLED_NOTE,
                        "stopped by an operator: cancelled while the sandbox was being created", false);
                runtime.cancel(new RunHandle(runId, unitId));
            } catch (RuntimeException e) {
                LOG.errorf(e, "run %s: it could not be stopped after a cancel arrived during its"
                                + " creation (%s); it continues until its wall clock",
                        runId, e.getClass().getSimpleName());
            }
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
                            + " changed paths, " + finished.blocked().size() + " blocked); the branch"
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
