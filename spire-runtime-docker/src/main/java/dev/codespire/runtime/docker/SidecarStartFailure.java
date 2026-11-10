package dev.codespire.runtime.docker;

import com.github.dockerjava.api.command.InspectContainerResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Why a unit's sidecar (the egress proxy or the model proxy) did not come up, as the run's failure detail says it: the
 * container's state, exit code and OOMKilled flag, then the last lines of its own output, bounded.
 *
 * <p>The live acceptance of harness reviews step 3 met a model proxy that was running and was not seen listening
 * within 30 s, and the detail said only that. With the state and the tail the next one explains itself: running and
 * silent, exited with its refusal, or killed for memory.
 *
 * <p>What the tail can hold: the model proxy prints {@code spire-model <METHOD> <path> -> <status>} per request and,
 * on a refused start, a line naming the variable, never its value ({@code ModelProxy.main}); the egress proxy prints
 * tinyproxy's own start-up and the hosts it was asked for, which its exit path already quoted. Bounded anyway.
 */
final class SidecarStartFailure {

    /** At most this many of the newest lines. */
    static final int TAIL_LINES = 20;

    /** And at most this many characters of them, joined. */
    static final int TAIL_CHARS = 2048;

    static final String SEPARATOR = " | ";

    /** Marks a line whose start was cut to fit; the end of a line is what names a failure. */
    private static final String CUT = "...";

    private static final String UNKNOWN = "unknown";

    private SidecarStartFailure() {
    }

    /** What docker inspect says of the container; any part may be absent. */
    record State(String status, Long exitCode, Boolean oomKilled) {

        static State of(InspectContainerResponse.ContainerState state) {
            if (state == null) return new State(null, null, null);
            return new State(state.getStatus(), state.getExitCodeLong(), state.getOOMKilled());
        }
    }

    /** {@code headline (state S, exit E, OOMKilled O); last output: a | b}, with the tail bounded by {@link #tail}. */
    static String detail(String headline, State state, List<String> log) {
        List<String> tail = tail(log);
        return headline
                + " (state " + orUnknown(state.status())
                + ", exit " + orUnknown(state.exitCode())
                + ", OOMKilled " + orUnknown(state.oomKilled())
                + "); last output: " + (tail.isEmpty() ? "none" : String.join(SEPARATOR, tail));
    }

    /**
     * The newest lines of {@code log}: at most {@link #TAIL_LINES}, joined at most {@link #TAIL_CHARS} characters. A
     * newest line longer than that alone keeps its end.
     */
    static List<String> tail(List<String> log) {
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (int index = log.size() - 1; index >= 0 && kept.size() < TAIL_LINES; index--) {
            String line = log.get(index);
            int cost = line.length() + (kept.isEmpty() ? 0 : SEPARATOR.length());
            if (used + cost > TAIL_CHARS) {
                if (kept.isEmpty()) kept.add(CUT + line.substring(line.length() - (TAIL_CHARS - CUT.length())));
                break;
            }
            kept.add(line);
            used += cost;
        }
        Collections.reverse(kept);
        return kept;
    }

    private static String orUnknown(Object value) {
        return value == null || value.toString().isBlank() ? UNKNOWN : value.toString();
    }
}
