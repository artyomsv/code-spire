package dev.codespire.worker.adapters;

import dev.codespire.worker.adapters.ProviderCircuits.CircuitOpenException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The breaker that stops every review paying its own retry ladder into a provider that is down
 * rather than blipping.
 */
class ProviderCircuitsTest {

    private static final String HOST = "api.example.invalid";
    private static final String OTHER_HOST = "git.other.invalid";

    private final AtomicLong now = new AtomicLong();
    private final ProviderCircuits circuits = new ProviderCircuits(now::get);
    private final AtomicInteger calls = new AtomicInteger();

    /** A provider that is unwell — the only kind of failure that counts toward opening. */
    private static final class Unwell extends RuntimeException {
    }

    /** A definite answer: the repository is gone, the token cannot see it. The provider is fine. */
    private static final class Answered extends RuntimeException {
    }

    private String failWith(String host, RuntimeException failure) {
        return circuits.guard(host, () -> {
            calls.incrementAndGet();
            throw failure;
        }, e -> e instanceof Unwell);
    }

    private String succeed(String host) {
        return circuits.guard(host, () -> {
            calls.incrementAndGet();
            return "ok";
        }, e -> e instanceof Unwell);
    }

    private void driveToOpen(String host) {
        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD; i++) {
            assertThrows(Unwell.class, () -> failWith(host, new Unwell()));
        }
    }

    @Test
    void passesCallsThroughWhileTheProviderIsHealthy() {
        assertEquals("ok", succeed(HOST));
        assertEquals(1, calls.get());
    }

    @Test
    void opensAfterTheFailureThresholdAndStopsCallingTheProvider() {
        driveToOpen(HOST);
        int callsWhileClosed = calls.get();

        assertThrows(CircuitOpenException.class, () -> succeed(HOST));

        assertEquals(callsWhileClosed, calls.get(), "an open circuit must not reach the provider");
    }

    /** One good answer resets the count, so scattered failures over hours never accumulate to open. */
    @Test
    void aSuccessResetsTheFailureCount() {
        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD - 1; i++) {
            assertThrows(Unwell.class, () -> failWith(HOST, new Unwell()));
        }
        succeed(HOST);
        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD - 1; i++) {
            assertThrows(Unwell.class, () -> failWith(HOST, new Unwell()));
        }

        assertDoesNotThrow(() -> succeed(HOST), "the run was broken, so the circuit never opened");
    }

    /**
     * The failure this breaker must NOT count. A 404 for a force-pushed commit or a 403 for a
     * repository the token cannot see are the provider answering correctly. Counting them would let
     * one misconfigured repository open the circuit for every review on that host — a host-wide
     * outage of our own making, out of a one-repository problem.
     */
    @Test
    void doesNotCountAFailureThatMeansTheProviderAnswered() {
        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD * 3; i++) {
            assertThrows(Answered.class, () -> failWith(HOST, new Answered()));
        }

        assertDoesNotThrow(() -> succeed(HOST), "definite answers are not ill health");
    }

    /**
     * The reason {@code DiffSource.apiHost()} exists rather than keying on {@code type()}: two
     * self-managed instances of one platform are independent systems, and one being down must not
     * pause reviews on the other.
     */
    @Test
    void oneHostGoingDownLeavesAnotherAlone() {
        driveToOpen(HOST);

        assertThrows(CircuitOpenException.class, () -> succeed(HOST));
        assertDoesNotThrow(() -> succeed(OTHER_HOST));
    }

    @Test
    void staysOpenUntilTheCooldownElapses() {
        driveToOpen(HOST);

        now.addAndGet(ProviderCircuits.COOLDOWN_MS - 1);
        assertThrows(CircuitOpenException.class, () -> succeed(HOST));

        now.addAndGet(1);
        assertDoesNotThrow(() -> succeed(HOST), "the cooldown elapsed, so one probe goes through");
    }

    /** A successful probe closes the circuit outright, not just for that one call. */
    @Test
    void aSuccessfulProbeResumesNormalService() {
        driveToOpen(HOST);
        now.addAndGet(ProviderCircuits.COOLDOWN_MS);

        succeed(HOST); // the probe
        int after = calls.get();

        assertEquals("ok", succeed(HOST));
        assertEquals(after + 1, calls.get(), "traffic resumed rather than needing another cooldown");
    }

    /**
     * A failed probe must re-open on its own evidence. If it fell back to counting toward the
     * threshold, the next four callers would all be let through — the circuit would be open in name
     * while a down provider took nearly full traffic.
     */
    @Test
    void aFailedProbeReopensImmediatelyRatherThanCountingAgain() {
        driveToOpen(HOST);
        now.addAndGet(ProviderCircuits.COOLDOWN_MS);

        assertThrows(Unwell.class, () -> failWith(HOST, new Unwell())); // the probe fails
        int afterProbe = calls.get();

        assertThrows(CircuitOpenException.class, () -> succeed(HOST));
        assertEquals(afterProbe, calls.get(), "the provider must not be called again straight away");

        now.addAndGet(ProviderCircuits.COOLDOWN_MS);
        assertDoesNotThrow(() -> succeed(HOST), "and a full second cooldown is what reopens it");
    }

    /** Only one caller probes; the rest are still refused, so a recovering provider is not stampeded. */
    @Test
    void onlyOneCallerProbesAfterTheCooldown() {
        driveToOpen(HOST);
        now.addAndGet(ProviderCircuits.COOLDOWN_MS);
        int beforeProbe = calls.get();

        // The probe blocks nothing here, but the second caller arrives while the circuit is HALF_OPEN.
        assertThrows(Unwell.class, () -> failWith(HOST, new Unwell()));
        assertThrows(CircuitOpenException.class, () -> succeed(HOST));

        assertEquals(beforeProbe + 1, calls.get(), "exactly one call reached the provider");
    }

    /** No host means no key; guarding every unknown caller on one shared circuit would be worse. */
    @Test
    void aMissingHostDisablesTheBreakerRatherThanSharingACircuit() {
        for (int i = 0; i < ProviderCircuits.FAILURE_THRESHOLD * 2; i++) {
            assertThrows(Unwell.class, () -> failWith(null, new Unwell()));
        }

        assertDoesNotThrow(() -> succeed(null));
    }
}
