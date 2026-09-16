package dev.codespire.runtime;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Runs one trusted container so a person can sign a harness in (M3.5 part F).
 *
 * <p>A separate port from {@link RunRuntime} rather than four more methods on it. {@code RunRuntime} is
 * the path that spends money and holds the push credential; every arm that implements it must get all of
 * salvage, orphan discovery, steering and cancellation right. This needs none of that, and widening that
 * interface would oblige every future arm to implement a screen's mechanism before it could run a single
 * agent.
 *
 * <p><b>Only one arm implements it today, and that is stated rather than hidden:</b> Docker. A
 * Kubernetes deployment needs its own, and until it has one, subscription sign-in is unavailable there
 * and says so. That is the same boundary RUN-TOPOLOGY already draws for the run unit.
 */
public interface SignInRuntime {

    RuntimeType type();

    /**
     * Starts the unit and streams its output, line by line, as it appears.
     *
     * <p>The stream is how the operator learns what to do: the link and the code are printed while the
     * unit is still waiting, and a caller that only read the output at the end would show them nothing
     * until it was too late to use.
     *
     * <p>The credential never travels this way. It is written to the spec's result path and read with
     * {@link #result}, because output reaches the daemon's logs and stays there.
     */
    Handle start(SignInUnitSpec spec, Consumer<String> lines);

    /**
     * Waits for the unit to exit.
     *
     * @return the exit code, or empty if it was still running when the wait elapsed
     */
    Optional<Integer> awaitExit(Handle handle, Duration within);

    /**
     * Reads the file the unit wrote, copied straight out of the stopped container.
     *
     * <p>The path is passed rather than remembered: the caller holds the spec that declared it, and an
     * arm that kept its own copy would hold state a restart loses silently.
     *
     * @return the bytes, or empty if the unit wrote nothing there
     */
    Optional<byte[]> result(Handle handle, String resultPath);

    /** Stops a unit nobody is going to answer. Safe to call on one that has already exited. */
    void cancel(Handle handle);

    /**
     * Removes the container and anything it wrote.
     *
     * <p>Always called, including after a failure, because what it destroys is a credential the operator
     * has just created. Leaving it in a stopped container means leaving it readable by anything that can
     * reach the daemon, for as long as nobody notices.
     */
    void destroy(Handle handle);

    /** What an arm hands back so the caller can name the unit again. Opaque by design. */
    record Handle(String unitId, String reference) {
        public Handle {
            if (unitId == null || unitId.isBlank()) throw new IllegalArgumentException("a handle needs its unitId");
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("a handle needs the arm's own reference");
        }
    }
}
