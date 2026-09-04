package dev.codespire.orchestrator.factory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unknown is not zero — ADR-023 as a type rather than as a rule people remember.
 *
 * <p>This project has paid for the confusion once already: {@code SUM()} skips NULL, so a run whose
 * charges could not be priced totals to whatever the priced ones came to, and a run with no charges
 * totals to nothing. Both render as "free" beside runs that really were.
 */
class RunCostTest {

    @Test
    void anUnknownCostIsNotZero() {
        assertFalse(RunCost.unknown().isKnown());
        assertTrue(RunCost.unknown().millicents() == null);
        assertEquals(RunCost.unknown(), RunCost.unknown(), "and it is one value, so callers may compare");
    }

    /**
     * <b>Zero is a legitimate KNOWN cost, and collapsing it into unknown would be the mirror error.</b>
     *
     * <p>An UNMETERED model is priced at zero by definition — V30's own CHECK requires exactly that —
     * so a self-hosted deployment's real answer is zero, and reporting it as "nobody knows" would hide
     * the one number it can be sure of.
     */
    @Test
    void zeroIsAKnownAnswerBecauseAnUnmeteredModelReallyCostsNothing() {
        assertTrue(RunCost.of(0).isKnown());
        assertEquals(0L, RunCost.of(0).millicents().longValue());
    }

    /** A negative cost is a join bug, not data — V31 constrains the column non-negative. */
    @Test
    void aNegativeCostIsRefusedRatherThanRenderedAsARefund() {
        assertThrows(IllegalArgumentException.class, () -> RunCost.of(-1));
    }

    /**
     * <b>A total is unknown if ANY member is.</b>
     *
     * <p>This is the property a list footer needs and the one a naive sum destroys: adding the priced
     * rows and skipping the rest produces a number that looks like a total, is smaller than the truth,
     * and carries no sign that anything is missing.
     */
    @Test
    void aTotalIsUnknownIfAnySingleRunIs() {
        assertEquals(RunCost.of(300), RunCost.of(100).plus(RunCost.of(200)));

        assertFalse(RunCost.of(100).plus(RunCost.unknown()).isKnown());
        assertFalse(RunCost.unknown().plus(RunCost.of(100)).isKnown(),
                "and in both directions, or the answer depends on iteration order");
        assertFalse(RunCost.unknown().plus(RunCost.unknown()).isKnown());
    }

    /** Adding nothing known to something known changes nothing — the identity a footer relies on. */
    @Test
    void addingZeroLeavesAKnownTotalAlone() {
        assertEquals(RunCost.of(100), RunCost.of(100).plus(RunCost.of(0)));
    }
}
