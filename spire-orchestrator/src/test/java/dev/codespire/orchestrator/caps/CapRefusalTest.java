package dev.codespire.orchestrator.caps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One vocabulary for all three gates. Three refusals must be distinguishable by their TEXT, not only
 * by which line produced them — a reader of the timeline sees the sentence, never the call site.
 */
class CapRefusalTest {

    @Test
    void anAllowedDecisionCarriesNoWording() {
        CapRefusal decision = CapRefusal.allow();
        assertTrue(decision.allowed());
        assertFalse(decision.refused());
        assertEquals("", decision.detail());
        assertEquals("", decision.note());
    }

    @Test
    void eachRefusalNamesItsOwnLimitAndTheMeasuredValue() {
        String diff = CapRefusal.diffTooLarge(5_000, 900_000L).detail();
        String spend = CapRefusal.spendCapReached(750_000L, 500_000L).detail();
        String calls = CapRefusal.callCapReached(120, 100).detail();

        assertTrue(diff.contains("5000"), "names the measured file count: " + diff);
        assertTrue(spend.contains("7.50") || spend.contains("5.00"), "names money in dollars: " + spend);
        assertTrue(calls.contains("120") && calls.contains("100"), "names both figures: " + calls);

        assertEquals(3, java.util.Set.of(diff, spend, calls).size(),
                "three refusals must read differently, or the timeline cannot tell them apart");
    }

    @Test
    void aRefusalIsNotAllowed() {
        assertTrue(CapRefusal.diffTooLarge(1, 1L).refused());
        assertFalse(CapRefusal.diffTooLarge(1, 1L).allowed());
    }
}
