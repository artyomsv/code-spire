package dev.codespire.worker.pipeline;

import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.worker.adapters.LlmFailures;
import dev.codespire.worker.adapters.ProviderCircuits;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/** How a review worker failure is classified, shared by the API path and the harness path (harness reviews, step 6). */
final class WorkerFailures {

    private WorkerFailures() {
    }

    static Throwable unwrap(RuntimeException e) {
        return e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    static boolean isCommitGone(Throwable cause) {
        return cause instanceof ScmApiException api && api.isNotFound();
    }

    /**
     * Transient (SCM 5xx/429, LLM retriable, I/O, timeout, a database fault) -> retryable; client errors -> terminal.
     * A database fault arrives as the idempotency store's {@code IllegalStateException} around an
     * {@link SQLException}: a finish must never fail a paid answer over one.
     */
    static boolean isRetryable(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ScmApiException api && (api.status() >= 500 || api.isRateLimited())) {
                return true;
            }
            if (t instanceof UncheckedIOException || t instanceof IOException || t instanceof TimeoutException
                    || t instanceof SQLException || t instanceof ProviderCircuits.CircuitOpenException) {
                return true;
            }
        }
        return LlmFailures.isProviderUnwell(cause);
    }

    /**
     * The provider refused the credential. Walks the cause chain for the same reason
     * {@link #isRetryable} does: an adapter failure can arrive wrapped by the LLM client.
     */
    static boolean isCredentialRejected(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ScmApiException api && api.isUnauthorized()) {
                return true;
            }
        }
        return false;
    }
}
