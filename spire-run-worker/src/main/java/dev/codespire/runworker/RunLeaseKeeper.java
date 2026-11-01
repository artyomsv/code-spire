package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.contract.event.RunResult;
import dev.codespire.runtime.RunHandle;
import org.jboss.logging.Logger;

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
final class RunLeaseKeeper implements RunObserver {

    private static final Logger LOG = Logger.getLogger(RunLeaseKeeper.class);

    private final RunLane lane;

    private final LaneResults results;

    private final String runId;

    /** Recorded with the run, because a steer arrives carrying only a run id. */
    private final String harness;

    private boolean unitExists;

    private boolean unitGone;

    RunLeaseKeeper(RunLane lane, LaneResults results, RunCommand.ExecuteRun execute) {
        this.lane = lane;
        this.results = results;
        this.runId = execute.runId();
        this.harness = execute.harness();
    }

    @Override
    public void event(RunEventRecord record) {
        lane.transcript().emit(record, (sent, refused) -> {
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
        lane.registry().register(runId, harness, new RunHandle(runId, unitId), notes);
        // The lease is recorded BEFORE the started event is emitted, deliberately: the cheap
        // local write goes first, so a broker that stalls cannot leave the lease unable to name
        // the unit an operator would then be looking for.
        lane.leases().recordUnit(runId, unitId);
        // RunStarted is emitted HERE rather than before the launch, because only here does a
        // unit id exist.
        results.emit(new RunResult.RunStarted(runId, unitId));
        stopIfCancelledWhileCreating(unitId, notes);
    }

    /**
     * The second read of {@link RunDispatcher#CANCEL_SLOT}, and the one that closes the window rather
     * than moving it.
     *
     * <p>{@code create} blocks for as long as an image pull and an init clone take. A cancel
     * arriving in there found an empty registry and wrote the claim, and the lane's pre-create read
     * had already happened. Reading again HERE — after registration, so the two orderings interleave
     * safely — is what leaves no window. Ordered after the lease and the started event on purpose: a
     * unit stopped before its lease names it is a sandbox the watchdog cannot attribute.
     *
     * <p>Best-effort and swallowed: the launcher already discards anything thrown from this
     * callback, and a database fault at this instant must not cost a RUNNING unit its outcome.
     * Failing to stop it degrades to the run finishing and reporting honestly, which is why this is
     * ERROR-logged rather than rethrown.
     */
    private void stopIfCancelledWhileCreating(String unitId, RunNotes notes) {
        try {
            if (!lane.claims().taken(runId, RunDispatcher.CANCEL_SLOT)) {
                return;
            }
            LOG.warnf("run %s: cancelled while its unit was being created; stopping it now", runId);
            // Through the registry, so the terminal result is relabelled CANCELLED instead of
            // reporting the agent failure a killed process produces.
            lane.registry().cancel(runId);
            notes.note(RunDispatcher.CANCELLED_NOTE,
                    "stopped by an operator: cancelled while the sandbox was being created", false);
            lane.runtime().cancel(new RunHandle(runId, unitId));
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
     *
     * @return whether the unit is gone
     */
    boolean settle() {
        if (!unitExists || unitGone) {
            lane.leases().release(runId);
            return true;
        }
        LOG.infof("run %s: its unit was not destroyed, so the lease is kept for the watchdog", runId);
        lane.leases().preserve(runId);
        return false;
    }
}
