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
    void eachRefusalNamesTheMeasuredValue() {
        String diff = CapRefusal.diffTooLarge(5_000, 900_000L).detail();
        String spend = CapRefusal.spendCapReached(750_000L, 500_000L).detail();
        String calls = CapRefusal.callCapReached(120, 100).detail();

        assertTrue(diff.contains("5000"), "names the measured file count: " + diff);
        assertTrue(spend.contains("7.50"), "names the measured spend in dollars: " + spend);
        assertTrue(calls.contains("120"), "names the measured call count: " + calls);

        assertEquals(3, java.util.Set.of(diff, spend, calls).size(),
                "three refusals must read differently, or the timeline cannot tell them apart");
    }

    /**
     * The three surfaces {@code detail()} and {@code note()} reach — the review timeline, the review's
     * note and the {@code CAP_REACHED} attention row — are all readable by {@code spire-viewer}, and
     * ADR-022's third rule makes configuration admin-only <em>including its reads</em>. The cap is the
     * deployment's spend policy; the measured figure is this review's own context and stays.
     *
     * <p>Asserted against the rendered strings rather than the record's fields: the field may keep
     * carrying the limit (the log needs it), so only what is rendered can be the assertion.
     */
    @Test
    void theConfiguredCapNeverReachesAViewerReadableSurface() {
        CapRefusal spend = CapRefusal.spendCapReached(750_000L, 500_000L);
        CapRefusal calls = CapRefusal.callCapReached(120, 100);

        assertFalse(spend.detail().contains("5.00"), "the configured cap is configuration: " + spend.detail());
        assertFalse(spend.note().contains("5.00"), "and the note is the same surface: " + spend.note());
        assertFalse(calls.detail().contains("100"), "the configured cap is configuration: " + calls.detail());
        assertFalse(calls.note().contains("100"), "and the note is the same surface: " + calls.note());
    }

    /**
     * The log is the one surface no viewer reads, so it keeps both halves of the comparison — an
     * operator diagnosing a refusal needs the limit beside the measurement. A suffix on
     * {@code detail()}, never a second wording, so the two cannot drift.
     */
    @Test
    void theLogRenderingKeepsTheConfiguredCap() {
        CapRefusal calls = CapRefusal.callCapReached(120, 100);

        assertTrue(calls.logDetail().startsWith(calls.detail()),
                "one vocabulary, one suffix: " + calls.logDetail());
        assertTrue(calls.logDetail().contains("100"), "and it names the limit: " + calls.logDetail());
        assertEquals(CapRefusal.diffTooLarge(5_000, 900_000L).detail(),
                CapRefusal.diffTooLarge(5_000, 900_000L).logDetail(),
                "the diff refusal carries no configured limit, so there is nothing to append");
    }

    /** A money figure in operator-facing text: a de-DE default would otherwise render "$7,50". */
    @Test
    void moneyIsRenderedLocaleIndependently() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertTrue(CapRefusal.spendCapReached(750_000L, 500_000L).detail().contains("$7.50"),
                    "a decimal comma in a dollar figure is a locale leak, not a formatting choice");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void aRefusalIsNotAllowed() {
        assertTrue(CapRefusal.diffTooLarge(1, 1L).refused());
        assertFalse(CapRefusal.diffTooLarge(1, 1L).allowed());
    }
}
