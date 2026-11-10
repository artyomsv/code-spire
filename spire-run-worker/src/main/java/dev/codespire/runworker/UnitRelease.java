package dev.codespire.runworker;

import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import org.jboss.logging.Logger;

/**
 * How {@link RunLauncher} stops or removes a unit: its agent stopped once the push gate refuses, and once the result is
 * decided the unit destroyed after a good salvage, or stopped and kept for inspection after a failed one. A held
 * build's unit is neither, because publication resumes from it.
 *
 * <p>Every one is guarded: a fault here must not cost the run its outcome or its measured spend.
 */
final class UnitRelease {

    /** The launcher's category, so these lines read in its log as they always have. */
    private static final Logger LOG = Logger.getLogger(RunLauncher.class);

    private final RunRuntime runtime;
    private final String runId;

    UnitRelease(RunRuntime runtime, String runId) {
        this.runtime = runtime;
        this.runId = runId;
    }

    /**
     * Stop the agent once the push gate has refused this run.
     *
     * <p>A refusal terminates the PUBLISHER: it stops reading bundles and exits, and nothing
     * reaches the remote. The agent knows none of that and keeps working until it finishes or
     * its wall clock expires, buying model calls for work that can never be published — which is
     * the whole cost of the refusal falling on the operator rather than on the run.
     *
     * <p>Called from the publisher reader, so it runs the moment the refusal is read rather than
     * at salvage, when the money is already spent. The caller holds the once-only latch, because
     * the publisher keeps emitting lines after it refuses.
     */
    void stopAgent(RunHandle handle) {
        LOG.warnf("run %s: the push gate refused, so the agent is stopped rather than left to spend"
                + " its wall clock on work that cannot be published", runId);
        try {
            runtime.cancel(handle);
        } catch (RuntimeException e) {
            // Reported, not rethrown: this runs on the publisher's reader thread, and throwing
            // here would end that stream and lose the blocked paths the refusal is about to be
            // reported with. The wall clock remains the bound.
            LOG.errorf(e, "run %s: the agent could not be stopped after the gate refused (%s)",
                    runId, e.getClass().getSimpleName());
        }
    }

    /**
     * Guarded for the same reason {@link #stopPreserved} is, and the reason is sharper here: by this line the run's
     * outcome AND its measured spend are already decided, so a daemon blip during teardown would propagate out of
     * launch, be caught by the dispatcher, and rewrite a successful run as WORKER_FAILED with its usage dropped —
     * discarding exactly the charge the ledger exists to keep. A leaked unit is recoverable by its label; a discarded
     * result is not.
     */
    void destroy(RunHandle handle, RunObserver observer) {
        try {
            runtime.destroy(handle);
            // The ONE place a unit is known gone. Reporting it here rather than letting the
            // caller infer it from the result is what closed four leaking paths: an init
            // failure the arm deliberately leaves behind, a throwing salvage after a publisher
            // failure, this catch, and anything escaping the launcher's loop.
            observer.unitReleased();
        } catch (RuntimeException e) {
            LOG.warnf("run %s: the unit could not be destroyed (%s); it is labelled for cleanup"
                    + " and keeps its lease, because it is still there",
                    runId, e.getClass().getSimpleName());
        }
    }

    /**
     * Preserved, and STOPPED. That an overrun kills the agent is one arm's private promise, not something the SPI
     * states — and a run now reported finished makes an operator rely on it. cancel() is an idempotent kill, and the
     * publisher has already had its drain window inside salvage, so nothing is lost. A throwing cancel must not lose
     * the result.
     */
    void stopPreserved(RunHandle handle, Finalization finalization) {
        try {
            runtime.cancel(handle);
        } catch (RuntimeException e) {
            LOG.warnf("run %s: the preserved unit could not be stopped (%s)",
                    runId, e.getClass().getSimpleName());
        }
        LOG.warnf("run %s preserved for inspection: %s", runId, finalization.detail());
    }
}
