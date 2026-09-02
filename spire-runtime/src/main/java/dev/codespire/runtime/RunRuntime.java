package dev.codespire.runtime;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Where a run unit runs and how its life is controlled.
 *
 * <p>{@link #salvage} and {@link #destroy} are separate on purpose. Merging them is how completed
 * work gets thrown away: teardown is the step that cannot be undone, and it must never be the same
 * call as the one that decides whether there was anything worth keeping.
 *
 * <p>The salvage step is named {@code salvage} rather than {@code finalize}. An interface method
 * called {@code finalize} taking an argument is a legal overload of the {@link Object} method that
 * is deprecated for removal, which compiles and then confuses every reader and linter that meets
 * it — and the operation's own javadoc already called it salvage.
 */
public interface RunRuntime {

    RuntimeType type();

    RuntimeCapabilities capabilities();

    RunHandle create(RunUnitSpec spec);

    /** Streams one container's stdout line by line until it exits. */
    void attach(RunHandle handle, LogChannel channel, Consumer<String> lines);

    void cancel(RunHandle handle);

    /** Takes everything worth keeping, BEFORE {@link #destroy}. Never destroys anything itself. */
    Finalization salvage(RunHandle handle);

    void destroy(RunHandle handle);

    /** Units this runtime holds that no live lease claims. See ARCHITECTURE §7. */
    List<RunHandle> discoverOrphans();

    /**
     * The longest {@link #salvage} may hold its caller after the agent is gone: the window the
     * publisher is given to finish its final bundle on its own before it is stopped. The worker's
     * ack budget adds it to the run's wall clock, and reads it from the arm rather than from a
     * constant of its own, so an arm that raises its window cannot silently outlive the channel —
     * the Docker arm's went 30s to 300s once and a guard that did not read it kept passing.
     */
    Duration drainWindow();
}
