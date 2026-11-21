package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The REST layer's guard (LlmProviderModelGuardTest) is enforced at the one caller that happens to exist, not at the
 * invariant's real boundary. This asserts the default-model writes themselves refuse a model they cannot price — the
 * check a seeder, an import, or a test reaching {@link LlmDefaultModel} directly cannot route around.
 */
@QuarkusTest
class LlmProviderRegistryPricingGuardTest {

    @Inject LlmProviderRegistry providers;
    @Inject LlmDefaultModel defaultModels;
    @Inject DataSource dataSource;

    private UUID provider;
    private String rateless;

    @BeforeEach
    void aProviderAndAModelWithNoRates() throws SQLException {
        provider = UUID.fromString(providers.create(new LlmProviderInput("TEST-pricing-guard-" + UUID.randomUUID(),
                "openai", "http://localhost", "TEST-key", 0.2, null, true, false, null)).id());
        rateless = "TEST-pricing-guard-rateless-" + UUID.randomUUID().toString().substring(0, 8);
        // As V30 left legacy rows: known, of the provider's vendor, with no rates.
        execute("INSERT INTO llm_model (id, type, name, label, pricing_mode) VALUES (?, 'openai', ?, ?, 'METERED')",
                UUID.randomUUID(), rateless, rateless);
    }

    @AfterEach
    void removeFixture() throws SQLException {
        providers.delete(provider);
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        execute("DELETE FROM llm_model WHERE name=?", rateless);
    }

    @Test
    void makingAProviderTheDefaultWithAnUnpriceableModelIsRefused() {
        LlmDefaultModel.ChoiceRefused refused = assertThrows(LlmDefaultModel.ChoiceRefused.class,
                () -> defaultModels.setDefaultProvider(provider, rateless));

        assertEquals("model_not_priceable", refused.reason());
        assertTrue(refused.getMessage().contains("Settings → LLM → Models"),
                "the refusal must name the fix, not merely refuse: " + refused.getMessage());
    }

    @Test
    void settingAnUnpriceableDefaultModelIsRefused() throws SQLException {
        // The default by SQL: through setDefaultProvider it would need a model, the very thing under test.
        execute("UPDATE llm_provider SET is_default = FALSE WHERE is_default");
        execute("UPDATE llm_provider SET is_default = TRUE WHERE id = ?", provider);

        LlmDefaultModel.ChoiceRefused refused = assertThrows(LlmDefaultModel.ChoiceRefused.class,
                () -> defaultModels.set(rateless));

        assertEquals("model_not_priceable", refused.reason());
    }

    @Test
    void anUncataloguedModelIsRefusedAsUnknown() {
        LlmDefaultModel.ChoiceRefused refused = assertThrows(LlmDefaultModel.ChoiceRefused.class,
                () -> defaultModels.setDefaultProvider(provider, "TEST-pricing-guard-uncatalogued"));

        assertEquals("unknown_model", refused.reason());
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setObject(i + 1, parameters[i]);
            }
            ps.executeUpdate();
        }
    }
}
