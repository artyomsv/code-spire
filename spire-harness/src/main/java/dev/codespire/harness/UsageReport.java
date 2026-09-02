package dev.codespire.harness;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a run consumed, or an explicit statement that the harness did not say.
 *
 * <p><b>Unknown is not zero.</b> ADR-023 exists because four separate places turned <i>unknown</i>
 * into <i>zero</i>, and a harness whose usage shape this adapter does not recognise must arrive
 * unpriceable rather than free. {@link #tokens} therefore throws on an unknown report instead of
 * answering a number nobody measured, and {@link #asMap} answers empty rather than an empty map —
 * a caller summing an empty map reaches the same fabricated zero by a different door.
 */
public final class UsageReport {

    private static final UsageReport UNKNOWN = new UsageReport(null);

    private final Map<TokenBucket, Long> counts;

    private UsageReport(Map<TokenBucket, Long> counts) {
        this.counts = counts;
    }

    public static UsageReport unknown() {
        return UNKNOWN;
    }

    public static UsageReport of(Map<TokenBucket, Long> counts) {
        if (counts.isEmpty()) {
            // An empty map is not a measurement. Allowing it would build a report whose
            // isUnknown() is false and whose every bucket reads 0 — the same fabricated zero,
            // through the front door. An adapter that does not recognise a vendor usage shape
            // must answer unknown(), not an empty measurement.
            throw new IllegalArgumentException(
                    "an empty count map is not a measurement — use UsageReport.unknown()");
        }
        Map<TokenBucket, Long> copy = new EnumMap<>(TokenBucket.class);
        counts.forEach((bucket, value) -> {
            if (value == null || value < 0) {
                throw new IllegalArgumentException("token count must be >= 0 for " + bucket + ": " + value);
            }
            copy.put(bucket, value);
        });
        return new UsageReport(Map.copyOf(copy));
    }

    public boolean isUnknown() {
        return counts == null;
    }

    /**
     * @throws IllegalStateException when this report is UNKNOWN. Ask {@link #isUnknown()} first.
     */
    public long tokens(TokenBucket bucket) {
        if (counts == null) {
            throw new IllegalStateException("usage is UNKNOWN; ask isUnknown() before reading a count");
        }
        return counts.getOrDefault(bucket, 0L);
    }

    /** @return the measured buckets, or empty when the harness reported nothing. */
    public Optional<Map<TokenBucket, Long>> asMap() {
        return Optional.ofNullable(counts);
    }

    /**
     * Value equality, because {@link RunEvent.Usage} is a record whose only interesting component is
     * a report, and record equality delegates to its components. Left as inherited identity equality,
     * two usage events carrying the same measurement compared unequal — so every test comparing a
     * parsed event had to destructure by hand, and any dedup of usage events silently kept both.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UsageReport that)) {
            return false;
        }
        return Objects.equals(counts, that.counts);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(counts);
    }

    @Override
    public String toString() {
        return counts == null ? "UsageReport[UNKNOWN]" : "UsageReport" + counts;
    }
}
