package dev.codespire.harness;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageReportTest {

    @Test
    void unknownIsNotZero() {
        UsageReport unknown = UsageReport.unknown();
        UsageReport freeCall = UsageReport.of(Map.of(TokenBucket.INPUT, 0L, TokenBucket.OUTPUT, 0L));

        assertTrue(unknown.isUnknown(), "a harness that reported nothing must say so");
        assertFalse(freeCall.isUnknown(), "a call that genuinely used zero tokens is a measurement");
        assertEquals(0L, freeCall.tokens(TokenBucket.INPUT));
    }

    @Test
    void unknownRefusesToAnswerTokenCounts() {
        UsageReport unknown = UsageReport.unknown();
        // An unknown report must not silently answer 0 — that is the shape ADR-023 exists to prevent.
        assertThrows(IllegalStateException.class, () -> unknown.tokens(TokenBucket.INPUT));
    }

    @Test
    void unknownCarriesNoMap() {
        // asMap() is the other read path. If it answered an empty map, a caller summing it would
        // reach the same fabricated zero by a different door.
        assertTrue(UsageReport.unknown().asMap().isEmpty());
        assertFalse(UsageReport.of(Map.of(TokenBucket.TOTAL, 7L)).asMap().isEmpty());
    }

    @Test
    void aBucketTheHarnessDidNotReportReadsZeroOnAKnownReport() {
        // Distinct from UNKNOWN: the harness DID report, and said nothing about REASONING, which for
        // a non-reasoning model is a true zero rather than an absent measurement.
        UsageReport report = UsageReport.of(Map.of(TokenBucket.INPUT, 12L));
        assertEquals(0L, report.tokens(TokenBucket.REASONING));
    }

    @Test
    void negativeCountsAreRefused() {
        // A buggy proxy reporting -1 must not reach a ledger CHECK constraint as a dead-lettered run.
        assertThrows(IllegalArgumentException.class,
                () -> UsageReport.of(Map.of(TokenBucket.INPUT, -1L)));
    }

    @Test
    void nullCountsAreRefused() {
        Map<TokenBucket, Long> counts = new HashMap<>();
        counts.put(TokenBucket.INPUT, null);
        assertThrows(IllegalArgumentException.class, () -> UsageReport.of(counts));
    }

    @Test
    void theReportDoesNotAliasTheCallersMap() {
        Map<TokenBucket, Long> mutable = new HashMap<>(Map.of(TokenBucket.INPUT, 5L));
        UsageReport report = UsageReport.of(mutable);
        mutable.put(TokenBucket.INPUT, 999L);

        assertEquals(5L, report.tokens(TokenBucket.INPUT), "usage must be a snapshot, not a view");
    }

    @Test
    void toStringSaysUnknownRatherThanRenderingAnEmptyMap() {
        // A diagnostic line reading "UsageReport{}" is indistinguishable from a run that measured
        // nothing, which is the same confusion the type exists to remove.
        assertTrue(UsageReport.unknown().toString().contains("UNKNOWN"),
                UsageReport.unknown().toString());
        assertFalse(UsageReport.of(Map.of(TokenBucket.INPUT, 3L)).toString().contains("UNKNOWN"));
        assertTrue(UsageReport.of(Map.of(TokenBucket.INPUT, 3L)).toString().contains("3"));
    }

    @Test
    void twoReportsOfTheSameMeasurementAreEqual() {
        // RunEvent.Usage is a record whose only interesting component is a report, and record
        // equality delegates to its components. Left as inherited identity equality, two usage
        // events carrying the same measurement compared unequal, so every test comparing a parsed
        // event had to destructure by hand and any dedup of usage events silently kept both.
        assertEquals(UsageReport.of(Map.of(TokenBucket.INPUT, 5L)),
                UsageReport.of(Map.of(TokenBucket.INPUT, 5L)));
        assertEquals(UsageReport.of(Map.of(TokenBucket.INPUT, 5L)).hashCode(),
                UsageReport.of(Map.of(TokenBucket.INPUT, 5L)).hashCode());
        assertEquals(UsageReport.unknown(), UsageReport.unknown());
    }

    @Test
    void unknownIsNeverEqualToAMeasurement() {
        // The distinction the whole type exists for must survive into equality: a report of zero
        // is a measurement and unknown is not, so the two must never compare equal.
        assertNotEquals(UsageReport.unknown(), UsageReport.of(Map.of(TokenBucket.INPUT, 0L)));
        assertNotEquals(UsageReport.of(Map.of(TokenBucket.INPUT, 5L)),
                UsageReport.of(Map.of(TokenBucket.INPUT, 6L)));
    }

    @Test
    void anEmptyCountMapIsNotAMeasurement() {
        // The front door to the same fabricated zero: an empty map builds a report whose
        // isUnknown() is false and whose every bucket reads 0. An adapter that fails to recognise
        // a vendor's usage field names would reach it by accident, and the ledger would price the
        // run at zero. Refused at the boundary rather than guarded by every caller.
        assertThrows(IllegalArgumentException.class, () -> UsageReport.of(Map.of()));
    }
}
