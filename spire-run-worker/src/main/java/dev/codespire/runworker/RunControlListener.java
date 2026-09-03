package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.runtime.RunRuntime;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.Optional;

/**
 * Control that must not queue behind the run it controls.
 *
 * <p>The command channel is ordered and blocking on purpose — the launcher holds it for the run's
 * whole duration, because an hour-long run cannot ride a channel that acks on completion. A cancel
 * delivered there would therefore be read when the run it cancels had already finished, which is
 * indistinguishable from the no-op this replaces.
 *
 * <p>So control has its own topic and its own listener, running beside the executor rather than
 * behind it. <b>{@code ordered = false} is load-bearing and is the reactive-messaging annotation,
 * not the common one.</b> The class previously used {@code io.smallrye.common.annotation.Blocking},
 * whose javadoc here claimed it left execution unordered — it has no such attribute, and Quarkus
 * maps its absence to {@code setBlockingExecutionOrdered(true)}. Control records were therefore
 * processed strictly one at a time, so a {@code cancel} hung on an unresponsive daemon blocked every
 * later cancel, for every other run. That is the outage this topic exists to prevent, reached from
 * inside instead of from the work channel.
 *
 * <p><b>Every replica reads every control record</b> (the channel's group id is per instance), so a
 * command for a run this replica is not executing is the ordinary case rather than an error, and is
 * passed over quietly. Whether the run exists at all is answered synchronously by the endpoint that
 * publishes the command, which can see the run's status; a listener cannot, because "not here" and
 * "nowhere" look identical from one replica.
 */
@ApplicationScoped
public class RunControlListener {

    private static final Logger LOG = Logger.getLogger(RunControlListener.class);

    /** Written when an instruction reached the agent. */
    private static final String STEERED = "STEERED";

    /** Written when it did not, whatever the reason — the line an operator must be able to see. */
    private static final String STEER_REFUSED = "STEER_REFUSED";

    /** Written when a human stopped the run, so the timeline says who ended it and why. */
    private static final String CANCELLED = "CANCELLED";

    @Inject
    RunRegistry registry;

    @Inject
    RunRuntime runtime;

    @Inject
    HarnessRegistry harnesses;

    @Incoming("run-control-in")
    @Blocking(ordered = false)
    public void onControl(RunCommand command) {
        if (command == null) {
            // A poison record: the deserializer already logged it and the failure strategy has it.
            return;
        }
        MDC.put(RunDispatcher.RUN_ID_MDC, command.runId());
        try {
            dispatch(command);
        } catch (RuntimeException e) {
            // The channel's failure strategy is `ignore`, so anything escaping here is dropped with
            // no record at all — the precise silence this class exists to remove. Everything below
            // is guarded individually; this is the backstop for whatever is not, and it must never
            // rethrow: a control channel that fails stops delivering the cancels that still matter.
            LOG.errorf(e, "run %s: its control command could not be handled (%s) and was not applied",
                    command.runId(), e.getClass().getSimpleName());
        } finally {
            MDC.remove(RunDispatcher.RUN_ID_MDC);
        }
    }

    private void dispatch(RunCommand command) {
        if (command instanceof RunCommand.CancelRun cancel) {
            cancel(cancel);
            return;
        }
        if (command instanceof RunCommand.SteerRun steer) {
            steer(steer);
            return;
        }
        // Only control belongs here. An ExecuteRun on this topic would otherwise be silently
        // ignored, which is the shape of a run that never happens and nobody is told about.
        LOG.warnf("ignoring %s on the control topic; execution rides cs.run-commands",
                command.getClass().getSimpleName());
    }

    /**
     * Deliver a new instruction to a run that is already going, or refuse VISIBLY.
     *
     * <p>Gated on what the harness declares rather than on what the runtime happens to support,
     * because the capability is the harness's fact: a one-shot agent has no session to steer whatever
     * the container could carry. Refusing here means the runtime's own refusal is a backstop rather
     * than the mechanism.
     *
     * <p>A refusal is logged at WARN and recorded on the run's transcript, never dropped. An operator
     * who steers a run and sees nothing cannot tell "not supported" from "the message was lost", and
     * the second sends them looking for a broker fault that is not there.
     */
    private void steer(RunCommand.SteerRun request) {
        // ONE read. Reading the handle and then the harness separately let a run end between them,
        // and the second read's null reached HarnessRegistry.forName, which rejects it — straight
        // out of this listener into a channel that ignores failures. No log, no note, nothing.
        Optional<RunRegistry.LiveRun> live = registry.liveRun(request.runId());
        if (live.isEmpty()) {
            LOG.debugf("steer for %s: not executing here", request.runId());
            return;
        }
        RunRegistry.LiveRun run = live.orElseThrow();
        if (!harnesses.forName(run.harness()).capabilities().steer()) {
            LOG.warnf("steer refused for %s: its harness does not support steering, so the"
                    + " instruction was not delivered", request.runId());
            refused(run, "the harness driving this run does not support steering");
            return;
        }
        deliver(run, request);
    }

    private void deliver(RunRegistry.LiveRun run, RunCommand.SteerRun request) {
        LOG.infof("steering %s", request.runId());
        try {
            runtime.steer(run.handle(), request.instruction());
            // The instruction itself, so the transcript shows what the agent was told rather than
            // only that it was told something. It is scrubbed on the way out like any other line.
            run.notes().note(STEERED, request.instruction(), false);
        } catch (UnsupportedOperationException e) {
            // The backstop firing means a harness declared a capability its runtime does not have.
            // That is a deployment fault worth naming, not a control-channel failure.
            LOG.errorf(e, "steer refused for %s: the harness declares steering but the runtime"
                    + " cannot deliver it", request.runId());
            refused(run, "the runtime cannot reach this run's agent");
        } catch (RuntimeException e) {
            LOG.errorf(e, "run %s: its steer instruction could not be delivered (%s)",
                    request.runId(), e.getClass().getSimpleName());
            refused(run, "the instruction could not be delivered (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * A line the operator needs to notice, rather than one that scrolls past.
     *
     * <p>Two named methods instead of one taking a boolean: every call site here passed a literal,
     * and at the call site {@code true} said nothing about what it meant.
     */
    private static void refused(RunRegistry.LiveRun run, String why) {
        run.notes().note(STEER_REFUSED, why, true);
    }

    private void cancel(RunCommand.CancelRun request) {
        Optional<RunRegistry.LiveRun> live = registry.cancel(request.runId());
        if (live.isEmpty()) {
            // Late, duplicate, or another replica's — and under a per-instance group id, most of
            // these are simply the other replicas. Nothing is wrong, so nothing is warned about.
            LOG.debugf("cancel for %s: not executing here (%s)", request.runId(), request.reason());
            return;
        }
        RunRegistry.LiveRun run = live.orElseThrow();
        LOG.warnf("cancelling %s: %s", request.runId(), request.reason());
        // On the transcript for the same reason a steer is: a run that simply stops, with the only
        // record a line in the worker's own log, leaves whoever reads the timeline unable to tell a
        // deliberate stop from a fault. Sharper here than for a steer — when the run had already
        // pushed a checkpoint the result is a RunFinished carrying its ref, deliberately not
        // relabelled, so without this line nothing anywhere says a human ended it.
        run.notes().note(CANCELLED, "stopped by an operator: " + request.reason(), false);
        stop(run, request.runId());
    }

    /**
     * Stop the sandbox, and let the launcher finish the run.
     *
     * <p>Nothing is salvaged or destroyed here. The launcher is still blocked on the agent's exit, so
     * killing the containers makes that call return and the ordinary terminal path runs — which
     * already salvages before it destroys. A cancel that salvaged for itself would race the launcher
     * for the same streams, and one that destroyed would throw away the checkpoints the run had
     * already pushed. Cancel is not delete.
     */
    private void stop(RunRegistry.LiveRun run, String runId) {
        try {
            runtime.cancel(run.handle());
        } catch (RuntimeException e) {
            // Reported, not rethrown. A control record that nacks is redelivered and tries to cancel
            // a run that may by then have ended normally, and the registry has already recorded the
            // cancellation so the result will say so either way.
            LOG.errorf(e, "run %s: its sandbox could not be stopped (%s); the run continues until its"
                    + " wall clock", runId, e.getClass().getSimpleName());
            run.notes().note(CANCELLED, "the sandbox could not be stopped; the run continues until"
                    + " its wall clock expires", true);
        }
    }
}
