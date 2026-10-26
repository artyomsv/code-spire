package dev.codespire.contract.work;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A step's summary on the wire (M4 slice 8, §6.8, A13): each list holds at most 20 items of at most 500 characters. */
class StepSummaryTest {

    private static final List<String> NONE = List.of();

    @Test
    void listsAndItemsAreCapped() {
        List<String> twenty = Collections.nCopies(StepSummary.MAX_ITEMS, "TEST-item");
        List<String> twentyOne = Collections.nCopies(StepSummary.MAX_ITEMS + 1, "TEST-item");
        String atCap = "T".repeat(StepSummary.MAX_ITEM_CHARS);
        String overCap = "T".repeat(StepSummary.MAX_ITEM_CHARS + 1);

        assertEquals(twenty, new StepSummary(twenty, NONE, NONE).decisions());
        assertEquals(List.of(atCap), new StepSummary(NONE, List.of(atCap), NONE).alternativesRejected());
        assertThrows(IllegalArgumentException.class, () -> new StepSummary(twentyOne, NONE, NONE));
        assertThrows(IllegalArgumentException.class, () -> new StepSummary(NONE, twentyOne, NONE));
        assertThrows(IllegalArgumentException.class, () -> new StepSummary(NONE, NONE, twentyOne));
        assertThrows(IllegalArgumentException.class, () -> new StepSummary(List.of(overCap), NONE, NONE));
        assertThrows(IllegalArgumentException.class, () -> new StepSummary(NONE, List.of(overCap), NONE));
        assertThrows(IllegalArgumentException.class, () -> new StepSummary(NONE, NONE, List.of(overCap)));
        // 500 emoji are 1,000 UTF-16 units and 500 characters: the cap a reader sees is the one applied.
        String emoji = "\uD83D\uDE00".repeat(StepSummary.MAX_ITEM_CHARS);
        assertEquals(List.of(emoji), new StepSummary(List.of(emoji), NONE, NONE).decisions());
    }

    @Test
    void theListsAreRequiredAndCopied() {
        List<String> decisions = new ArrayList<>(List.of("TEST-decision"));
        StepSummary summary = new StepSummary(decisions, NONE, NONE);
        decisions.clear();

        assertEquals(List.of("TEST-decision"), summary.decisions());
        assertThrows(UnsupportedOperationException.class, () -> summary.decisions().clear());
        assertThrows(NullPointerException.class, () -> new StepSummary(null, NONE, NONE));
        assertThrows(NullPointerException.class, () -> new StepSummary(NONE, null, NONE));
        assertThrows(NullPointerException.class, () -> new StepSummary(NONE, NONE, null));
    }
}
