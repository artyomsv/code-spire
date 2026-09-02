package dev.codespire.runtime;

import java.util.Objects;

/**
 * How a run's sandbox ended, as far as the runtime could observe it.
 *
 * <p>Three outcomes, not two. An earlier version had only "salvaged or not", which collapsed an
 * agent that outlived its wall clock with a daemon that hung up mid-wait. They send different people
 * to different places: an operator told "salvage failed" for a timeout goes looking for broken
 * infrastructure, and one told "the agent timed out" for a broken daemon goes looking for a slow
 * prompt.
 *
 * <p><b>Neither is retryable, and that is deliberate rather than an oversight.</b> An earlier draft
 * of this note claimed they "deserve opposite retry answers"; the taxonomy gives both the same
 * answer, and it is right to. A preserved unit may still hold a live agent, so retrying would put a
 * second agent on the same branch. The debt entry that asked for a retryable fault is answered here:
 * considered, and declined for that reason.
 */
public record Finalization(int exitCode, Outcome outcome, String detail) {

    /** The exit code of a run whose outcome was never observed. Never a real process status. */
    public static final int NOT_OBSERVED = -1;

    public enum Outcome {
        /** The agent exited and the runtime read its status. */
        SALVAGED,
        /** The agent outlived the run's wall clock and was stopped. Its doing, not the runtime's. */
        OVERRAN,
        /** The runtime could not observe the agent at all. */
        FAULTED
    }

    public Finalization {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("a finalization must say what happened");
        }
        if (outcome != Outcome.SALVAGED && exitCode != NOT_OBSERVED) {
            throw new IllegalArgumentException(
                    "nothing observed an exit code, so it cannot report " + exitCode);
        }
        // Guarded on BOTH sides, and the second half is not symmetry for its own sake: Docker
        // reports State.ExitCode as -1 for a container that never started, so without this a unit
        // that never ran would arrive claiming it was salvaged with a real-looking status.
        if (outcome == Outcome.SALVAGED && exitCode == NOT_OBSERVED) {
            throw new IllegalArgumentException(
                    "a salvaged run observed an exit code, so it cannot report NOT_OBSERVED");
        }
    }

    /** Whether an exit status was observed. False for both an overrun and a fault. */
    public boolean salvaged() {
        return outcome == Outcome.SALVAGED;
    }

    /** Whether the agent outlived its wall clock, as opposed to the runtime failing to look. */
    public boolean overran() {
        return outcome == Outcome.OVERRAN;
    }

    public static Finalization salvaged(int exitCode, String detail) {
        return new Finalization(exitCode, Outcome.SALVAGED, detail);
    }

    /** The agent outlived the run's wall clock. Not a fault of the runtime, and not retryable. */
    public static Finalization overran(String detail) {
        return new Finalization(NOT_OBSERVED, Outcome.OVERRAN, detail);
    }

    /**
     * The runtime could not observe the run's outcome.
     *
     * <p>Named for the value it builds. It was {@code salvageFailed}, which no longer matched
     * {@code FAULTED} once the outcomes split — and reaching for a name like that on a timeout is
     * precisely the bug this type was reshaped to prevent, so the name mattering is the point.
     */
    public static Finalization faulted(String detail) {
        return new Finalization(NOT_OBSERVED, Outcome.FAULTED, detail);
    }
}
