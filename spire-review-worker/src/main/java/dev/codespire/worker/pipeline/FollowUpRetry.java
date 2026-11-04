package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ReplyRetry;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.worker.adapters.LlmFailures;
import dev.codespire.worker.adapters.ProviderCircuits;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * The conversation path's retry: a transient fault is retried with the conversation settings' backoff, anything else
 * or the last attempt is re-thrown, so the channel's dead-letter strategy routes the command to {@code cs.dlq} (ADR-013:
 * never silently lost). Shared by the API reply ({@code AnswerFollowUp}) and the harness reply's prepare and post.
 */
final class FollowUpRetry {

    private static final Logger LOG = Logger.getLogger(FollowUpRetry.class);

    /** The longest single backoff, whatever the settings say. */
    private static final long MAX_BACKOFF_MS = 60_000L;

    /** Which command's attempts these are, for the log: its review, what it does, and how it retries. */
    record RetryCall(String reviewId, String what, ReplyRetry retry) {
    }

    private FollowUpRetry() {
    }

    static void run(RetryCall call, Runnable attempt) {
        int maxAttempts = Math.max(1, call.retry().maxAttempts());
        RuntimeException lastFailure = null;
        for (int number = 1; number <= maxAttempts; number++) {
            try {
                attempt.run();
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                Throwable cause = unwrap(e);
                if (number < maxAttempts && isTransient(cause)) {
                    LOG.warnf("%s attempt %d/%d for %s failed transiently (%s) — retrying",
                            call.what(), number, maxAttempts, call.reviewId(), cause.getMessage());
                    backoff(number, call.retry());
                    continue;
                }
                break; // non-retryable, or attempts exhausted
            }
        }
        LOG.warnf(unwrap(lastFailure), "%s for %s failed after %d attempt(s) — routing to cs.dlq",
                call.what(), call.reviewId(), maxAttempts);
        throw lastFailure;
    }

    /** {@code min(cap, base * factor^(attempt-1))}, the settings the orchestrator packed from {@code app_setting}. */
    private static void backoff(int attempt, ReplyRetry retry) {
        long sleepMs = (long) Math.min(MAX_BACKOFF_MS,
                retry.backoffBaseMs() * Math.pow(retry.backoffFactor(), attempt - 1));
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Throwable unwrap(RuntimeException e) {
        return e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    /** Transient SCM (5xx / 429) or IO / timeout → worth a retry; anything else → dead-letter immediately. */
    static boolean isTransient(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ScmApiException api && (api.status() >= 500 || api.isRateLimited())) {
                return true;
            }
            if (t instanceof UncheckedIOException || t instanceof IOException || t instanceof TimeoutException
                    || t instanceof ProviderCircuits.CircuitOpenException) {
                return true;
            }
        }
        // An LLM provider that is rate-limiting or 5xx-ing is transient here exactly as it is for a
        // review. Without this a follow-up went straight to cs.dlq on the provider's first bad
        // minute — and once the LLM call is behind a circuit breaker, so would every reply for the
        // whole cooldown.
        return LlmFailures.isProviderUnwell(cause);
    }
}
