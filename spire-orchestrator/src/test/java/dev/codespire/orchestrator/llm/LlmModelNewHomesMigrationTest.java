package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V118 on a populated database, in a private schema: migrate to V117, seed, migrate to V118. The default provider's
 * model becomes the {@code llm.default-model} setting and a repository keeps its provider's model in its own column.
 */
@QuarkusTest
class LlmModelNewHomesMigrationTest {
    @Inject DataSource dataSource;

    @Test
    void theDefaultProvidersModelBecomesTheSetting() throws Exception {
        inStagedSchema(c -> {
            insertProvider(c, UUID.randomUUID(), "TEST-p1", "TEST-m1", true);
            insertProvider(c, UUID.randomUUID(), "TEST-p3", "TEST-m3", false);
        }, c -> assertEquals(Optional.of("TEST-m1"), setting(c)));
    }

    @Test
    void aRepositoryKeepsItsProvidersModel() throws Exception {
        UUID provider = UUID.randomUUID();
        UUID named = UUID.randomUUID();
        UUID unnamed = UUID.randomUUID();
        inStagedSchema(c -> {
            insertProvider(c, provider, "TEST-p2", "TEST-m2", false);
            insertRepository(c, named, provider, "TEST-repo-named");
            insertRepository(c, unnamed, null, "TEST-repo-default");
        }, c -> {
            assertEquals("TEST-m2", repositoryModel(c, named));
            assertNull(repositoryModel(c, unnamed));
        });
    }

    @Test
    void noDefaultProviderWritesNoSetting() throws Exception {
        inStagedSchema(c -> insertProvider(c, UUID.randomUUID(), "TEST-p1", "TEST-m1", false),
                c -> assertTrue(setting(c).isEmpty()));
    }

    @Test
    void anExistingSettingIsOverwritten() throws Exception {
        inStagedSchema(c -> {
            insertProvider(c, UUID.randomUUID(), "TEST-p1", "TEST-m1", true);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO app_setting (key, value) VALUES ('llm.default-model', 'TEST-stale')");
            }
        }, c -> assertEquals(Optional.of("TEST-m1"), setting(c)));
    }

    private interface Step {
        void run(Connection c) throws SQLException;
    }

    private void inStagedSchema(Step seed, Step check) throws SQLException {
        String schema = "test_llm_model_homes_" + UUID.randomUUID().toString().replace("-", "");
        migrate(schema, "117");
        try (Connection c = dataSource.getConnection()) {
            String previous = c.getSchema();
            try {
                c.setSchema(schema);
                seed.run(c);
                migrate(schema, "118");
                check.run(c);
            } finally {
                c.setSchema(previous);
                try (Statement st = c.createStatement()) {
                    st.execute("DROP SCHEMA " + schema + " CASCADE");
                }
            }
        }
    }

    private void migrate(String schema, String target) {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target(target).load().migrate();
    }

    private static void insertProvider(Connection c, UUID id, String name, String model, boolean isDefault)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO llm_provider (id, name, type, base_url, api_key, model,"
                + " is_default) VALUES (?, ?, 'openai', 'https://TEST-llm.example.test', 'TEST-cipher', ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, model);
            ps.setBoolean(4, isDefault);
            ps.executeUpdate();
        }
    }

    private static void insertRepository(Connection c, UUID id, UUID provider, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO repository (id, scm_type, forge_origin, workspace,"
                + " slug, review_llm_provider_id) VALUES (?, 'github', 'https://TEST-forge.example.test', 'TEST-ws', ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, slug);
            ps.setObject(3, provider);
            ps.executeUpdate();
        }
    }

    private static Optional<String> setting(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM app_setting WHERE key = 'llm.default-model'")) {
            return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
        }
    }

    private static String repositoryModel(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT review_llm_model FROM repository WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }
}
