package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenCount;
import dev.codespire.contract.review.TokenType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pricing turns a token partition into charge lines. The cases that matter are the ones where a price
 * is NOT simply available: an uncatalogued model, a dimension with no rate, and an asserted zero. Each
 * must be distinguishable in the result, because collapsing any of them to a plain 0 is the defect
 * this whole change exists to remove.
 *
 * <p>Rates below are obviously-synthetic round numbers, not any vendor's real published price.
 */
@QuarkusTest
class LlmModelRegistryPricingTest {

    @Inject
    LlmModelRegistry registry;

    private LlmModelView metered(String name, Map<String, Long> rates) {
        return registry.create(new LlmModelInput("openai", name, "TEST " + name, "METERED", rates,
                null, null, null, Map.of(), true));
    }

    @Test
    void aMeteredCallIsPricedPerTypeAtTheStoredRate() {
        metered("TEST-METERED-1", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L));

        List<ChargeLine> lines = registry.priceCall("TEST-METERED-1",
                new ModelUsage("TEST-METERED-1",
                        List.of(new TokenCount(TokenType.INPUT, 1_000_000),
                                new TokenCount(TokenType.OUTPUT, 500_000)),
                        1_500_000, true));

        assertEquals(2, lines.size());
        ChargeLine input = lines.stream().filter(l -> l.tokenType() == TokenType.INPUT).findFirst().orElseThrow();
        assertEquals(PricingMode.METERED, input.mode());
        assertEquals(200_000L, input.rateMillicentsPerMillion());
        assertEquals(200_000L, input.costMillicents()); // 1M tokens at 200000/1M
        ChargeLine output = lines.stream().filter(l -> l.tokenType() == TokenType.OUTPUT).findFirst().orElseThrow();
        assertEquals(200_000L, output.costMillicents()); // 500k tokens at 400000/1M
    }

    /**
     * The partial case. A dimension the operator never priced must not silently cost zero, and must
     * not take the rest of the call down with it.
     */
    @Test
    void aDimensionWithNoRateIsUnknownWhileTheRestOfTheCallPrices() {
        metered("TEST-METERED-2", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L));

        List<ChargeLine> lines = registry.priceCall("TEST-METERED-2",
                new ModelUsage("TEST-METERED-2",
                        List.of(new TokenCount(TokenType.INPUT, 1_000_000),
                                new TokenCount(TokenType.CACHE_WRITE, 1_000_000)),
                        2_000_000, true));

        ChargeLine cacheWrite = lines.stream()
                .filter(l -> l.tokenType() == TokenType.CACHE_WRITE).findFirst().orElseThrow();
        assertEquals(PricingMode.UNKNOWN, cacheWrite.mode());
        assertNull(cacheWrite.costMillicents());
        assertNull(cacheWrite.rateMillicentsPerMillion());

        ChargeLine input = lines.stream()
                .filter(l -> l.tokenType() == TokenType.INPUT).findFirst().orElseThrow();
        assertEquals(PricingMode.METERED, input.mode());
        assertEquals(200_000L, input.costMillicents());
    }

    /**
     * The Anthropic shape, and the reason pricing must iterate {@code counts()} rather than any single
     * total. Anthropic reports cache reads and writes as buckets OUTSIDE its own token total, so pricing
     * anything against a total would bill those tokens at the wrong rate or not at all — and cached
     * tokens are the ones a cache exists to make cheap, so under-billing them is the expensive mistake.
     * Each bucket must be priced at its own rate.
     */
    @Test
    void everyBucketIsPricedAtItsOwnRateIncludingTheCacheBuckets() {
        metered("TEST-METERED-CACHE", Map.of(
                "INPUT", 300_000L, "OUTPUT", 600_000L, "CACHED_INPUT", 30_000L, "CACHE_WRITE", 375_000L));

        List<ChargeLine> lines = registry.priceCall("TEST-METERED-CACHE",
                new ModelUsage("TEST-METERED-CACHE",
                        List.of(new TokenCount(TokenType.INPUT, 1_000_000),
                                new TokenCount(TokenType.CACHED_INPUT, 1_000_000),
                                new TokenCount(TokenType.CACHE_WRITE, 1_000_000),
                                new TokenCount(TokenType.OUTPUT, 1_000_000)),
                        4_000_000, true));

        assertEquals(4, lines.size());
        assertEquals(30_000L, lines.stream().filter(l -> l.tokenType() == TokenType.CACHED_INPUT)
                .findFirst().orElseThrow().costMillicents(),
                "a cached-input token must cost its OWN rate, not the fresh-input rate");
        assertEquals(375_000L, lines.stream().filter(l -> l.tokenType() == TokenType.CACHE_WRITE)
                .findFirst().orElseThrow().costMillicents());
        // The call's cost is the sum of its lines — no total-based shortcut can produce this figure.
        assertEquals(1_305_000L, lines.stream().mapToLong(ChargeLine::costMillicents).sum());
    }

    /** Self-hosted inference: an ASSERTED zero, which must read differently from an absent price. */
    @Test
    void anUnmeteredModelChargesAnExplicitZero() {
        registry.create(new LlmModelInput("openai", "TEST-UNMETERED", "TEST self-hosted", "UNMETERED",
                Map.of(), null, null, null, Map.of(), true));

        List<ChargeLine> lines = registry.priceCall("TEST-UNMETERED",
                ModelUsage.of("TEST-UNMETERED", 1_000_000, 500_000));

        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(l -> l.mode() == PricingMode.UNMETERED));
        assertTrue(lines.stream().allMatch(l -> l.costMillicents() == 0L));
        assertTrue(lines.stream().allMatch(l -> l.rateMillicentsPerMillion() == 0L));
    }

    /**
     * An UNMETERED model's cost is an asserted zero whatever the split turns out to be — including when
     * there is no split at all. This must record as UNMETERED, not as unpriced: the catalog already
     * answered the question, so an unreconciled call must not downgrade that answer to UNKNOWN.
     */
    @Test
    void anUnmeteredModelsUnreconciledCallIsStillAnAssertedZero() {
        registry.create(new LlmModelInput("openai", "TEST-UNMETERED-UNRECONCILED", "TEST self-hosted",
                "UNMETERED", Map.of(), null, null, null, Map.of(), true));

        List<ChargeLine> lines = registry.priceCall("TEST-UNMETERED-UNRECONCILED",
                new ModelUsage("TEST-UNMETERED-UNRECONCILED",
                        List.of(new TokenCount(TokenType.TOTAL, 900)), 900, false));

        assertEquals(1, lines.size());
        assertEquals(TokenType.TOTAL, lines.get(0).tokenType());
        assertEquals(PricingMode.UNMETERED, lines.get(0).mode());
        assertEquals(0L, lines.get(0).costMillicents());
    }

    /**
     * The regression that motivated the change: an uncatalogued model used to be priced at 0, which
     * froze forever as "free". It must be UNKNOWN.
     */
    @Test
    void anUncataloguedModelIsUnknownAndNeverZero() {
        List<ChargeLine> lines = registry.priceCall("TEST-NOT-IN-CATALOG",
                ModelUsage.of("TEST-NOT-IN-CATALOG", 1_000, 500));

        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().allMatch(l -> l.mode() == PricingMode.UNKNOWN));
        assertTrue(lines.stream().allMatch(l -> l.costMillicents() == null));
    }

    /**
     * A non-null usage whose vendor reported no tokens at all (empty {@code counts}) — what
     * {@code TokenUsageMapper.map} returns for an all-zero token report. Must degrade to the same
     * unpriced TOTAL line as an unreconciled call, never vanish from the ledger with no trace.
     */
    @Test
    void aUsageWithNoCountsAtAllStillRecordsAnUnknownTotalLine() {
        List<ChargeLine> lines = registry.priceCall("TEST-EMPTY-COUNTS",
                new ModelUsage("TEST-EMPTY-COUNTS", List.of(), 0, true));

        assertEquals(1, lines.size());
        assertEquals(TokenType.TOTAL, lines.get(0).tokenType());
        assertEquals(PricingMode.UNKNOWN, lines.get(0).mode());
        assertNull(lines.get(0).costMillicents());
    }

    /** An unreconciled call has no split, so it cannot be metered even for a priced model. */
    @Test
    void anUnreconciledCallYieldsASingleUnknownTotalLine() {
        metered("TEST-METERED-3", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L));

        List<ChargeLine> lines = registry.priceCall("TEST-METERED-3",
                new ModelUsage("TEST-METERED-3", List.of(new TokenCount(TokenType.TOTAL, 900)), 900, false));

        assertEquals(1, lines.size());
        assertEquals(TokenType.TOTAL, lines.get(0).tokenType());
        assertEquals(PricingMode.UNKNOWN, lines.get(0).mode());
    }

    @Test
    void aMeteredModelWithoutInputOrOutputRatesIsRejectedOnSave() {
        assertThrows(IllegalArgumentException.class, () -> registry.create(new LlmModelInput(
                "openai", "TEST-NO-RATES", "TEST no rates", "METERED", Map.of("INPUT", 200_000L),
                null, null, null, Map.of(), true)));
    }

    @Test
    void aMeteredModelWithAZeroRateIsRejectedOnSave() {
        assertThrows(IllegalArgumentException.class, () -> registry.create(new LlmModelInput(
                "openai", "TEST-ZERO-RATE", "TEST zero", "METERED",
                Map.of("INPUT", 0L, "OUTPUT", 400_000L), null, null, null, Map.of(), true)));
    }

    @Test
    void isPriceableIsTrueForAMeteredModelWithBothMandatoryRatesAndForAnUnmeteredOne() {
        metered("TEST-PRICEABLE", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L));
        registry.create(new LlmModelInput("openai", "TEST-PRICEABLE-FREE", "TEST free", "UNMETERED",
                Map.of(), null, null, null, Map.of(), true));

        assertTrue(registry.isPriceable("TEST-PRICEABLE"));
        assertTrue(registry.isPriceable("TEST-PRICEABLE-FREE"));
        assertFalse(registry.isPriceable("TEST-STILL-NOT-IN-CATALOG"));
    }
}
