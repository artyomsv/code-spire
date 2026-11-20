package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default provider and the default review model change together: both are written, or neither, so no review sees
 * a provider of one vendor with a model of another. Driven through {@link LlmDefaultModel} and
 * {@link LlmProviderRegistry}, the boundary every caller (the REST layer, a test, an import) goes through.
 */
@QuarkusTest
class LlmDefaultModelTest {

    @Inject LlmDefaultModel defaultModels;
    @Inject LlmProviderRegistry providers;
    @Inject LlmModelRegistry models;
    @Inject DataSource dataSource;

    private String openaiModel;
    private String anthropicModel;

    /**
     * Only this suite's TEST-ldm- rows are touched, by SQL: deleting every provider through the registry fails the whole
     * class with ProviderInUse when another suite leaves a repository naming one. Each test that needs a default moves it
     * to its own provider.
     */
    @BeforeEach
    void noSetting() throws SQLException {
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        openaiModel = catalogued("openai", "TEST-ldm-openai-" + suffix);
        anthropicModel = catalogued("anthropic", "TEST-ldm-anthropic-" + suffix);
    }

    @AfterEach
    void removeFixture() throws SQLException {
        execute("DROP TRIGGER IF EXISTS test_ldm_explode_write ON app_setting");
        execute("DROP TRIGGER IF EXISTS test_ldm_explode_delete ON app_setting");
        execute("DROP FUNCTION IF EXISTS test_ldm_explode()");
        execute("DELETE FROM llm_provider WHERE name LIKE ?", "TEST-ldm-%");
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        execute("DELETE FROM llm_model WHERE name LIKE ?", "TEST-ldm-%");
    }

    @Test
    void settingTheDefaultProviderWritesProviderAndModelTogether() throws SQLException {
        UUID first = provider("TEST-ldm-first", "openai");
        UUID second = provider("TEST-ldm-second", "openai");

        defaultModels.setDefaultProvider(second, openaiModel);

        assertTrue(providers.get(second).orElseThrow().isDefault());
        assertFalse(providers.get(first).orElseThrow().isDefault());
        assertEquals(Optional.of(openaiModel), setting());
    }

    @Test
    void aCrossVendorDefaultChangeWithAMatchingModelSucceeds() throws SQLException {
        UUID openai = provider("TEST-ldm-openai", "openai");
        defaultModels.setDefaultProvider(openai, openaiModel);
        UUID anthropic = provider("TEST-ldm-anthropic", "anthropic");

        defaultModels.setDefaultProvider(anthropic, anthropicModel);

        assertTrue(providers.get(anthropic).orElseThrow().isDefault());
        assertEquals(Optional.of(anthropicModel), setting());
    }

    /** The vendor check: the model is known, enabled and priced, so only its type can refuse it. */
    @Test
    void aMismatchedModelChangesNothing() throws SQLException {
        UUID openai = provider("TEST-ldm-openai", "openai");
        defaultModels.setDefaultProvider(openai, openaiModel);
        UUID anthropic = provider("TEST-ldm-anthropic", "anthropic");

        LlmDefaultModel.ChoiceRefused refused = assertThrows(LlmDefaultModel.ChoiceRefused.class,
                () -> defaultModels.setDefaultProvider(anthropic, openaiModel));

        assertEquals("model_not_for_provider", refused.reason());
        assertTrue(refused.getMessage().contains("openai") && refused.getMessage().contains("anthropic"),
                "the refusal names both types: " + refused.getMessage());
        assertTrue(providers.get(openai).orElseThrow().isDefault(), "the default provider is unchanged");
        assertFalse(providers.get(anthropic).orElseThrow().isDefault());
        assertEquals(Optional.of(openaiModel), setting(), "the default model is unchanged");
    }

    @Test
    void deletingTheDefaultProviderClearsTheSetting() throws SQLException {
        UUID openai = provider("TEST-ldm-openai", "openai");
        defaultModels.setDefaultProvider(openai, openaiModel);

        assertTrue(providers.delete(openai));

        assertTrue(setting().isEmpty(), "the model chosen for the deleted default must not outlive it");
    }

    @Test
    void deletingAnotherProviderKeepsTheSetting() throws SQLException {
        UUID openai = provider("TEST-ldm-openai", "openai");
        defaultModels.setDefaultProvider(openai, openaiModel);
        UUID other = provider("TEST-ldm-other", "openai");

        assertTrue(providers.delete(other));

        assertEquals(Optional.of(openaiModel), setting());
    }

    @Test
    void aDisabledDefaultProviderCountsAsNone() {
        UUID openai = provider("TEST-ldm-openai", "openai");
        defaultModels.setDefaultProvider(openai, openaiModel);
        providers.update(openai, new LlmProviderInput("TEST-ldm-openai", "openai", "https://llm.example.invalid",
                null, null, null, false, null, null));

        assertThrows(LlmDefaultModel.NoDefaultProvider.class, () -> defaultModels.set(openaiModel));
    }

    @Test
    void createWithIsDefaultAndAModelWritesBoth() throws SQLException {
        UUID openai = provider("TEST-ldm-openai", "openai");
        defaultModels.setDefaultProvider(openai, openaiModel);

        LlmProviderView created = providers.create(new LlmProviderInput("TEST-ldm-new", "anthropic",
                "https://llm.example.invalid", "TEST-key", null, null, true, true, anthropicModel));

        assertTrue(created.isDefault());
        assertEquals(Optional.of(anthropicModel), setting());
    }

    /** The create requirement: replacing an enabled default without its model would leave a model of another vendor. */
    @Test
    void createWithIsDefaultWhileADefaultExistsNeedsAModel() throws SQLException {
        UUID openai = provider("TEST-ldm-openai", "openai");
        defaultModels.setDefaultProvider(openai, openaiModel);

        assertThrows(IllegalArgumentException.class, () -> providers.create(new LlmProviderInput("TEST-ldm-new",
                "anthropic", "https://llm.example.invalid", "TEST-key", null, null, true, true, null)));

        assertTrue(providers.get(openai).orElseThrow().isDefault(), "the default provider is unchanged");
        assertEquals(Optional.of(openaiModel), setting(), "the default model is unchanged");
    }

    @Test
    void providerTypeCannotChange() {
        UUID openai = provider("TEST-ldm-openai", "openai");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> providers.update(openai, new LlmProviderInput("TEST-ldm-openai", "anthropic",
                        "https://llm.example.invalid", null, null, null, true, null, null)));

        assertEquals("Provider type cannot change; create a new provider for another vendor.", refused.getMessage());
        assertEquals("openai", providers.get(openai).orElseThrow().type());
    }

    /**
     * The transaction: the setting write fails after the provider row moved, so the move must roll back. A trigger
     * raises only for this suite's TEST-ldm-explode model; without {@code @Transactional} the default would stay moved.
     */
    @Test
    void aFailedModelWriteLeavesTheDefaultProviderWhereItWas() throws SQLException {
        UUID first = provider("TEST-ldm-first", "openai");
        defaultModels.setDefaultProvider(first, openaiModel);
        UUID second = provider("TEST-ldm-second", "openai");
        String explode = catalogued("openai", "TEST-ldm-explode-" + UUID.randomUUID().toString().substring(0, 8));
        explodeOnWrite();

        assertThrows(RuntimeException.class, () -> defaultModels.setDefaultProvider(second, explode));

        assertTrue(providers.get(first).orElseThrow().isDefault(), "the default provider did not move");
        assertFalse(providers.get(second).orElseThrow().isDefault());
        assertEquals(Optional.of(openaiModel), setting());
    }

    /** The same for a create with isDefault: the new row and the cleared default roll back with the failed write. */
    @Test
    void aFailedModelWriteOnCreateLeavesNoNewDefault() throws SQLException {
        UUID first = provider("TEST-ldm-first", "openai");
        defaultModels.setDefaultProvider(first, openaiModel);
        String explode = catalogued("openai", "TEST-ldm-explode-" + UUID.randomUUID().toString().substring(0, 8));
        explodeOnWrite();

        assertThrows(RuntimeException.class, () -> providers.create(new LlmProviderInput("TEST-ldm-new", "openai",
                "https://llm.example.invalid", "TEST-key", null, null, true, true, explode)));

        assertTrue(providers.get(first).orElseThrow().isDefault(), "the old default is still the default");
        assertTrue(providers.list().stream().noneMatch(p -> p.name().equals("TEST-ldm-new")), "no new provider row");
    }

    /** And for a delete of the default: a failed clear of the setting keeps the provider. */
    @Test
    void aFailedClearOnDeleteKeepsTheProvider() throws SQLException {
        UUID first = provider("TEST-ldm-first", "openai");
        String explode = catalogued("openai", "TEST-ldm-explode-" + UUID.randomUUID().toString().substring(0, 8));
        defaultModels.setDefaultProvider(first, explode);
        explodeOnDelete();

        assertThrows(RuntimeException.class, () -> providers.delete(first));

        assertTrue(providers.get(first).isPresent(), "the provider row is still there");
        assertEquals(Optional.of(explode), setting());
    }

    private void explodeFunction() throws SQLException {
        execute("CREATE OR REPLACE FUNCTION test_ldm_explode() RETURNS trigger LANGUAGE plpgsql AS"
                + " $$ BEGIN RAISE EXCEPTION 'TEST-ldm-explode'; END $$");
    }

    private void explodeOnWrite() throws SQLException {
        explodeFunction();
        execute("CREATE TRIGGER test_ldm_explode_write BEFORE INSERT OR UPDATE ON app_setting FOR EACH ROW"
                + " WHEN (NEW.value LIKE 'TEST-ldm-explode%') EXECUTE FUNCTION test_ldm_explode()");
    }

    private void explodeOnDelete() throws SQLException {
        explodeFunction();
        execute("CREATE TRIGGER test_ldm_explode_delete BEFORE DELETE ON app_setting FOR EACH ROW"
                + " WHEN (OLD.value LIKE 'TEST-ldm-explode%') EXECUTE FUNCTION test_ldm_explode()");
    }

    // ---- fixture -------------------------------------------------------------

    private String catalogued(String type, String name) {
        models.create(new LlmModelInput(type, name, name, "UNMETERED", Map.of(),
                null, null, null, null, true, List.of()));
        return name;
    }

    private UUID provider(String name, String type) {
        return UUID.fromString(providers.create(new LlmProviderInput(name, type,
                "https://llm.example.invalid", "TEST-key", null, null, true, false, null)).id());
    }

    private Optional<String> setting() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM app_setting WHERE key=?")) {
            ps.setString(1, LlmDefaultModel.KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
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
