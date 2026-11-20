package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmModelPricer}'s lookup can fail below the "model not catalogued" case: a database fault while
 * reading the manual models. This wires a real pricer to a hand-built resolver whose manual reads throw
 * and asserts the SAME UNKNOWN outcome, never a coerced zero, a stray UNMETERED, or the catalog's rate
 * for a name a manual model might be shadowing. Collaborators are field-injected, so the fake is set
 * directly.
 */
class LlmModelPricerFailureTest {

    private static LlmModelPricer pricerOverAFailingCatalog() {
        LlmModelPricer pricer = new LlmModelPricer();
        pricer.catalog = CatalogTestDoubles.failingCatalog();
        return pricer;
    }

    private static void assertAllUnknown(List<ChargeLine> lines) {
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().allMatch(l -> l.mode() == PricingMode.UNKNOWN), lines.toString());
        assertTrue(lines.stream().allMatch(l -> l.costMillicents() == null), lines.toString());
    }

    @Test
    void aDatabaseFaultDuringPricingLookupIsUnknownNeverZero() {
        LlmModelPricer pricer = pricerOverAFailingCatalog();

        assertAllUnknown(pricer.priceCall("TEST-ANY-MODEL", ModelUsage.of("TEST-ANY-MODEL", 1_000, 500)));
    }

    @Test
    void aDatabaseFaultDuringPricingLookupMakesAModelUnpriceable() {
        LlmModelPricer pricer = pricerOverAFailingCatalog();

        assertFalse(pricer.isPriceable("TEST-ANY-MODEL"));
    }

    /**
     * The name the catalog prices (TEST-cat-o, rates 1 and 2) is the discriminating case: a pricer or
     * a resolver that fell through to the catalog on a fault would price these lines.
     */
    @Test
    void aPricingReadFaultIsUnknownNotTheCatalogRate() {
        LlmModelPricer pricer = pricerOverAFailingCatalog();

        assertAllUnknown(pricer.priceCall("TEST-cat-o", ModelUsage.of("TEST-cat-o", 1_000_000, 1_000_000)));
        assertFalse(pricer.isPriceable("TEST-cat-o"));
        assertFalse(pricer.isPriceable("TEST-cat-o", "codex"));
    }

    @Test
    void withoutAFaultTheSameNameIsPricedFromTheCatalog() {
        LlmModelPricer pricer = new LlmModelPricer();
        pricer.catalog = CatalogTestDoubles.testCatalog();

        List<ChargeLine> lines = pricer.priceCall("TEST-cat-o", ModelUsage.of("TEST-cat-o", 1_000_000, 1_000_000));

        assertTrue(lines.stream().allMatch(l -> l.mode() == PricingMode.METERED), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> Long.valueOf(1).equals(l.costMillicents())), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> Long.valueOf(2).equals(l.costMillicents())), lines.toString());
    }
}
