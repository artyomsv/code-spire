package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.LlmModelInput;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.ModelRate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The manual-model reads on the real tables. Every row is TEST- and removed after each test. */
@QuarkusTest
class JdbcManualModelsTest {

    @Inject
    JdbcManualModels manualModels;

    @Inject
    LlmModelRegistry registry;

    @AfterEach
    void removeTheTestModels() {
        registry.list().stream()
                .filter(model -> model.id() != null && model.name().startsWith("TEST-jdbc-"))
                .forEach(model -> registry.delete(UUID.fromString(model.id())));
    }

    private void create(String name, boolean enabled) {
        registry.create(new LlmModelInput("openai", name, "TEST " + name, "METERED",
                Map.of("INPUT", 1L, "OUTPUT", 2L), null, null, null, null, enabled, List.of("CACHE_WRITE")));
    }

    @Test
    void theEarliestAskedNameWins() throws SQLException {
        create("TEST-jdbc-id", true);
        create("TEST-jdbc-alias", false);

        assertEquals("TEST-jdbc-alias",
                manualModels.byAnyName(List.of("TEST-jdbc-alias", "TEST-jdbc-id")).orElseThrow().name());
        assertEquals("TEST-jdbc-id",
                manualModels.byAnyName(List.of("TEST-jdbc-missing", "TEST-jdbc-id", "TEST-jdbc-alias"))
                        .orElseThrow().name());
        assertTrue(manualModels.byAnyName(List.of("TEST-jdbc-missing")).isEmpty());
    }

    @Test
    void aManualRowCarriesItsRatesAndSwitch() throws SQLException {
        create("TEST-jdbc-off", false);

        ResolvedModel model = manualModels.byAnyName(List.of("TEST-jdbc-off")).orElseThrow();

        assertEquals(ModelSource.MANUAL, model.source());
        assertFalse(model.enabled());
        assertEquals(ModelRate.rated(1), model.rates().get(TokenType.INPUT));
        assertEquals(ModelRate.notBilled(), model.rates().get(TokenType.CACHE_WRITE));
        assertTrue(manualModels.allNames().contains("TEST-jdbc-off"));
    }
}
