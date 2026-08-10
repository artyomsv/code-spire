package dev.codespire.orchestrator.caps;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comparison itself, at its boundary, on both axes — hand-built fakes, no container.
 *
 * <p>{@code SpendCapGateTest} drives the gate through the real ledger and settings store, which is what
 * makes it a delta test against ambient usage and therefore the wrong place to pin an exact threshold.
 * The money axis in particular was reachable by no test at any level: that suite deliberately sets the
 * spend cap out of reach so only the call axis can fire, and the lifecycle tests construct a
 * {@code CapRefusal} by hand. A flipped comparison or a swapped argument on
 * {@code usage.spentMillicents() >= spendCap} was invisible.
 */
class SpendGateTest {

    private static final long SPEND_CAP = 500_000L;
    private static final int CALL_CAP = 100;

    /**
     * A budget is exhausted when it has been consumed, so the cap value itself refuses. Asserted
     * because the diff gate deliberately refuses on {@code >} instead, and an undocumented,
     * unasserted difference between the two is indistinguishable from the drift this bean exists to
     * prevent.
     */
    @Test
    void spendingExactlyTheCapIsAlreadyOverIt() {
        assertTrue(gate(SPEND_CAP).decide().refused(), "a consumed budget is spent, not spendable");
        assertTrue(gate(SPEND_CAP - 1).decide().allowed(), "and one millicent short is not");
    }

    @Test
    void theCallAxisUsesTheSameBoundary() {
        assertTrue(gateWithCalls(CALL_CAP).decide().refused());
        assertTrue(gateWithCalls(CALL_CAP - 1).decide().allowed());
    }

    /** The money axis names the measured spend, so a swapped argument cannot pass unnoticed. */
    @Test
    void theMoneyAxisRefusesOnMoneyAndSaysSo() {
        CapRefusal refusal = gate(750_000L).decide().refusal();

        assertEquals(CapRefusal.Reason.SPEND_CAP_REACHED, refusal.reason());
        assertTrue(refusal.detail().contains("$7.50"), "names what was measured: " + refusal.detail());
    }

    /**
     * Fail open, and say so. Answering {@code Usage(0, 0)} on a failed read made an unreadable ledger
     * indistinguishable from an empty one, so the attention row saw "allowed" and reported health while
     * the cap was refusing nothing. The allow decision is deliberately unchanged — a cap that refuses
     * every review because its own query failed is an outage that looks like policy.
     */
    @Test
    void anUnreadableLedgerAllowsTheCallAndReportsItselfDegraded() {
        SpendGate gate = new SpendGate();
        gate.policy = policy(OptionalLong.of(SPEND_CAP), OptionalInt.empty());
        gate.window = new SpendWindow() {
            @Override
            public Optional<Usage> since(Instant from) {
                return Optional.empty();
            }
        };

        SpendGate.Decision decision = gate.decide();

        assertTrue(decision.allowed(), "fail open: the paid call still happens");
        assertTrue(decision.ledgerUnreadable(), "but nobody may be told the cap is healthy");
    }

    /**
     * With nothing configured the ledger is never read, so there is no enforcement to be degraded — the
     * degraded flag must not fire on a deployment that simply has no cap.
     */
    @Test
    void anUnsetCapReadsNothingAndIsNotDegraded() {
        SpendGate gate = new SpendGate();
        gate.policy = policy(OptionalLong.empty(), OptionalInt.empty());
        gate.window = new SpendWindow() {
            @Override
            public Optional<Usage> since(Instant from) {
                throw new AssertionError("unset must skip the ledger read entirely, not query and allow");
            }
        };

        SpendGate.Decision decision = gate.decide();

        assertTrue(decision.allowed());
        assertFalse(decision.ledgerUnreadable());
    }

    private static SpendGate gate(long spentMillicents) {
        return gateReporting(new SpendWindow.Usage(spentMillicents, 0),
                OptionalLong.of(SPEND_CAP), OptionalInt.empty());
    }

    private static SpendGate gateWithCalls(int calls) {
        return gateReporting(new SpendWindow.Usage(0L, calls), OptionalLong.empty(),
                OptionalInt.of(CALL_CAP));
    }

    private static SpendGate gateReporting(SpendWindow.Usage usage, OptionalLong spendCap,
                                           OptionalInt callCap) {
        SpendGate gate = new SpendGate();
        gate.policy = policy(spendCap, callCap);
        gate.window = new SpendWindow() {
            @Override
            public Optional<Usage> since(Instant from) {
                return Optional.of(usage);
            }
        };
        return gate;
    }

    private static CapPolicy policy(OptionalLong spendCap, OptionalInt callCap) {
        return new CapPolicy() {
            @Override
            public OptionalLong spendCapMillicents() {
                return spendCap;
            }

            @Override
            public OptionalInt callCap() {
                return callCap;
            }

            @Override
            public Duration window() {
                return Duration.ofMinutes(60);
            }
        };
    }
}
