package dev.codespire.harness;

import java.util.Objects;
import java.util.Optional;

/**
 * How a run ended. A failure always names its cause, and a success never carries one — the compact
 * constructor refuses the contradiction rather than leaving the two components free to disagree.
 */
public record TerminalOutcome(boolean succeeded, Optional<FailureCause> cause, String detail) {

    public TerminalOutcome {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(detail, "detail");
        if (succeeded && cause.isPresent()) {
            throw new IllegalArgumentException("a successful outcome cannot name a failure cause: " + cause.get());
        }
        if (!succeeded && cause.isEmpty()) {
            throw new IllegalArgumentException("a failure must name its cause (FR-F9): " + detail);
        }
    }

    public static TerminalOutcome success(String detail) {
        return new TerminalOutcome(true, Optional.empty(), detail);
    }

    public static TerminalOutcome failure(FailureCause cause, String detail) {
        return new TerminalOutcome(false, Optional.of(cause), detail);
    }
}
