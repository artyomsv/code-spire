package dev.codespire.llmcatalog;

import java.util.List;

/** The bounds every catalog file, and every manual model rate, must stay within. */
public final class CatalogLimits {

    /** A catalog file larger than this is refused before it is parsed. */
    public static final int MAX_BYTES = 1_048_576;

    /** The highest rate accepted, in millicents per 1M tokens ($10,000 per 1M). */
    public static final long MAX_RATE_MILLICENTS_PER_MILLION = 1_000_000_000L;

    /** The ledger token types a {@code notBilled} entry may name. */
    public static final List<String> LEDGER_TYPES = List.of("INPUT", "CACHED_INPUT", "CACHE_WRITE", "OUTPUT",
            "REASONING");

    private CatalogLimits() {
    }
}
