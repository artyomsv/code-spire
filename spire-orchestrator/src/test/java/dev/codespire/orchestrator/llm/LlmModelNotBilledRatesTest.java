package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenCount;
import dev.codespire.contract.review.TokenType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "The vendor does not bill this token type", as a fact an operator states (M3.5 part D, decision 4B).
 *
 * <p>This is the third state, and the tests below exist to keep it apart from the other two. A rate is
 * a price somebody read off a vendor's page. An assertion is a person saying nothing is charged. An
 * absent entry is nobody having said, and a call reporting it stays UNPRICED — which is what stopped
 * work item 36 on 2026-09-15, after it had already spent.
 *
 * <p>Rates below are obviously-synthetic round numbers, not any vendor's real published price.
 */
@QuarkusTest
class LlmModelNotBilledRatesTest {

    @Inject LlmModelRegistry registry;
    @Inject LlmModelPricer pricer;

    private String name() { return "TEST-NOT-BILLED-" + UUID.randomUUID().toString().substring(0, 8); }

    private LlmModelView model(String name, Map<String, Long> rates, List<String> notBilled) {
        return registry.create(new LlmModelInput("openai", name, "TEST " + name, "METERED", rates,
                null, null, null, Map.of(), true, notBilled));
    }

    @Test
    void anAssertionRoundTripsAsAnAssertionRatherThanAsAZeroRate() {
        String model = name();
        LlmModelView created = model(model, Map.of("INPUT", 200_000L, "OUTPUT", 400_000L), List.of("CACHED_INPUT"));
        assertEquals(List.of("CACHED_INPUT"), created.notBilled());
        assertFalse(created.rates().containsKey("CACHED_INPUT"), "an assertion is not a rate of zero");

        LlmModelView read = registry.list().stream().filter(row -> row.name().equals(model)).findFirst().orElseThrow();
        assertEquals(List.of("CACHED_INPUT"), read.notBilled());
        assertEquals(Map.of("INPUT", 200_000L, "OUTPUT", 400_000L), read.rates());
    }

    /**
     * The discriminating case for the repository read. {@code getLong} answers 0 for SQL NULL, so an
     * assertion read without {@code wasNull} arrives as a zero PRICE — a metered line at rate 0, which
     * is a fabricated price rather than a stated one. The line's own mode is what tells them apart.
     */
    @Test
    void anAssertedTypeChargesAnAssertedZeroAndNotAMeteredZero() {
        String model = name();
        model(model, Map.of("INPUT", 200_000L, "OUTPUT", 400_000L), List.of("CACHED_INPUT"));

        ChargeLine cached = registry.priceCall(model, new ModelUsage(model,
                        List.of(new TokenCount(TokenType.INPUT, 1_000_000), new TokenCount(TokenType.CACHED_INPUT, 500_000)),
                        1_500_000, true)).stream()
                .filter(line -> line.tokenType() == TokenType.CACHED_INPUT).findFirst().orElseThrow();

        assertEquals(PricingMode.UNMETERED, cached.mode());
        assertEquals(0L, cached.costMillicents());
        assertEquals(500_000, cached.tokens());
        assertEquals(0L, cached.rateMillicentsPerMillion(), "the ledger's own check requires a zero rate here");
    }

    /** Nobody said anything about this type, so the call cannot be priced. Never a zero. */
    @Test
    void aTypeWithNeitherARateNorAnAssertionStaysUnpriced() {
        String model = name();
        model(model, Map.of("INPUT", 200_000L, "OUTPUT", 400_000L), List.of());

        ChargeLine reasoning = registry.priceCall(model, new ModelUsage(model,
                        List.of(new TokenCount(TokenType.INPUT, 1_000), new TokenCount(TokenType.REASONING, 10)),
                        1_010, true)).stream()
                .filter(line -> line.tokenType() == TokenType.REASONING).findFirst().orElseThrow();

        assertEquals(PricingMode.UNKNOWN, reasoning.mode());
        assertNull(reasoning.costMillicents());
    }

    @Test
    void oneTypeCannotCarryBothARateAndAnAssertion() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> model(name(), Map.of("INPUT", 200_000L, "OUTPUT", 400_000L, "CACHED_INPUT", 100_000L), List.of("CACHED_INPUT")))
                .getMessage().contains("both a rate and a"));
    }

    /** A model that charges for neither is UNMETERED, which says that about the whole model. */
    @Test
    void theTwoTypesEveryVendorChargesForCannotBeAsserted() {
        for (String required : List.of("INPUT", "OUTPUT")) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> model(name(), Map.of("INPUT", 200_000L, "OUTPUT", 400_000L), List.of(required)))
                    .getMessage().contains("cannot be asserted as unbilled"));
        }
    }

    @Test
    void anUnmeteredModelCarriesNoAssertionsBecauseItAlreadyAssertsTheWholeModel() {
        assertThrows(IllegalArgumentException.class, () -> registry.create(new LlmModelInput("openai", name(),
                "TEST unmetered", "UNMETERED", Map.of(), null, null, null, Map.of(), true, List.of("CACHED_INPUT"))));
    }

    /**
     * The completeness question a harness run asks: every type codex can report is priced or asserted.
     * The old question — INPUT and OUTPUT only — is what let an item start and then stop mid-flight.
     */
    @Test
    void aHarnessRunNeedsEveryTypeThatHarnessCanReport() {
        String partial = name();
        model(partial, Map.of("INPUT", 200_000L, "OUTPUT", 400_000L), List.of());
        assertTrue(pricer.isPriceable(partial), "the review question only asks about INPUT and OUTPUT");
        assertFalse(pricer.isPriceable(partial, "codex"));
        assertEquals(List.of(TokenType.CACHED_INPUT, TokenType.CACHE_WRITE, TokenType.REASONING),
                pricer.unpricedTypes(partial, "codex"));

        String complete = name();
        model(complete, Map.of("INPUT", 200_000L, "OUTPUT", 400_000L, "CACHED_INPUT", 50_000L),
                List.of("CACHE_WRITE", "REASONING"));
        assertTrue(pricer.isPriceable(complete, "codex"));
        assertEquals(List.of(), pricer.unpricedTypes(complete, "codex"));
    }

    /** A harness this build does not know is assumed to report everything; guessing low spends money. */
    @Test
    void anUnknownHarnessDemandsEveryPriceableType() {
        String model = name();
        model(model, Map.of("INPUT", 200_000L, "OUTPUT", 400_000L, "CACHED_INPUT", 50_000L),
                List.of("CACHE_WRITE", "REASONING"));
        assertTrue(pricer.isPriceable(model, "TEST-unknown-harness"));

        String partial = name();
        model(partial, Map.of("INPUT", 200_000L, "OUTPUT", 400_000L), List.of());
        assertEquals(List.of(TokenType.CACHED_INPUT, TokenType.CACHE_WRITE, TokenType.REASONING),
                pricer.unpricedTypes(partial, "TEST-unknown-harness"));
    }

    /** An UNMETERED model is priced for every harness: its zero is asserted for the whole model. */
    @Test
    void anUnmeteredModelIsCompleteForAnyHarness() {
        String model = name();
        registry.create(new LlmModelInput("openai", model, "TEST unmetered", "UNMETERED", Map.of(),
                null, null, null, Map.of(), true, List.of()));
        assertTrue(pricer.isPriceable(model, "codex"));
        assertEquals(List.of(), pricer.unpricedTypes(model, "codex"));
    }

    /** A model the catalogue does not have cannot be priced for any harness, and says every type. */
    @Test
    void anUncataloguedModelIsMissingEveryType() {
        assertEquals(5, pricer.unpricedTypes("TEST-model-nobody-registered", "codex").size());
        assertFalse(pricer.isPriceable("TEST-model-nobody-registered", "codex"));
    }
}
