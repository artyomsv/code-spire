package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
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

import java.util.concurrent.CompletionStage;

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

    @Inject
    RunClaimStore claims;

    /**
     * Only to stop a unit cancelled while it was being created — see
     * {@code RunLeaseKeeper.stopIfCancelledWhileCreating}. Everything else about placement belongs to the
     * launcher; this dispatcher does not create, salvage or destroy.
     */
    @Inject
    RunRuntime runtime;

    @Inject
    RunLauncher launcher;

    @Inject WorkRunWorker workRuns;
    @Inject WorkVerifyWorker verifies;

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
        if (command instanceof RunCommand.ExecuteWorkRun work) return workRuns.execute(message, work);
        // On the work channel on purpose: a verify takes the one execution slot, like a build (M4).
        if (command instanceof RunCommand.VerifyWork verify) return verifies.execute(message, verify);
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
            RunExecution.ack(message);
            return RunExecution.DONE;
        }
        return execute(message, command);
    }

    /**
     * A build, or a specification or plan run (M4 slice 3), which takes the same claim, lease and cancel reads and
     * launches read-only: its unit is destroyed with its result, so it never belongs in the held store. Anything else —
     * a review run (harness reviews, step 4) belongs on cs.review-runs — is acked and launched nowhere, and keeps its
     * execute slot free for the lane that owns it.
     */
    private CompletionStage<Void> execute(Message<RunCommand> message, RunCommand command) {
        if (command instanceof RunCommand.ExecuteWorkDraftRun draft) {
            return new RunExecution(lane()).execute(message, draft.execution(),
                    keeper -> launcher.launchReadOnly(draft, keeper));
        }
        if (command instanceof RunCommand.ExecuteRun plain) {
            return new RunExecution(lane()).execute(message, plain, keeper -> launcher.launch(plain, keeper));
        }
        if (command instanceof RunCommand.ExecuteReviewRun review) {
            LOG.warnf("run %s: a review run arrived on cs.run-commands, where it is NOT run; it rides"
                    + " cs.review-runs", review.runId());
        }
        RunExecution.ack(message);
        return RunExecution.DONE;
    }

    private RunLane lane() {
        return new RunLane(claims, runtime, leases, registry, failures, results, transcript, resultAckSeconds);
    }
}
