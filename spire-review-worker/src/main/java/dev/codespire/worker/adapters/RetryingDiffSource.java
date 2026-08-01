package dev.codespire.worker.adapters;

import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmApiException;
import org.jboss.logging.Logger;

import java.util.Random;
import java.util.function.Supplier;

/**
 * Retries a transient SCM read instead of failing the whole review over one blip.
 *
 * <p>Until now the only retry was ADR-016's: a transient failure on any of the several sequential
 * calls a review makes fails the attempt, and the orchestrator re-runs the entire pipeline — a fresh
 * diff fetch, a fresh context assembly — burning one of three attempts on a network hiccup that a
 * second try 200ms later would have cleared. This absorbs that locally, and ADR-016 stays the outer
 * guard for everything it cannot.
 *
 * <p><b>Reads only, and that is the whole reason this decorates {@code DiffSource}.</b> Every method
 * here is a GET, so a retry is safe by construction and needs no idempotency key. Posting is not
 * wrapped: comment writes already carry their own claim in {@code comment_idempotency} plus a
 * Retry-After-aware backoff with a budget tuned against Kafka's {@code max.poll.interval.ms}, and a
 * second retry layer underneath that would silently double the sleep it is bounded by.
 *
 * <p>The delays are deliberately tiny for the same reason: worst case here is roughly
 * {@code 100 + 200 = 300ms} of sleep plus jitter, on a consumer thread whose poll interval the
 * posting path is already carefully budgeting against.
 */
public class RetryingDiffSource implements DiffSource {

    private static final Logger LOG = Logger.getLogger(RetryingDiffSource.class);

    /** One retry rarely helps a provider that is genuinely unwell; three attempts is the ceiling. */
    static final int MAX_ATTEMPTS = 3;
    static final long BASE_DELAY_MS = 100;

    private final DiffSource delegate;
    private final Sleeper sleeper;
    private final Random jitter;

    /** Separated so a test can assert the backoff without spending it. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public RetryingDiffSource(DiffSource delegate) {
        this(delegate, Thread::sleep, new Random());
    }

    RetryingDiffSource(DiffSource delegate, Sleeper sleeper, Random jitter) {
        this.delegate = delegate;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    @Override
    public ScmType type() {
        return delegate.type(); // local, never fails
    }

    @Override
    public PullRequest fetchPullRequest(RepoRef repo, long prId) {
        return withRetry("fetchPullRequest", () -> delegate.fetchPullRequest(repo, prId));
    }

    @Override
    public Diff fetchDiff(RepoRef repo, long prId, String commit) {
        return withRetry("fetchDiff", () -> delegate.fetchDiff(repo, prId, commit));
    }

    @Override
    public String fetchCompareDiff(RepoRef repo, String base, String head) {
        return withRetry("fetchCompareDiff", () -> delegate.fetchCompareDiff(repo, base, head));
    }

    @Override
    public void assertRepoAccessible(RepoRef repo) {
        withRetry("assertRepoAccessible", () -> {
            delegate.assertRepoAccessible(repo);
            return null;
        });
    }

    /**
     * Retries only what a second attempt could plausibly fix. The classification is the pipeline's
     * existing one — 5xx and 429 are transient, everything else is the provider's settled answer —
     * so a 404 on a force-pushed commit still abandons immediately rather than sleeping first.
     *
     * <p>A rate-limited provider is NOT retried here even though it is transient: it tells us how
     * long to wait via Retry-After, and honouring that is the posting path's job, with a budget. A
     * blind 100ms retry into a 429 spends an attempt to be refused again.
     */
    private <T> T withRetry(String operation, Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (!isWorthRetrying(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                last = e;
                backOff(operation, attempt, e);
            }
        }
        throw last; // unreachable: the loop either returns or throws
    }

    private static boolean isWorthRetrying(RuntimeException e) {
        return e instanceof ScmApiException api && api.status() >= 500 && !api.isRateLimited();
    }

    /** Exponential with full jitter, so several reviews failing at once do not retry in lockstep. */
    private void backOff(String operation, int attempt, RuntimeException cause) {
        long ceiling = BASE_DELAY_MS << (attempt - 1);
        long delay = 1 + jitter.nextLong(ceiling);
        LOG.warnf("Retrying %s after a transient SCM failure (attempt %d/%d, waiting %dms): %s",
                operation, attempt, MAX_ATTEMPTS, delay, cause.getMessage());
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cause; // shutting down — surface the original failure, not the interruption
        }
    }
}
