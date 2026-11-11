package dev.codespire.runworker;

import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * What the launcher keeps of an agent's event stream: a fold, not a list.
 *
 * <p>The version this replaces accumulated every parsed event in a {@code CopyOnWriteArrayList}.
 * The agent writes to that stream at {@code danger-full-access}, so its volume is the agent's to
 * choose, and nothing capped it; and that list copies its whole backing array on every add, so N
 * events cost O(N²) — on a worker shared by every concurrent run. One chatty run degraded every
 * other run on the replica.
 *
 * <p>What the adapters actually read from a summary is small and known: {@code classify} asks
 * whether any output was seen at all, and {@code usage} walks the {@code Usage} events in order to
 * take the latest cumulative report and to notice a total that shrank. So the fold keeps the usage
 * events, bounded, one flag, and the last {@code result} state change; every other event is counted and dropped.
 *
 * <p>The {@code result} state is how an arm whose stream ends with a verdict line says the model failed even when the
 * process exits 0 (Claude Code's {@code "is_error":true}, measured with {@code "subtype":"success"}). Dropped here, an
 * error result would classify on the exit code alone and succeed. Only the last is kept, since a later verdict
 * replaces an earlier one; the replaced one counts as dropped.
 *
 * <p><b>The bound holds the FIRST usage events and always the latest.</b> The usage reading depends
 * on order — a later total smaller than an earlier one marks the run unreconciled — so dropping the
 * middle keeps both ends of the comparison the adapter makes. A harness reports usage once per
 * turn; the cap is orders of magnitude above what a real run produces, and a stream that exceeds it
 * is not a harness talking.
 */
final class RunEventFold {

    static final int MAX_USAGE_EVENTS = 1_000;

    /** The state an arm's verdict line is reported under; neutral, so no arm is named here. */
    static final String RESULT_STATE = "result";

    private final List<RunEvent.Usage> usage = new ArrayList<>();
    private RunEvent.Usage latestBeyondCap;
    private RunEvent.StateChange result;
    private boolean sawAnyOutput;
    private long dropped;

    synchronized void accept(RunEvent event) {
        switch (event) {
            case RunEvent.Usage u -> {
                if (usage.size() < MAX_USAGE_EVENTS) {
                    usage.add(u);
                } else {
                    latestBeyondCap = u;
                    dropped++;
                }
            }
            case RunEvent.Output o -> sawAnyOutput = true;
            case RunEvent.StateChange s when false && RESULT_STATE.equals(s.state()) -> {
                if (result != null) {
                    dropped++;
                }
                result = s;
            }
            default -> dropped++;
        }
    }

    synchronized RunEventSummary summary() {
        List<RunEvent> events = new ArrayList<>(usage);
        if (latestBeyondCap != null) {
            events.add(latestBeyondCap);
        }
        if (result != null) {
            events.add(result);
        }
        return new RunEventSummary(events, sawAnyOutput);
    }

    /** Events not carried into the summary, for the log: never a reason to fail the run. */
    synchronized long dropped() {
        return dropped;
    }
}
