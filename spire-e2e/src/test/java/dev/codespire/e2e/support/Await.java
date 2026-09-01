package dev.codespire.e2e.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The async contract.
 *
 * <p>Nothing in this pipeline is synchronous: reviews complete through Kafka and GitLab delivers
 * webhooks through Sidekiq, so every assertion is a race unless it waits.
 *
 * <p>Deadlines are generous and uniform rather than tuned per step. This is a nightly job, so one
 * flaky step costs a day of signal, and a step that genuinely needs its own longer deadline is
 * evidence of a problem rather than an invitation to tune.
 */
public final class Await {

    /** Uniform per-step deadline. Two model calls plus a webhook round trip fit inside it. */
    public static final Duration DEADLINE = Duration.ofMinutes(4);

    /** How long "nothing else happened" has to hold before it counts. */
    public static final Duration QUIET = Duration.ofSeconds(45);

    private static final Duration INTERVAL = Duration.ofSeconds(3);

    private Await() {
    }

    public static <T> T until(String step, Duration deadline, Supplier<Optional<T>> probe) {
        Instant giveUp = Instant.now().plus(deadline);
        RuntimeException lastError = null;
        int attempts = 0;
        while (Instant.now().isBefore(giveUp)) {
            attempts++;
            try {
                Optional<T> result = probe.get();
                if (result.isPresent()) {
                    return result.get();
                }
                lastError = null;
            } catch (RuntimeException e) {
                // A probe may legitimately fail while the system converges — a row that does not
                // exist yet, a thread not yet created. Keep the last one for the failure message
                // rather than aborting on it: without this, every ordinary race reads as a defect.
                lastError = e;
            }
            sleep(INTERVAL);
        }
        throw new AssertionError(step + " — not satisfied within " + deadline
                + " (" + attempts + " probes)"
                + (lastError == null ? "" : "; last probe error: " + describe(lastError)));
    }

    /**
     * The whole cause chain, not just {@code toString()}.
     *
     * <p>Every driver in this harness wraps an {@code IOException} as
     * {@code IllegalStateException("GitLab request failed: " + uri, e)}, so rendering only the
     * outermost exception discards the one word that matters — "Connection refused", "Read timed
     * out", "Broken pipe". In a nightly job nobody is watching, that word is the difference between a
     * failure someone can act on and one that costs a re-run to understand.
     */
    private static String describe(Throwable error) {
        StringBuilder chain = new StringBuilder(error.toString());
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            chain.append(" <- ").append(cause);
        }
        return chain.toString();
    }

    public static <T> T until(String step, Supplier<Optional<T>> probe) {
        return until(step, DEADLINE, probe);
    }

    /**
     * Asserts a count does NOT rise over a quiet period.
     *
     * <p>Callers must anchor this to a positive signal first — wait for the thing that SHOULD happen,
     * then call this. An absence assertion with nothing anchoring it passes against a system that has
     * not started yet, which is not an assertion at all.
     */
    public static void absent(String step, Duration quietPeriod, Supplier<Long> count) {
        long before = count.get();
        Instant until = Instant.now().plus(quietPeriod);
        while (Instant.now().isBefore(until)) {
            sleep(INTERVAL);
            long now = count.get();
            if (now != before) {
                throw new AssertionError(step + " — expected no change over " + quietPeriod
                        + ", but the count moved from " + before + " to " + now);
            }
        }
    }

    public static void absent(String step, Supplier<Long> count) {
        absent(step, QUIET, count);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting", e);
        }
    }
}
