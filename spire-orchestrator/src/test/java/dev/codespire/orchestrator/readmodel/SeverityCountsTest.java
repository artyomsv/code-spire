package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.Severity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The list row's per-severity counts: one slot per dashboard slug, summing to the open total. */
class SeverityCountsTest {

    @Test
    void countsEachSeverityInItsOwnSlot() {
        SeverityCounts counts = SeverityCounts.of(List.of(Severity.MINOR, Severity.BLOCKER, Severity.MINOR,
                Severity.MAJOR, Severity.MAJOR, Severity.MINOR, Severity.MINOR));

        assertEquals(new SeverityCounts(1, 2, 4, 0), counts);
    }

    /** The findings card shows INFO and NIT both as {@code nit}; the list must not name a fifth severity. */
    @Test
    void infoAndNitShareTheNitSlot() {
        assertEquals(new SeverityCounts(0, 0, 0, 2), SeverityCounts.of(List.of(Severity.INFO, Severity.NIT)));
    }

    @Test
    void noOpenFindingsIsAllZeros() {
        assertEquals(new SeverityCounts(0, 0, 0, 0), SeverityCounts.of(List.of()));
        assertEquals(new SeverityCounts(0, 0, 0, 0), OpenFindingCounts.of(Map.of(), 0).bySeverity());
    }

    /**
     * Counted from the same keyed set as the total, carried-over findings included, so the badges add up to the
     * number they replace. A stored finding with no severity (an old row) counts as a nit, never as nothing.
     */
    @Test
    void theBadgesSumToTheOpenTotalWithCarriedFindingsIncluded() {
        Map<String, String> open = new LinkedHashMap<>();
        open.put("l:src/New.java:1", "critical");
        open.put("l:src/New.java:2", "suggestion");
        open.put("t:carried-1", "warning");
        open.put("t:carried-2", "suggestion");
        open.put("t:old-row", null);

        OpenFindingCounts counts = OpenFindingCounts.of(open, 2);

        assertEquals(new SeverityCounts(1, 1, 2, 1), counts.bySeverity());
        assertEquals(3, counts.carriedOver());
        SeverityCounts by = counts.bySeverity();
        assertEquals(counts.open(), by.critical() + by.warning() + by.suggestion() + by.nit());
    }
}
