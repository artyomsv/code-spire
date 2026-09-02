package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import io.smallrye.common.annotation.Blocking;
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
 * behind it. {@code @Blocking} without {@code ordered} keeps the Docker call off the event loop
 * while letting two cancels for different runs proceed independently.
 *
 * <p><b>A cancel for a run this replica is not executing is not an error.</b> It arrived late, or
 * twice, or it belongs to a sibling — and a listener that failed on any of those would stop
 * delivering the cancels that still matter.
 */
@ApplicationScoped
public class RunControlListener {

    private static final Logger LOG = Logger.getLogger(RunControlListener.class);

    private static final String RUN_ID_MDC = "runId";

    @Inject
    RunRegistry registry;

    @Inject
    RunRuntime runtime;

    @Incoming("run-control-in")
    @Blocking
    public void onControl(RunCommand command) {
        if (command == null) {
            // A poison record: the deserializer already logged it and the failure strategy has it.
            return;
        }
        MDC.put(RUN_ID_MDC, command.runId());
        try {
            if (command instanceof RunCommand.CancelRun cancel) {
                cancel(cancel);
                return;
            }
            // Only control belongs here. An ExecuteRun on this topic would otherwise be silently
            // ignored, which is the shape of a run that never happens and nobody is told about.
            LOG.warnf("ignoring %s on the control topic; execution rides cs.run-commands",
                    command.getClass().getSimpleName());
        } finally {
            MDC.remove(RUN_ID_MDC);
        }
    }

    private void cancel(RunCommand.CancelRun request) {
        Optional<RunHandle> handle = registry.cancel(request.runId());
        if (handle.isEmpty()) {
            // Late, duplicate, or another replica's. Logged at INFO rather than WARN: an operator
            // cancelling a run that has just finished has done nothing wrong, and a warning with no
            // action implied is noise.
            LOG.infof("cancel ignored: this replica is not executing %s (%s)",
                    request.runId(), request.reason());
            return;
        }
        LOG.warnf("cancelling %s: %s", request.runId(), request.reason());
        stop(handle.orElseThrow(), request.runId());
    }

    /**
     * Stop the sandbox, and let the launcher finish the run.
     *
     * <p>Nothing is salvaged or destroyed here. The launcher is still blocked on the agent's exit,
     * so killing the containers makes that call return and the ordinary terminal path runs — which
     * already salvages before it destroys. A cancel that salvaged for itself would race the launcher
     * for the same streams, and one that destroyed would throw away the checkpoints the run had
     * already pushed. Cancel is not delete.
     */
    private void stop(RunHandle handle, String runId) {
        try {
            runtime.cancel(handle);
        } catch (RuntimeException e) {
            // Reported, not rethrown. A control record that nacks is redelivered and tries to cancel
            // a run that may by then have ended normally, and the registry has already recorded the
            // cancellation so the result will say so either way.
            LOG.errorf(e, "run %s: its sandbox could not be stopped (%s); the run continues until its"
                    + " wall clock", runId, e.getClass().getSimpleName());
        }
    }
}
