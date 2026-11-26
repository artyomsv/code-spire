package dev.codespire.worker.adapters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * What an LLM failure means: is the provider unwell, or did it answer?
 *
 * <p>The distinction is the whole basis of {@link ProviderCircuits}. A rate limit, a 5xx or a
 * timeout says the provider cannot serve us right now — pausing calls helps both sides. A rejected
 * key or a malformed request is an <b>answer</b>: the provider is up and told us something true, and
 * counting it would let one misconfigured credential pause every review on that host.
 *
 * <p>Lives here rather than in {@code spire-llm} because {@link ProviderCircuits} is worker-owned
 * and {@code spire-llm} is Apache-2.0: under ADR-021 no Apache-2.0 module may depend on a service
 * module, so the breaker cannot be pushed down into the provider it guards. The decorator is in the
 * worker for the same reason {@code RetryingDiffSource} is.
 */
public final class LlmFailures {

    /**
     * The worker never compiles against LangChain4j — it stays an implementation detail of
     * {@code spire-llm} — so the transient hierarchy (RateLimitException, InternalServerException
     * and TimeoutException all extend RetriableException) is recognized by name. Anything outside
     * it, notably AuthenticationException and InvalidRequestException, is an answer, not illness.
     */
    private static final String LANGCHAIN4J_RETRIABLE = "dev.langchain4j.exception.RetriableException";

    /** LangChain4j's timeout, recognised by name for the same reason. */
    private static final String LANGCHAIN4J_TIMEOUT = "dev.langchain4j.exception.TimeoutException";

    private LlmFailures() {
    }

    /**
     * True when the failure says the provider itself is struggling. Walks the cause chain because
     * the client wraps transport failures, and {@code join()} wraps everything again.
     */
    public static boolean isProviderUnwell(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof UncheckedIOException || t instanceof IOException
                    || t instanceof TimeoutException
                    || isLangChain4jRetriable(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the call ran out of time rather than being refused: a JDK or socket timeout, or LangChain4j's own
     * {@code TimeoutException}, anywhere in the cause chain. A timed-out large prompt is not re-sent, since each
     * attempt sends the whole prompt again and may be billed.
     */
    public static boolean isTimeout(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException
                    || t instanceof HttpTimeoutException || isNamed(t, LANGCHAIN4J_TIMEOUT)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLangChain4jRetriable(Throwable t) {
        return isNamed(t, LANGCHAIN4J_RETRIABLE);
    }

    private static boolean isNamed(Throwable t, String className) {
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            if (className.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }
}
