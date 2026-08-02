package dev.codespire.worker.adapters;

import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Stops every review paying its own retry ladder into a provider that is not blipping but down.
 *
 * <p>{@link RetryingDiffSource} absorbs a single transient failure, and ADR-016's saga budget absorbs
 * a failed pipeline. Neither covers sustained degradation: a provider answering 5xx for an hour gets
 * a full retry ladder per call, per review, from every command that arrives — wasted work that also
 * prolongs recovery for a provider already struggling.
 *
 * <p><b>State is per API host and shared across commands, which is the whole point.</b>
 * {@code WorkerScmClients.Clients} is rebuilt for every command, so a breaker owned by one of those
 * would never see a second failure and could never open. Hosts stay independent — that is why
 * {@code DiffSource.apiHost()} exists rather than keying on {@code type()}, which would let one
 * self-managed instance being down pause reviews on every other instance of the same platform.
 *
 * <p><b>An open circuit is a RETRYABLE failure.</b> It means "this provider is down right now",
 * which is the definition of transient, so the command classifies like a 503 and ADR-016 re-drives
 * it on the V25 scheduled backoff. The review reaches the same end state it would have reached
 * without the breaker — terminal once the attempt budget is spent — but stops paying a retry ladder
 * per call to get there. Failing terminally instead would turn one outage into a pile of
 * permanently-failed reviews needing manual re-runs, which is worse than the waste it replaces.
 *
 * <p>Hand-rolled rather than SmallRye Fault Tolerance, following ADR-016: that decision rejected the
 * FT extension and left per-call resilience as a thin layer under the saga budget, which is what
 * {@link RetryingDiffSource} is. The per-host registry above is needed either way, and the extension
 * is a larger footprint than the guard it would provide.
 */
public class ProviderCircuits {

    private static final Logger LOG = Logger.getLogger(ProviderCircuits.class);

    /**
     * Consecutive failed calls that open a circuit. Each of those has already exhausted
     * {@link RetryingDiffSource}'s own attempts, so this is five failed ladders, not five requests —
     * comfortably past anything a blip produces.
     */
    static final int FAILURE_THRESHOLD = 5;

    /** How long a circuit stays open before one probe is let through. */
    static final long COOLDOWN_MS = 30_000;

    /**
     * Shared by every command in this worker: the only scope at which a breaker sees enough failures
     * to open. Not a CDI bean — {@code Clients} is a record built in a compact constructor with no
     * injection point, and the state must outlive it regardless.
     */
    private static final ProviderCircuits SHARED = new ProviderCircuits(System::currentTimeMillis);

    private final Map<String, Circuit> byHost = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    /** Separated so a test moves time without spending it — the reason RetryingDiffSource has a Sleeper. */
    ProviderCircuits(LongSupplier clock) {
        this.clock = clock;
    }

    public static ProviderCircuits shared() {
        return SHARED;
    }

    /** Thrown INSTEAD of calling a provider whose circuit is open. Classified retryable by both workers. */
    public static class CircuitOpenException extends RuntimeException {
        public CircuitOpenException(String host) {
            super(host + " is failing consistently — calls are paused briefly to let it recover");
        }
    }

    private enum State { CLOSED, OPEN, HALF_OPEN }

    /**
     * Refuses the call when the circuit for {@code host} is open; otherwise runs it and records the
     * outcome. A null or blank host disables the breaker for that call rather than lumping every
     * unknown host onto one shared circuit.
     *
     * <p><b>{@code unhealthy} decides what counts against the provider, and it is not "threw".</b> A
     * 404 for a force-pushed commit or a 403 for a repository the token cannot see are answers, not
     * illness — the provider is up and told us something true. Counting them would let one
     * misconfigured repository open the circuit for every review on that host, turning a
     * one-repository problem into a host-wide outage of our own making. Anything not matched is
     * treated as the provider having answered, and closes the circuit.
     */
    public <T> T guard(String host, Supplier<T> call, Predicate<RuntimeException> unhealthy) {
        if (host == null || host.isBlank()) {
            return call.get();
        }
        Circuit circuit = byHost.computeIfAbsent(host, Circuit::new);
        circuit.requireClosed();
        try {
            T result = call.get();
            circuit.recordSuccess();
            return result;
        } catch (RuntimeException e) {
            if (unhealthy.test(e)) {
                circuit.recordFailure();
            } else {
                circuit.recordSuccess();
            }
            throw e;
        }
    }

    /** Package-private so a test starts from a known state; production never needs it. */
    void reset() {
        byHost.clear();
    }

    private final class Circuit {

        private final String host;
        /** State and its counters change together, so they move as one atomically-swapped snapshot. */
        private final AtomicReference<Snapshot> snapshot =
                new AtomicReference<>(new Snapshot(State.CLOSED, 0, 0));

        private Circuit(String host) {
            this.host = host;
        }

        private void requireClosed() {
            Snapshot current = snapshot.get();
            if (current.state() == State.CLOSED) {
                return;
            }
            if (current.state() == State.OPEN && clock.getAsLong() - current.openedAt() >= COOLDOWN_MS
                    && snapshot.compareAndSet(current,
                            new Snapshot(State.HALF_OPEN, current.failures(), current.openedAt()))) {
                // Exactly one caller wins the CAS and becomes the probe; everyone else is still
                // refused, so a recovering provider gets one request rather than every queued review.
                LOG.infof("Probing %s after %dms — one call allowed through", host, COOLDOWN_MS);
                return;
            }
            throw new CircuitOpenException(host);
        }

        private void recordSuccess() {
            Snapshot previous = snapshot.getAndSet(new Snapshot(State.CLOSED, 0, 0));
            if (previous.state() != State.CLOSED) {
                LOG.infof("%s answered again — resuming calls", host);
            }
        }

        /**
         * A failed probe re-opens for a full cooldown, and needs no special case to do it: only
         * {@link #recordSuccess} clears the counter, so a circuit that reaches HALF_OPEN still
         * carries the failures that opened it and the very next one is past the threshold again.
         * An earlier draft branched on HALF_OPEN here; mutation testing showed the branch changed
         * no behaviour, so it is a log message rather than a state rule.
         */
        private void recordFailure() {
            Snapshot current = snapshot.get();
            int failures = current.failures() + 1;
            if (failures < FAILURE_THRESHOLD) {
                snapshot.set(new Snapshot(State.CLOSED, failures, current.openedAt()));
                return;
            }
            snapshot.set(new Snapshot(State.OPEN, failures, clock.getAsLong()));
            if (current.state() == State.HALF_OPEN) {
                LOG.warnf("%s failed its probe — pausing calls for another %dms", host, COOLDOWN_MS);
            } else {
                LOG.warnf("%s failed %d consecutive calls — pausing calls for %dms so further reviews "
                        + "stop paying a retry ladder each", host, failures, COOLDOWN_MS);
            }
        }
    }

    /** @param openedAt when the circuit last opened; meaningful only while OPEN or HALF_OPEN. */
    private record Snapshot(State state, int failures, long openedAt) {
    }
}
