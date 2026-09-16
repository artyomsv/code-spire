package dev.codespire.contract.llm;

import dev.codespire.contract.review.TokenType;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which token types a harness can report, so a dispatch can refuse a model that cannot price them.
 *
 * <p>This exists because the check before a run asked a weaker question than the harness can answer.
 * A model priced for INPUT and OUTPUT passed it; the codex arm then reported CACHED_INPUT and
 * REASONING as well, those lines had no rate, the run's cost became UNKNOWN, and the item stopped
 * after it had already spent — the operator's item 36, on 2026-09-15.
 *
 * <p>It declares what a harness CAN report, not what one run did. Every observed codex run has
 * reported zero cache writes, and the adapter's own note says the alternative is not ruled out by
 * measurement; a completeness check built on that observation would pass until the first non-zero
 * cache write, and then stop an item mid-flight for a rate nobody was asked for.
 *
 * <p>The declaration lives here rather than on {@code HarnessCapabilities} because the orchestrator,
 * which decides whether a run may start, has no dependency on the harness tier and never sees an
 * adapter — the adapters run in the worker. The cost of that is drift, so it is paid the way
 * {@code TokenBucketMatchesLedgerDimensionsTest} pays it: a test in the ADAPTER's own module feeds a
 * usage line and compares what the adapter emits against what this class declares.
 *
 * <p>{@link TokenType#TOTAL} is never included. It is the degraded, unreconciled case, and it can
 * never carry a metered rate.
 */
public final class HarnessTokenReport {

    /** Every priceable type. What an unlisted harness is assumed to report, because guessing low spends money. */
    public static final Set<TokenType> EVERY_PRICEABLE_TYPE = Set.copyOf(EnumSet.complementOf(EnumSet.of(TokenType.TOTAL)));

    private static final Map<String, Set<TokenType>> REPORTED = Map.of(
            "codex", Set.copyOf(EnumSet.of(TokenType.INPUT, TokenType.CACHED_INPUT, TokenType.CACHE_WRITE,
                    TokenType.OUTPUT, TokenType.REASONING)));

    private HarnessTokenReport() {}

    /**
     * What this harness can report. An unknown name answers with every priceable type: a harness this
     * build does not know is not a harness that reports less, and the refusal it causes is one an
     * operator can clear by entering a rate or asserting that the vendor does not bill it.
     */
    public static Set<TokenType> reportedBy(String harness) {
        if (harness == null) return EVERY_PRICEABLE_TYPE;
        return REPORTED.getOrDefault(harness.trim(), EVERY_PRICEABLE_TYPE);
    }

    /** The harness names this class declares, for a test that pins each one against its adapter. */
    public static Set<String> declared() {
        return REPORTED.keySet();
    }
}
