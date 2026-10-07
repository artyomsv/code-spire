package dev.codespire.runtime;

import java.util.List;
import java.util.Objects;

/**
 * What a verify unit observed (M4): whether the checkpoint could be prepared, and each check that ran. An
 * observation, not a verdict; the run worker classifies it into passed, failed or unverified.
 *
 * @param preparedHead the head the prepare container reported, or null when it did not prepare
 * @param timedOut whether the time limit stopped prepare or a check
 */
public record VerifyRun(String preparedHead, Prepare prepare, List<Check> checks, boolean timedOut) {
    public enum Prepare { PREPARED, CHECKPOINT_MISSING, FAILED }

    /**
     * @param exitCode null when the container never started or was killed at the time limit
     * @param tail the last lines of its output, already bounded by the runtime
     */
    public record Check(Integer exitCode, long wallMillis, List<String> tail) {
        public Check {
            tail = List.copyOf(Objects.requireNonNull(tail, "tail"));
            if (wallMillis < 0) throw new IllegalArgumentException("a check cannot take negative time");
        }
    }

    public VerifyRun {
        Objects.requireNonNull(prepare, "prepare");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        if (prepare != Prepare.PREPARED && !checks.isEmpty()) throw new IllegalArgumentException("no check runs on an unprepared checkpoint");
    }
}
