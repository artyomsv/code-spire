package dev.codespire.runworker;

import dev.codespire.contract.event.RunEventRecord;

/**
 * What the launcher tells its caller while a run happens.
 *
 * <p>Three positional lambdas is where positions stop reading, and the third one arrived carrying a
 * fact the caller had previously been GUESSING — which is the more important reason this exists. The
 * dispatcher used to re-derive "was the sandbox destroyed" from the run's wire result, and a review
 * found four paths where the guess was wrong in the leaking direction: an init-container failure that
 * the Docker arm deliberately leaves behind, a salvage that throws after the publisher reported a
 * push failure, a {@code destroy} that itself throws, and anything escaping the observation loop. On
 * each, a sandbox holding a live model credential survived with nothing recording that it existed.
 *
 * <p>The launcher knows. So the launcher says, and the caller acts on knowledge rather than on
 * inference from a wire type that cannot carry a worker-internal fact.
 *
 * <p><b>The default is that the unit survives.</b> {@link #unitReleased()} is called only where a
 * teardown has actually succeeded, so every path nobody thought about leaks a lease row rather than a
 * container. A stale row costs one reconcile against the daemon; a missing row costs a credential
 * nobody can find.
 */
public interface RunObserver {

    /** One line of the run's live transcript. */
    void event(RunEventRecord record);

    /**
     * The sandbox exists, and this is its own id.
     *
     * <p>Called once, the instant {@code create} returns and before the run is observed, so a run
     * that then hangs is still findable. Not called at all when no unit was created.
     *
     * <p>{@code notes} is handed over at the same moment because it is the same fact: the run's
     * transcript now has a numbering authority, and anything wanting to add a line to that run must
     * go through it rather than counting for itself. See {@link RunNotes}.
     */
    void unitCreated(String unitId, RunNotes notes);

    /** The sandbox is gone: a teardown ran and returned. Never called when one was skipped or threw. */
    void unitReleased();

    /** Ignores everything. For a caller that only wants the terminal result. */
    RunObserver IGNORING = new RunObserver() {
        @Override
        public void event(RunEventRecord record) {
            // nothing
        }

        @Override
        public void unitCreated(String unitId, RunNotes notes) {
            // nothing
        }

        @Override
        public void unitReleased() {
            // nothing
        }
    };
}
