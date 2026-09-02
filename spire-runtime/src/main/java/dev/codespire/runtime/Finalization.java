package dev.codespire.runtime;

import java.util.Objects;

/**
 * The result of salvaging a run — everything worth keeping, taken BEFORE teardown.
 *
 * <p>{@code destroy} runs only when {@link #salvaged()} is true. A failed salvage preserves the
 * unit, because "the agent did the work and the container died with it" was the second most common
 * failure in the prior art this design learned from.
 *
 * <p>The two halves cannot disagree: a failed salvage carries no exit code to trust, so the
 * canonical constructor refuses one. Without that, {@code new Finalization(0, false, ...)} is
 * constructible and reads as a clean exit that was never observed.
 */
public record Finalization(int exitCode, boolean salvaged, String detail) {

    /** The exit code of a run whose outcome was never observed. Never a real process status. */
    public static final int NOT_OBSERVED = -1;

    public Finalization {
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("a finalization must say what happened");
        }
        if (!salvaged && exitCode != NOT_OBSERVED) {
            throw new IllegalArgumentException(
                    "a failed salvage observed no exit code, so it cannot report " + exitCode);
        }
        if (salvaged && exitCode == NOT_OBSERVED) {
            // Guarded on BOTH sides, or the sentinel is ambiguous: a salvaged run whose real status
            // is -1 would be byte-identical to one never observed, and any consumer testing
            // exitCode == NOT_OBSERVED reads a real outcome as "never happened". Not hypothetical —
            // Docker reports State.ExitCode as -1 for a container that never started, and that
            // path arrives here as a salvaged unit.
            throw new IllegalArgumentException(
                    "a salvaged run observed an exit code, so it cannot report NOT_OBSERVED");
        }
    }

    public static Finalization salvaged(int exitCode, String detail) {
        return new Finalization(exitCode, true, detail);
    }

    public static Finalization salvageFailed(String detail) {
        return new Finalization(NOT_OBSERVED, false, detail);
    }
}
