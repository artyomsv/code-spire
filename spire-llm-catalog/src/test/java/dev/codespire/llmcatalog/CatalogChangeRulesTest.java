package dev.codespire.llmcatalog;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static dev.codespire.llmcatalog.CatalogFixtures.SOURCE;
import static dev.codespire.llmcatalog.CatalogFixtures.TODAY;
import static dev.codespire.llmcatalog.CatalogFixtures.document;
import static dev.codespire.llmcatalog.CatalogFixtures.model;
import static dev.codespire.llmcatalog.CatalogFixtures.pricing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogChangeRulesTest {

    private static CatalogModel retired(CatalogModel model) {
        return new CatalogModel(model.modelId(), model.provider(), model.displayName(), model.aliases(),
                new CatalogLifecycle(CatalogLifecycle.Status.RETIRED, null, null, null), model.contextWindow(),
                model.maxOutputTokens(), model.capabilities(), model.api(), model.pricing());
    }

    private static CatalogModel priced(String modelId, long output, LocalDate observedAt) {
        return model(modelId, "openai",
                new CatalogPricing(1L, output, null, null, null, null, List.of(), SOURCE, observedAt));
    }

    @Test
    void removingAModelIsAProblem() {
        List<String> problems = CatalogChangeRules.problems(document(model("TEST-a"), model("TEST-b")),
                document(model("TEST-a")));

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("TEST-b"), problems.getFirst());
    }

    @Test
    void retiringAModelIsFine() {
        assertEquals(List.of(), CatalogChangeRules.problems(document(model("TEST-a")),
                document(retired(model("TEST-a")))));
    }

    @Test
    void aPriceChangeWithTheSameObservedAtIsAProblem() {
        List<String> problems = CatalogChangeRules.problems(document(priced("TEST-a", 2L, TODAY.minusDays(1))),
                document(priced("TEST-a", 3L, TODAY.minusDays(1))));

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("observedAt"), problems.getFirst());
    }

    @Test
    void aNotBilledChangeWithTheSameObservedAtIsAProblem() {
        CatalogPricing before = new CatalogPricing(1L, 2L, null, null, null, null, List.of(), SOURCE, TODAY);
        CatalogPricing after = new CatalogPricing(1L, 2L, null, null, null, null, List.of("CACHE_WRITE"), SOURCE,
                TODAY);

        assertEquals(1, CatalogChangeRules.problems(document(model("TEST-a", "openai", before)),
                document(model("TEST-a", "openai", after))).size());
    }

    @Test
    void aPriceChangeWithANewObservedAtIsFine() {
        assertEquals(List.of(), CatalogChangeRules.problems(document(priced("TEST-a", 2L, TODAY.minusDays(1))),
                document(priced("TEST-a", 3L, TODAY))));
    }

    @Test
    void anUnchangedPriceKeepsItsObservedAt() {
        assertEquals(List.of(), CatalogChangeRules.problems(document(model("TEST-a", "openai", pricing(1L, 2L))),
                document(model("TEST-a", "openai", pricing(1L, 2L)))));
    }

    @Test
    void anEmptyBaseIsFine() {
        assertEquals(List.of(), CatalogChangeRules.problems(document(), document(model("TEST-a"))));
    }
}
