package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.ModelRate;
import dev.codespire.orchestrator.llm.PricingMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles.FakeManualModels;
import static dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles.FakeStates;
import static dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles.catalog;
import static dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles.manual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The resolver on hand-built collaborators: load, fallback, shadowing and the fault rule. */
class LlmModelCatalogTest {

    private static final String REMOTE = "https://example.invalid/catalog.yaml";
    private static final String STORED_DOCUMENT = """
            providers:
              - code: openai
                displayName: TEST openai
            models:
              - modelId: TEST-stored
                provider: openai
                displayName: TEST stored
                lifecycle:
                  status: ACTIVE
                api:
                  outputTokenParam: MAX_TOKENS
                  temperature: true
                pricing:
                  input: 1
                  output: 2
                  sourceUrl: https://example.invalid/pricing
                  observedAt: 2026-11-20
            """;

    private final FakeManualModels manualModels = new FakeManualModels();

    private ResolvedModel resolved(LlmModelCatalog catalog, String name) {
        return catalog.resolve(name).orElseThrow(() -> new AssertionError(name + " did not resolve"));
    }

    @Test
    void urlNoneUsesTheBundledCopyEvenWhenTheRowHoldsADocument() {
        FakeStates states = new FakeStates().holding(STORED_DOCUMENT);
        LlmModelCatalog catalog = catalog(manualModels, states, "none");

        assertTrue(catalog.resolve("TEST-cat-o").isPresent());
        assertEquals(Optional.empty(), catalog.resolve("TEST-stored"));
        assertEquals(0, states.reads.get(), "url none must not read the row");
    }

    @Test
    void theLazyLoadReadsTheRowOnlyOnce() {
        FakeStates states = new FakeStates().holding(STORED_DOCUMENT);
        LlmModelCatalog catalog = catalog(manualModels, states, REMOTE);

        catalog.resolve("TEST-stored");
        catalog.resolve("TEST-stored");
        catalog.snapshot();

        assertEquals(1, states.reads.get());
    }

    @Test
    void aCatalogModelResolvesByModelIdAndByAlias() {
        LlmModelCatalog catalog = catalog(manualModels, new FakeStates(), "none");

        ResolvedModel byId = resolved(catalog, "TEST-cat-a");
        ResolvedModel byAlias = resolved(catalog, "TEST-cat-a-dated");

        assertEquals(ModelSource.CATALOG, byId.source());
        assertEquals("anthropic", byId.type());
        assertEquals(PricingMode.METERED, byId.pricingMode());
        assertEquals(ModelRate.rated(1), byId.rates().get(TokenType.INPUT));
        assertEquals(ModelRate.rated(2), byId.rates().get(TokenType.OUTPUT));
        assertEquals(ModelRate.rated(3), byId.rates().get(TokenType.CACHED_INPUT));
        assertEquals("TEST-cat-a", byAlias.catalogEntry().modelId());
    }

    @Test
    void notBilledAndMissingRatesKeepTheirMeaning() {
        ResolvedModel openai = resolved(catalog(manualModels, new FakeStates(), "none"), "TEST-cat-o");

        assertEquals(ModelRate.notBilled(), openai.rates().get(TokenType.CACHE_WRITE));
        assertEquals(ModelRate.rated(2), openai.rates().get(TokenType.REASONING));
        assertEquals(4 + 1, openai.rates().size());
        assertFalse(resolved(catalog(manualModels, new FakeStates(), "none"), "TEST-cat-a").rates()
                .containsKey(TokenType.REASONING), "a type with no rate and no mark stays unpriced");
    }

    @Test
    void aManualModelWinsOverTheCatalogModelOfTheSameName() {
        manualModels.with(manual("TEST-cat-o", "openai", true));

        ResolvedModel model = resolved(catalog(manualModels, new FakeStates(), "none"), "TEST-cat-o");

        assertEquals(ModelSource.MANUAL, model.source());
        assertEquals(ModelRate.rated(5), model.rates().get(TokenType.INPUT));
        assertNull(model.catalogEntry());
    }

    @Test
    void aSwitchedOffManualModelStillShadows() {
        manualModels.with(manual("TEST-cat-o", "openai", false));

        ResolvedModel model = resolved(catalog(manualModels, new FakeStates(), "none"), "TEST-cat-o");

        assertEquals(ModelSource.MANUAL, model.source());
        assertFalse(model.enabled());
    }

    @Test
    void aManualModelNamedLikeAnAliasShadowsTheWholeEntry() {
        manualModels.with(manual("TEST-cat-a-dated", "anthropic", true));
        LlmModelCatalog catalog = catalog(manualModels, new FakeStates(), "none");

        assertEquals(ModelSource.MANUAL, resolved(catalog, "TEST-cat-a").source());
        assertEquals(ModelSource.MANUAL, resolved(catalog, "TEST-cat-a-dated").source());
    }

    @Test
    void theAskedNameWinsWhenTwoManualModelsShadowOneEntry() {
        manualModels.with(manual("TEST-cat-a-dated", "anthropic", true)).with(manual("TEST-cat-a", "anthropic", false));
        LlmModelCatalog catalog = catalog(manualModels, new FakeStates(), "none");

        assertEquals("TEST-cat-a-dated", resolved(catalog, "TEST-cat-a-dated").name());
        assertEquals("TEST-cat-a", resolved(catalog, "TEST-cat-a").name());
    }

    @Test
    void aDatabaseFaultNeverFallsThroughToTheCatalog() {
        manualModels.failing = true;
        LlmModelCatalog catalog = catalog(manualModels, new FakeStates(), "none");

        assertThrows(LlmModelCatalog.CatalogueReadFailed.class, () -> catalog.resolve("TEST-cat-o"));
        assertThrows(LlmModelCatalog.CatalogueReadFailed.class, catalog::effective);
        assertThrows(LlmModelCatalog.CatalogueReadFailed.class, catalog::catalogRows);
    }

    @Test
    void aRetiredModelIsDisabledButPriced() {
        ResolvedModel retired = resolved(catalog(manualModels, new FakeStates(), "none"), "TEST-cat-retired");

        assertFalse(retired.enabled());
        assertTrue(retired.rates().containsKey(TokenType.INPUT));
        assertTrue(retired.rates().containsKey(TokenType.OUTPUT));
    }

    @Test
    void effectiveListsEachNameOnce() {
        manualModels.with(manual("TEST-cat-o", "openai", true)).with(manual("TEST-manual-only", "openai", true));
        LlmModelCatalog catalog = catalog(manualModels, new FakeStates(), "none");

        List<ResolvedModel> effective = catalog.effective();

        assertEquals(List.of("TEST-cat-a", "TEST-cat-retired", "TEST-cat-o", "TEST-manual-only"),
                effective.stream().map(ResolvedModel::name).toList());
        assertEquals(ModelSource.MANUAL, effective.get(2).source());
        LlmModelCatalog.CatalogRow shadowed = catalog.catalogRows().stream()
                .filter(row -> row.model().modelId().equals("TEST-cat-o")).findFirst().orElseThrow();
        assertTrue(shadowed.shadowed());
        assertTrue(catalog.catalogRows().stream().filter(row -> !row.model().modelId().equals("TEST-cat-o"))
                .noneMatch(LlmModelCatalog.CatalogRow::shadowed));
    }

    @Test
    void theFirstCallLoadsTheStoredDocumentElseTheBundledOne() {
        LlmModelCatalog empty = catalog(manualModels, new FakeStates(), REMOTE);
        assertTrue(empty.resolve("TEST-cat-o").isPresent(), "an empty row falls back to the bundled copy");

        LlmModelCatalog stored = catalog(manualModels, new FakeStates().holding(STORED_DOCUMENT), REMOTE);
        assertTrue(stored.resolve("TEST-stored").isPresent());
        assertEquals(Optional.empty(), stored.resolve("TEST-cat-o"));
        assertEquals(CatalogSnapshot.sha256Of(STORED_DOCUMENT), stored.snapshot().sha256());
    }

    @Test
    void aStoredDocumentThatNoLongerParsesFallsBackToTheBundledCopy() {
        FakeStates corrupt = new FakeStates().holding("models: [ TEST-not-a-model");
        LlmModelCatalog catalog = catalog(manualModels, corrupt, REMOTE);

        assertTrue(catalog.resolve("TEST-cat-o").isPresent());
        assertEquals(CatalogSnapshot.sha256Of(CatalogTestDoubles.testCatalogText()), catalog.snapshot().sha256());
    }

    @Test
    void anUnreadableRowIsAFaultAndIsNotCached() {
        FakeStates broken = new FakeStates() {
            @Override
            public LlmCatalogState read() {
                reads.incrementAndGet();
                throw new IllegalStateException("TEST database fault");
            }
        };
        LlmModelCatalog catalog = catalog(manualModels, broken, REMOTE);

        assertThrows(LlmModelCatalog.CatalogueReadFailed.class, () -> catalog.resolve("TEST-cat-o"));
        assertThrows(LlmModelCatalog.CatalogueReadFailed.class, () -> catalog.resolve("TEST-cat-o"));
        assertEquals(2, broken.reads.get());
    }

    @Test
    void replaceSwapsTheWholeCatalog() throws Exception {
        LlmModelCatalog catalog = catalog(manualModels, new FakeStates(), "none");
        catalog.resolve("TEST-cat-o");

        catalog.replace(LlmModelCatalog.parse(STORED_DOCUMENT));

        assertTrue(catalog.resolve("TEST-stored").isPresent());
        assertEquals(Optional.empty(), catalog.resolve("TEST-cat-o"));
    }
}
