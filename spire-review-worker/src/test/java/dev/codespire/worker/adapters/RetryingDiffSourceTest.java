package dev.codespire.worker.adapters;

import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmApiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The call-level retry: a transient SCM read is retried in place instead of failing the review and
 * spending one of ADR-016's three whole-pipeline attempts on a network blip.
 */
class RetryingDiffSourceTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");

    private final List<Long> slept = new ArrayList<>();

    /** Time is driven by the test, so an open circuit's cooldown is reached without waiting for it. */
    private final AtomicLong now = new AtomicLong();
    private final ProviderCircuits circuits = new ProviderCircuits(now::get);

    @Test
    void retriesATransientFailureAndSucceeds() {
        FailingDiffSource delegate = new FailingDiffSource(1, 503);

        PullRequest pr = source(delegate).fetchPullRequest(REPO, 42);

        assertNotNull(pr);
        assertEquals(2, delegate.calls, "one failure, then the retry that worked");
        assertEquals(1, slept.size(), "one backoff between the two attempts");
    }

    @Test
    void givesUpAfterTheAttemptCeilingAndRethrowsTheProvidersError() {
        FailingDiffSource delegate = new FailingDiffSource(Integer.MAX_VALUE, 500);

        TestScmException thrown =
                assertThrows(TestScmException.class, () -> source(delegate).fetchPullRequest(REPO, 42));

        assertEquals(500, thrown.status(), "the caller still sees the provider's own failure");
        assertEquals(RetryingDiffSource.MAX_ATTEMPTS, delegate.calls);
        assertEquals(RetryingDiffSource.MAX_ATTEMPTS - 1, slept.size(), "no sleep after the last attempt");
    }

    /**
     * A force-pushed commit answers 404, and no number of retries will bring it back. Retrying it
     * would delay the quiet abandon the pipeline does on purpose.
     */
    @Test
    void doesNotRetryAFailureTheProviderHasSettled() {
        FailingDiffSource delegate = new FailingDiffSource(Integer.MAX_VALUE, 404);

        assertThrows(TestScmException.class, () -> source(delegate).fetchDiff(REPO, 42, "abc123"));

        assertEquals(1, delegate.calls, "a 404 is an answer, not a blip");
        assertTrue(slept.isEmpty());
    }

    /**
     * A 429 carries Retry-After, and honouring it belongs to the posting path that budgets its sleep
     * against the Kafka poll interval. Burning an attempt on a blind 100ms retry only gets refused again.
     */
    @Test
    void doesNotRetryARateLimitItCannotHonourProperly() {
        FailingDiffSource delegate = new FailingDiffSource(Integer.MAX_VALUE, 429);

        assertThrows(TestScmException.class, () -> source(delegate).fetchPullRequest(REPO, 42));

        assertEquals(1, delegate.calls);
        assertTrue(slept.isEmpty());
    }

    /** Backoff grows and stays inside its ceiling, so a retry never eats the consumer's poll interval. */
    @Test
    void backsOffExponentiallyWithinABoundedCeiling() {
        FailingDiffSource delegate = new FailingDiffSource(Integer.MAX_VALUE, 503);

        assertThrows(TestScmException.class, () -> source(delegate).fetchPullRequest(REPO, 42));

        assertEquals(2, slept.size());
        assertTrue(slept.get(0) >= 1 && slept.get(0) <= RetryingDiffSource.BASE_DELAY_MS,
                "first backoff within one base delay, was " + slept.get(0));
        assertTrue(slept.get(1) >= 1 && slept.get(1) <= RetryingDiffSource.BASE_DELAY_MS * 2,
                "second backoff within two base delays, was " + slept.get(1));
    }

    /**
     * The decorator is useless unwired, and nothing else would notice: every worker call goes through
     * {@code Clients}, so this is the assertion that the retry is actually in the path.
     */
    @Test
    void everyClientsGetsItsDiffSourceWrapped() {
        DiffSource raw = new FailingDiffSource(0, 200);

        WorkerScmClients.Clients clients = new WorkerScmClients.Clients(raw, null);

        assertTrue(clients.diff() instanceof RetryingDiffSource,
                "reads must be retried however the Clients was built");
    }

    /** Commands that only post resolve no diff source, and wrapping must not turn that into an NPE. */
    @Test
    void toleratesTheAbsenceOfADiffSource() {
        assertEquals(null, new WorkerScmClients.Clients(null, null).diff());
    }

    /** Re-wrapping would stack a retry on a retry, quietly cubing the attempt count. */
    @Test
    void doesNotWrapAnAlreadyWrappedSource() {
        DiffSource wrapped = new RetryingDiffSource(new FailingDiffSource(0, 200));

        assertEquals(wrapped, new WorkerScmClients.Clients(wrapped, null).diff());
    }

    /**
     * The breaker wraps the WHOLE ladder, so one exhausted ladder is one failure against the
     * threshold. Were it inside, three attempts of a single call would nearly trip it on their own
     * and the circuit would open on the first bad minute rather than on an outage.
     */
    @Test
    void anExhaustedRetryLadderCountsOnceTowardsOpeningTheCircuit() {
        FailingDiffSource delegate = new FailingDiffSource(Integer.MAX_VALUE, 503);
        RetryingDiffSource source = source(delegate);

        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD; i++) {
            assertThrows(TestScmException.class, () -> source.fetchPullRequest(REPO, 42));
        }

        assertEquals(RetryingDiffSource.MAX_ATTEMPTS * ProviderCircuits.FAILURE_THRESHOLD, delegate.calls,
                "every ladder ran in full — the breaker counted ladders, not attempts");
        assertThrows(ProviderCircuits.CircuitOpenException.class, () -> source.fetchPullRequest(REPO, 42));
        assertEquals(RetryingDiffSource.MAX_ATTEMPTS * ProviderCircuits.FAILURE_THRESHOLD, delegate.calls,
                "and now the provider is not called at all");
    }

    /** A settled answer is not ill health: a 404 per force-pushed commit must never open a circuit. */
    @Test
    void aSettledFailureNeverOpensTheCircuit() {
        FailingDiffSource delegate = new FailingDiffSource(Integer.MAX_VALUE, 404);
        RetryingDiffSource source = source(delegate);

        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD * 2; i++) {
            assertThrows(TestScmException.class, () -> source.fetchDiff(REPO, 42, "abc123"));
        }

        assertThrows(TestScmException.class, () -> source.fetchDiff(REPO, 42, "abc123"));
    }

    /** Circuits are keyed by the delegate's host, which is the whole reason apiHost() is on the port. */
    @Test
    void aDegradedHostDoesNotPauseAnother() {
        RetryingDiffSource sick = source(new FailingDiffSource(Integer.MAX_VALUE, 503, "sick.invalid"));
        RetryingDiffSource healthy = source(new FailingDiffSource(0, 200, "healthy.invalid"));

        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD; i++) {
            assertThrows(TestScmException.class, () -> sick.fetchPullRequest(REPO, 42));
        }

        assertThrows(ProviderCircuits.CircuitOpenException.class, () -> sick.fetchPullRequest(REPO, 42));
        assertNotNull(healthy.fetchPullRequest(REPO, 42), "a different host is a different circuit");
    }

    /**
     * A decorator that forgets a method does not fall through to its delegate — it inherits
     * {@link DiffSource}'s own default and silently answers null, replacing the real adapter's
     * implementation with nothing. The call still succeeds, so the only symptom is a feature that
     * never works. That is precisely how {@code fetchTextFileOnBranch} was lost when it was added,
     * and this is the guard so the next port method cannot repeat it.
     */
    @Test
    void delegatesEveryMethodOfThePort() {
        for (java.lang.reflect.Method method : DiffSource.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            assertDoesNotThrow(
                    () -> RetryingDiffSource.class.getDeclaredMethod(method.getName(), method.getParameterTypes()),
                    "RetryingDiffSource does not override " + method.getName()
                            + " — it would answer the interface default instead of the wrapped adapter");
        }
    }

    /** A fresh breaker per test, so one test's failures cannot open a circuit for the next. */
    private RetryingDiffSource source(DiffSource delegate) {
        // fixed seed: jitter is not the subject
        return new RetryingDiffSource(delegate, slept::add, new Random(7), circuits);
    }

    /** Fails the first {@code failures} calls with {@code status}, then succeeds. */
    private static final class FailingDiffSource implements DiffSource {
        private final int failures;
        private final int status;
        private final String host;
        int calls;

        FailingDiffSource(int failures, int status) {
            this(failures, status, "api.example.invalid");
        }

        FailingDiffSource(int failures, int status, String host) {
            this.failures = failures;
            this.status = status;
            this.host = host;
        }

        @Override
        public ScmType type() {
            return ScmType.GITHUB;
        }

        @Override
        public String apiHost() {
            return host;
        }

        @Override
        public PullRequest fetchPullRequest(RepoRef repo, long prId) {
            return guard(() -> new PullRequest(repo, prId, "title", "body", "head", "base",
                    "abc123", null, "https://example.invalid/pr/" + prId));
        }

        @Override
        public Diff fetchDiff(RepoRef repo, long prId, String commit) {
            return guard(() -> new Diff(commit, List.of(), false));
        }

        private <T> T guard(java.util.function.Supplier<T> success) {
            if (++calls <= failures) {
                throw new TestScmException(status);
            }
            return success.get();
        }
    }

    private static final class TestScmException extends RuntimeException implements ScmApiException {
        private final int status;

        TestScmException(int status) {
            super("upstream said " + status);
            this.status = status;
        }

        @Override
        public int status() {
            return status;
        }
    }
}
