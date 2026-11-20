package dev.codespire.orchestrator.llm;

import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.review.ModelUsage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pricer and the registry read through the resolver: catalog models (the %test TEST- catalog) are
 * priced and listed, and a manual model of the same name wins. Every manual row and provider is TEST-
 * and removed after each test.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class CatalogReadersTest {

    private static final String PROVIDER = "TEST-catalog-readers-provider";

    @Inject
    LlmModelPricer pricer;

    @Inject
    LlmModelRegistry registry;

    @Inject
    LlmProviderRegistry providers;

    @AfterEach
    void removeTestRows() {
        providers.list().stream().filter(p -> p.name().equals(PROVIDER))
                .forEach(p -> providers.delete(UUID.fromString(p.id())));
        registry.list().stream()
                .filter(m -> m.id() != null && (m.name().startsWith("TEST-cat-") || m.name().startsWith("TEST-rd-")))
                .forEach(m -> registry.delete(UUID.fromString(m.id())));
    }

    private String createManual(String name, String type) {
        return registry.create(new LlmModelInput(type, name, "TEST manual " + name, "METERED",
                Map.of("INPUT", 5L, "OUTPUT", 6L), null, null, null, Map.of(), true, List.of())).id();
    }

    private void provideWith(String model) {
        providers.create(new LlmProviderInput(PROVIDER, "openai", "http://example.invalid", "sk-test", 0.2,
                null, true, false, null));
    }

    private static List<Long> costs(List<ChargeLine> lines) {
        return lines.stream().map(ChargeLine::costMillicents).toList();
    }

    @Test
    void aCatalogModelIsPricedFromTheCatalog() {
        List<ChargeLine> lines = pricer.priceCall("TEST-cat-o", ModelUsage.of("TEST-cat-o", 1_000_000, 1_000_000));

        assertEquals(List.of(1L, 2L), costs(lines));
        assertTrue(pricer.isPriceable("TEST-cat-o", "codex"), "rates for all five types, CACHE_WRITE not billed");
    }

    @Test
    void aShadowedModelIsPricedFromTheManualRow() {
        createManual("TEST-cat-o", "openai");

        List<ChargeLine> lines = pricer.priceCall("TEST-cat-o", ModelUsage.of("TEST-cat-o", 1_000_000, 1_000_000));

        assertEquals(List.of(5L, 6L), costs(lines));
    }

    @Test
    void aRetiredCatalogModelIsDisabledButPriced() {
        assertTrue(registry.isDisabled("TEST-cat-retired"));
        assertFalse(registry.isDisabled("TEST-cat-a"));
        assertTrue(pricer.isPriceable("TEST-cat-retired"));
    }

    @Test
    void listReturnsEachNameOnceWithItsSource() {
        createManual("TEST-cat-o", "openai");

        List<LlmModelView> list = registry.list();

        List<LlmModelView> named = list.stream().filter(m -> m.name().equals("TEST-cat-o")).toList();
        assertEquals(1, named.size(), "a shadowed catalog entry is not listed beside its manual model");
        assertEquals("MANUAL", named.getFirst().source());
        LlmModelView catalogRow = list.stream().filter(m -> m.name().equals("TEST-cat-a")).findFirst().orElseThrow();
        assertNull(catalogRow.id());
        assertNull(catalogRow.createdAt());
        assertEquals("CATALOG", catalogRow.source());
        assertEquals("anthropic", catalogRow.type());
        assertEquals(Map.of("INPUT", 1L, "CACHED_INPUT", 3L, "CACHE_WRITE", 3L, "OUTPUT", 2L), catalogRow.rates());
    }

    @Test
    void theProfileOfACatalogModelComesFromItsApiDialect() {
        ModelParamProfile profile = registry.profileForName("TEST-cat-o").orElseThrow();

        assertEquals("MAX_COMPLETION_TOKENS", profile.outputTokenParam().name());
        assertFalse(profile.supportsTemperature());
        assertEquals("low", profile.reasoningEffort());
        assertEquals(Map.of(), profile.extraParams());
    }

    @Test
    void useCatalogDeletesAnInUseManualModelThatShadowsAPricedCatalogEntry() {
        String id = createManual("TEST-cat-o", "openai");
        provideWith("TEST-cat-o");

        assertTrue(registry.delete(UUID.fromString(id)));

        assertEquals("CATALOG", registry.list().stream().filter(m -> m.name().equals("TEST-cat-o"))
                .findFirst().orElseThrow().source(), "the provider's model still resolves, now to the catalog");
    }

    @Test
    void deleteStillRefusesAnInUseManualModelWithNoCatalogEntry() {
        String id = createManual("TEST-rd-manual-only", "openai");
        provideWith("TEST-rd-manual-only");

        assertThrows(ModelInUseException.class, () -> registry.delete(UUID.fromString(id)));
    }

    @Test
    void deleteStillRefusesAnInUseManualModelShadowingARetiredEntry() {
        String id = createManual("TEST-cat-retired", "anthropic");
        provideWith("TEST-cat-retired");

        assertThrows(ModelInUseException.class, () -> registry.delete(UUID.fromString(id)));
    }
}
