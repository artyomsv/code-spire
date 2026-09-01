package dev.codespire.harness;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
