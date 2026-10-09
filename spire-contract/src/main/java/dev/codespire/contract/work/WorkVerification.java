package dev.codespire.contract.work;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What a verify attempt found for one checkpoint (M4 verify, spec §3.2). PASSED is the only outcome that
 * may authorize delivery; UNVERIFIED says the checks could not run and must never render as passed.
 */
public record WorkVerification(UUID attemptId, String head, Outcome outcome, String reason, List<CheckResult> checks) {
    public enum Outcome { PASSED, FAILED, UNVERIFIED }

    /** Bounded so an agent-controlled log cannot grow an event, a row or a screen without limit. */
    public static final int MAX_TAIL_CHARS = 64 * 1024;
    public static final Set<String> UNVERIFIED_REASONS = Set.of("no_checks_declared", "tool_missing", "timed_out",
            "checkpoint_missing", "verify_could_not_run");

    /** @param exitCode null when the container never started */
    public record CheckResult(String command, Integer exitCode, long wallMillis, String outputTail) {
        public CheckResult {
            if (command == null || command.isBlank()) throw new IllegalArgumentException("A check names its command");
            if (wallMillis < 0) throw new IllegalArgumentException("A check cannot take negative time");
            outputTail = outputTail == null ? "" : outputTail;
            if (outputTail.length() > MAX_TAIL_CHARS) throw new IllegalArgumentException("A check tail is over its cap");
        }
    }

    public WorkVerification {
        Objects.requireNonNull(attemptId, "A verification names its attempt");
        Objects.requireNonNull(outcome, "A verification has an outcome");
        if (head == null || !head.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("A verification names its full head");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        switch (outcome) {
            case PASSED -> {
                if (reason != null) throw new IllegalArgumentException("A passed verification has no reason");
                // Zero runnable checks must never produce the same green as zero failing ones (AUTONOMY.md).
                if (checks.isEmpty() || checks.stream().anyMatch(check -> !Integer.valueOf(0).equals(check.exitCode())))
                    throw new IllegalArgumentException("Passed means every declared check ran and exited 0");
            }
            case FAILED -> {
                if (!"check_failed".equals(reason)) throw new IllegalArgumentException("A failed verification is check_failed");
            }
            case UNVERIFIED -> {
                if (!UNVERIFIED_REASONS.contains(reason)) throw new IllegalArgumentException("Unknown unverified reason " + reason);
            }
        }
    }

    public boolean passed() { return outcome == Outcome.PASSED; }
}
