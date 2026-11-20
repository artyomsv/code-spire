package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chosen model is a bare name: the default review model ({@code llm.default-model}) and each repository's
 * {@code review_llm_model} hold it with no foreign key. Delete, rename and a type change of a manual model in use are
 * refused, naming the uses; every other field stays editable. The default model counts only while an enabled default
 * provider exists.
 *
 * <p>Uses are written with SQL, so this suite tests the guard alone.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class LlmModelInUseGuardTest {

    private static final String WORKSPACE = "TEST-miu-ws";

    @Inject LlmModelRegistry models;
    @Inject LlmProviderRegistry providers;
    @Inject DataSource dataSource;

    private UUID provider;
    private String suffix;

    @BeforeEach
    void aProvider() throws SQLException {
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        provider = UUID.fromString(providers.create(new LlmProviderInput("TEST-miu-" + suffix, "openai",
                "https://llm.example.invalid", "TEST-key", null, null, true, false, null)).id());
    }

    @AfterEach
    void removeFixture() throws SQLException {
        execute("DELETE FROM repository WHERE workspace=?", WORKSPACE);
        providers.delete(provider);
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        execute("DELETE FROM llm_model WHERE name LIKE ? OR (name = 'TEST-cat-a' AND type = 'openai')", "TEST-miu-%");
    }

    @Test
    void aModelUsedAsTheDefaultCannotBeDeleted() throws SQLException {
        LlmModelView model = models.create(metered("TEST-miu-default-" + suffix));
        asTheDefault(model.name());

        assertThrows(ModelInUseException.class, () -> models.delete(UUID.fromString(model.id())));
    }

    /** With no enabled default provider nothing spends the setting, so it is a leftover, not a use. */
    @Test
    void aModelUsedAsTheDefaultCanBeDeletedWhenNoDefaultProviderExists() throws SQLException {
        LlmModelView model = models.create(metered("TEST-miu-leftover-" + suffix));
        asTheDefault(model.name());
        execute("UPDATE llm_provider SET enabled = FALSE WHERE id=?", provider);

        assertTrue(models.delete(UUID.fromString(model.id())));
    }

    @Test
    void aModelUsedByARepositoryCannotBeRenamed() throws SQLException {
        LlmModelView model = models.create(metered("TEST-miu-repo-" + suffix));
        repositoryChoosing("TEST-repo-1", model.name());

        ModelInUseException refused = assertThrows(ModelInUseException.class,
                () -> models.update(UUID.fromString(model.id()), metered(model.name() + "-renamed")));

        assertTrue(refused.getMessage().contains("then rename it"), refused.getMessage());
    }

    /** A catalog entry's alias reaches the manual model that shadows it, so a choice of the alias is a use. */
    @Test
    void aModelUsedThroughAnAliasCannotBeRenamed() throws SQLException {
        LlmModelView model = models.create(new LlmModelInput("anthropic", "TEST-cat-a", "TEST-cat-a", "METERED",
                Map.of("INPUT", 2L, "OUTPUT", 3L), null, null, null, null, true, List.of()));
        try {
            repositoryChoosing("TEST-repo-1", "TEST-cat-a-dated");

            assertThrows(ModelInUseException.class, () -> models.update(UUID.fromString(model.id()),
                    new LlmModelInput("anthropic", "TEST-miu-alias-" + suffix, "TEST-cat-a", "METERED",
                            Map.of("INPUT", 2L, "OUTPUT", 3L), null, null, null, null, true, List.of())));
        } finally {
            execute("DELETE FROM repository WHERE workspace=?", WORKSPACE);
            execute("DELETE FROM llm_model WHERE id=?", UUID.fromString(model.id()));
        }
    }

    /** The type-change guard: same name, another vendor, would turn the choice into a model of another vendor. */
    @Test
    void anInUseManualModelCannotChangeType() throws SQLException {
        LlmModelView model = models.create(metered("TEST-miu-type-" + suffix));
        repositoryChoosing("TEST-repo-1", model.name());

        ModelInUseException refused = assertThrows(ModelInUseException.class,
                () -> models.update(UUID.fromString(model.id()), new LlmModelInput("anthropic", model.name(),
                        model.name(), "METERED", Map.of("INPUT", 2L, "OUTPUT", 3L), null, null, null, null, true,
                        List.of())));

        assertTrue(refused.getMessage().contains("then change its type"), refused.getMessage());
    }

    /**
     * "Use catalog" deletes an in-use manual model only when the catalog entry it shadows is of the same vendor:
     * TEST-cat-a is an anthropic entry, the manual model an openai one, so the choice would change vendor.
     */
    @Test
    void useCatalogOfADifferentVendorEntryIsRefused() throws SQLException {
        LlmModelView model = models.create(new LlmModelInput("openai", "TEST-cat-a", "TEST-cat-a", "METERED",
                Map.of("INPUT", 2L, "OUTPUT", 3L), null, null, null, null, true, List.of()));
        repositoryChoosing("TEST-repo-1", "TEST-cat-a");

        assertThrows(ModelInUseException.class, () -> models.delete(UUID.fromString(model.id())));
    }

    @Test
    void theMessageNamesTheUses() throws SQLException {
        LlmModelView model = models.create(metered("TEST-miu-named-" + suffix));
        asTheDefault(model.name());
        repositoryChoosing("TEST-repo-1", model.name());
        repositoryChoosing("TEST-repo-2", model.name());

        ModelInUseException refused = assertThrows(ModelInUseException.class,
                () -> models.delete(UUID.fromString(model.id())));

        assertEquals("Model '" + model.name() + "' is in use by the default review model, repository "
                + WORKSPACE + "/TEST-repo-1, repository " + WORKSPACE + "/TEST-repo-2. Choose another model there"
                + " first.", refused.getMessage());
    }

    @Test
    void otherFieldsRemainEditableWhileAModelIsReferenced() throws SQLException {
        LlmModelView model = models.create(metered("TEST-miu-editable-" + suffix));
        repositoryChoosing("TEST-repo-1", model.name());

        LlmModelView updated = models.update(UUID.fromString(model.id()),
                new LlmModelInput("openai", model.name(), "TEST relabeled", "METERED", Map.of("INPUT", 3L, "OUTPUT", 4L),
                        "MAX_COMPLETION_TOKENS", null, null, Map.of(), true, List.of())).orElseThrow();

        assertEquals("TEST relabeled", updated.label());
        assertEquals(3L, updated.rates().get("INPUT"));
        assertEquals("MAX_COMPLETION_TOKENS", updated.outputTokenParam());
    }

    @Test
    void renamingAnUnreferencedModelSucceeds() {
        LlmModelView model = models.create(metered("TEST-miu-free-" + suffix));

        LlmModelView renamed = models.update(UUID.fromString(model.id()), metered(model.name() + "-renamed"))
                .orElseThrow();

        assertEquals(model.name() + "-renamed", renamed.name());
    }

    // ---- fixture -------------------------------------------------------------

    private static LlmModelInput metered(String name) {
        return new LlmModelInput("openai", name, name, "METERED", Map.of("INPUT", 2L, "OUTPUT", 3L),
                null, null, null, null, true, List.of());
    }

    /** This suite's provider becomes the enabled default, with this model, by SQL. */
    private void asTheDefault(String model) throws SQLException {
        execute("UPDATE llm_provider SET is_default = FALSE WHERE is_default");
        execute("UPDATE llm_provider SET is_default = TRUE WHERE id=?", provider);
        execute("INSERT INTO app_setting (key, value) VALUES (?, ?)", LlmDefaultModel.KEY, model);
    }

    private void repositoryChoosing(String slug, String model) throws SQLException {
        execute("INSERT INTO repository (id, scm_type, forge_origin, workspace, slug, review_llm_provider_id,"
                + " review_llm_model) VALUES (?, 'github', 'https://github.example.invalid', ?, ?, ?, ?)",
                UUID.randomUUID(), WORKSPACE, slug, provider, model);
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
