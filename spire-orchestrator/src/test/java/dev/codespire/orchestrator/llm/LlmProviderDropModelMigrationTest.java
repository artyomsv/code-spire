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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V119 on a populated database, in a private schema: migrate to V118, seed, migrate to V119. Rows that were valid
 * before stay valid under the tightened CHECK, and {@code llm_provider.model} is gone.
 */
@QuarkusTest
class LlmProviderDropModelMigrationTest {
    @Inject DataSource dataSource;

    /** V109 let a harness review leave its effort null (the model's own default); V119 must keep that row. */
    @Test
    void aHarnessRowWithANullEffortSurvives() throws Exception {
        UUID repository = UUID.randomUUID();
        inStagedSchema(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO repository (id, scm_type, forge_origin, workspace,"
                    + " slug, review_harness, review_harness_model, review_harness_effort, review_harness_pay_with)"
                    + " VALUES (?, 'github', 'https://TEST-forge.example.test', 'TEST-ws', 'TEST-repo', 'codex',"
                    + " 'TEST-model', NULL, 'API_KEY')")) {
                ps.setObject(1, repository);
                ps.executeUpdate();
            }
        }, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT review_harness FROM repository WHERE id = ?")) {
                ps.setObject(1, repository);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("codex", rs.getString(1));
                }
            }
        });
    }

    /** A repository V118 filled from its provider keeps provider and model together through the new CHECK. */
    @Test
    void aRepositoryWithItsProvidersModelSurvives() throws Exception {
        UUID provider = UUID.randomUUID();
        inStagedSchema(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO llm_provider (id, name, type, base_url, api_key, model) VALUES ('" + provider
                        + "', 'TEST-p', 'openai', 'https://TEST-llm.example.test', 'TEST-cipher', 'TEST-m')");
                st.executeUpdate("INSERT INTO repository (id, scm_type, forge_origin, workspace, slug,"
                        + " review_llm_provider_id, review_llm_model) VALUES ('" + UUID.randomUUID() + "', 'github',"
                        + " 'https://TEST-forge.example.test', 'TEST-ws', 'TEST-repo', '" + provider + "', 'TEST-m')");
            }
        }, c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM repository WHERE review_llm_model = 'TEST-m'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        });
    }

    @Test
    void theProviderModelColumnIsGone() throws Exception {
        inStagedSchema(c -> { }, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM information_schema.columns"
                    + " WHERE table_schema = current_schema() AND table_name = 'llm_provider' AND column_name = 'model'");
                 ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next(), "llm_provider.model must be dropped");
            }
        });
    }

    private interface Step {
        void run(Connection c) throws SQLException;
    }

    private void inStagedSchema(Step seed, Step check) throws SQLException {
        String schema = "test_llm_drop_model_" + UUID.randomUUID().toString().replace("-", "");
        migrate(schema, "118");
        try (Connection c = dataSource.getConnection()) {
            String previous = c.getSchema();
            try {
                c.setSchema(schema);
                seed.run(c);
                migrate(schema, "119");
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
}
