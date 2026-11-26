package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;

import java.time.Duration;

/** The operator-facing words for a review's bounded retry (C8): its delay and why a run ended. */
final class RetryNotes {

    private static final long SECONDS_PER_MINUTE = 60;

    private RetryNotes() {
    }

    /** "8s" / "2m 30s" — the note is read by an operator wondering why nothing is happening yet. */
    static String humanDelay(Duration delay) {
        long seconds = Math.max(0, delay.toSeconds());
        if (seconds < SECONDS_PER_MINUTE) {
            return seconds + "s";
        }
        long minutes = seconds / SECONDS_PER_MINUTE;
        long remainder = seconds % SECONDS_PER_MINUTE;
        return remainder == 0 ? minutes + "m" : minutes + "m " + remainder + "s";
    }

    /** Why a failure ended the review rather than retrying it. */
    static String terminalNote(ReviewFailed e, int attempt, int maxAttempts) {
        if (e.retryable() && attempt >= maxAttempts) {
            return "Failed at " + e.phase() + " after " + attempt + " attempts (retry budget exhausted).";
        }
        if (e.retryable()) {
            return "Failed at " + e.phase() + " — provider unavailable, cannot retry.";
        }
        return "Failed terminally at " + e.phase() + ".";
    }
}
