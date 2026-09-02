package dev.codespire.runworker;

import dev.codespire.runtime.RunHandle;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runs this replica is executing right now, so a cancel can reach one.
 *
 * <p>The command channel is deliberately ordered and blocking: the launcher holds it for the run's
 * whole duration. So a cancel arriving on that channel would queue behind the very run it cancels
 * and take effect when the run had already finished. Control rides its own topic into a listener
 * beside the executor, and this is the map that lets the two meet.
 *
 * <p><b>In memory, and deliberately not durable.</b> A handle is only useful to the process holding
 * the containers; a restarted replica cannot cancel a run it is no longer executing, and its stale
 * lease makes the sandbox the orphan watchdog's problem instead. Persisting the map would invite a
 * cancel to be delivered to a replica that could not act on it while looking as though it had.
 *
 * <p>The cancellation is recorded as well as acted on, because stopping the containers and saying
 * WHY the run ended are two different facts. Without the flag the launcher classifies the killed
 * agent as an ordinary non-zero exit, and an operator who cancelled a run is told it failed.
 */
@ApplicationScoped
public class RunRegistry {

    private final Map<String, LiveRun> live = new ConcurrentHashMap<>();

    /** Record a run's sandbox and which harness is driving it, from the moment it exists. */
    public void register(String runId, String harness, RunHandle handle) {
        live.put(runId, new LiveRun(handle, harness, false));
    }

    /** This run's sandbox, without marking anything. Empty when this replica is not running it. */
    public Optional<RunHandle> find(String runId) {
        return Optional.ofNullable(live.get(runId)).map(LiveRun::handle);
    }

    /**
     * Which harness is driving this run, or null.
     *
     * <p>Recorded rather than re-read from the command, because a steer arrives on its own topic
     * carrying only a run id -- and the capability that decides whether it may be delivered is the
     * HARNESS's fact, not the runtime's.
     */
    public String harnessOf(String runId) {
        LiveRun run = live.get(runId);
        return run == null ? null : run.harness();
    }

    /**
     * Mark a run cancelled and hand back its sandbox.
     *
     * <p>Empty means this replica is not executing that run: a cancel that arrived after the run
     * finished, a duplicate, or one for a run another replica owns. All three are harmless and none
     * is an error — a control channel that failed on a late cancel would stop delivering the ones
     * that still matter.
     */
    public Optional<RunHandle> cancel(String runId) {
        LiveRun cancelled = live.computeIfPresent(runId, (id, run) -> run.asCancelled());
        return Optional.ofNullable(cancelled).map(LiveRun::handle);
    }

    /**
     * Whether this run was cancelled while it was executing.
     *
     * <p>Read at the terminal result, before {@link #forget}, so the killed agent's non-zero exit is
     * reported as a cancellation rather than as a failure. An operator who stopped a run must not be
     * told it broke.
     */
    public boolean wasCancelled(String runId) {
        LiveRun run = live.get(runId);
        return run != null && run.cancelled();
    }

    /** Drop a finished run. Called on every terminal path, so the map holds only live work. */
    public void forget(String runId) {
        live.remove(runId);
    }

    /**
     * Whether this process is executing this run right now.
     *
     * <p>The orphan watchdog's exemption, and the only one that cannot be wrong. The heartbeat is
     * best-effort — a lease write that fails is logged and skipped — so a database outage longer
     * than the staleness window ages every lease this replica holds while its runs carry on
     * working perfectly well. This fact is local and exact.
     */
    public boolean isExecuting(String runId) {
        return live.containsKey(runId);
    }

    /** How many runs this replica believes it is executing. For the health surface and for tests. */
    public int size() {
        return live.size();
    }

    private record LiveRun(RunHandle handle, String harness, boolean cancelled) {

        LiveRun asCancelled() {
            return new LiveRun(handle, harness, true);
        }
    }
}
