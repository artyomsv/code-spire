package dev.codespire.runtime;

import java.util.Objects;

/**
 * How a run's sandbox ended, as far as the runtime could observe it.
 *
 * <p>Three outcomes, not two. An earlier version had only "salvaged or not", which collapsed an
 * agent that outlived its wall clock with a daemon that hung up mid-wait. They are different
 * people's problems and deserve opposite retry answers: the same prompt against the same commit will
 * overrun again, while a daemon fault may not recur — and an operator told "salvage failed" for a
 * timeout goes looking for broken infrastructure.
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

    /** The runtime could not observe the run's outcome. */
    public static Finalization salvageFailed(String detail) {
        return new Finalization(NOT_OBSERVED, Outcome.FAULTED, detail);
    }
}
