package dev.codespire.llmcatalog;

import java.time.LocalDate;
import java.util.List;

/** Synthetic catalog content for tests: TEST- names and rates 1, 2, 3 only. */
final class CatalogFixtures {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    static final String SOURCE = "https://example.invalid/pricing";

    private CatalogFixtures() {
    }

    static CatalogProvider provider(String code) {
        return new CatalogProvider(code, "TEST " + code, SOURCE);
    }

    static CatalogPricing pricing(Long input, Long output) {
        return new CatalogPricing(input, output, 3L, null, null, null, List.of(), SOURCE, TODAY);
    }

    static CatalogModel model(String modelId, String provider, CatalogPricing pricing, String... aliases) {
        return new CatalogModel(modelId, provider, "TEST " + modelId, List.of(aliases),
                new CatalogLifecycle(CatalogLifecycle.Status.ACTIVE, null, null, null), 1L, 1L,
                new CatalogCapabilities(true, true, false, true),
                new CatalogApiDialect(CatalogApiDialect.OutputTokenParam.MAX_TOKENS, true, null), pricing);
    }

    static CatalogModel model(String modelId) {
        return model(modelId, "openai", pricing(1L, 2L));
    }

    static CatalogDocument document(CatalogModel... models) {
        return new CatalogDocument(List.of(provider("openai"), provider("anthropic")), List.of(models));
    }
}
