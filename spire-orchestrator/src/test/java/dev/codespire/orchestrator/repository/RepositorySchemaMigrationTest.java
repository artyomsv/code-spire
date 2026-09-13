package dev.codespire.orchestrator.repository;

import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Actual populated V59 -> V60 migration in a private Dev Services schema. */
@QuarkusTest
class RepositorySchemaMigrationTest {
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;

    @Test void databaseRejectsBlankRepositoryOrigin() throws Exception {
        UUID id = UUID.randomUUID();
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement insert = connection.prepareStatement("INSERT INTO repository (id,scm_type,forge_origin,workspace,slug) VALUES (?,'gitlab','','TEST-blank-origin','TEST-repo')");
             java.sql.PreparedStatement cleanup = connection.prepareStatement("DELETE FROM repository WHERE id=?")) {
            insert.setObject(1, id); cleanup.setObject(1, id);
            try {
                java.sql.SQLException failure = assertThrows(java.sql.SQLException.class, insert::executeUpdate);
                assertEquals("23514", failure.getSQLState());
            } finally { cleanup.executeUpdate(); }
        }
    }

    @Test void preservesAccountIdsCredentialsAndContextReferences() throws Exception {
        String schema = "test_repository_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("59").load().migrate();
        UUID reviewer = UUID.randomUUID(), factory = UUID.randomUUID(), source = UUID.randomUUID();
        String reviewerCipher = encryption.encryptString("TEST-review-token", "provider:" + reviewer);
        String factoryCipher = encryption.encryptString("TEST-factory-token", "provider:" + factory);
        try (var c = dataSource.getConnection()) {
            String previous = c.getSchema();
            try {
                c.setSchema(schema);
                try (var ps = c.prepareStatement("INSERT INTO scm_provider (id,name,type,base_url,workspace,auth_kind,auth_secret,role) VALUES (?,?,'gitlab','https://TEST-forge.example.test','TEST-group/nested','bearer',?,?)")) {
                    ps.setObject(1, reviewer); ps.setString(2, "TEST-reviewer"); ps.setString(3, reviewerCipher); ps.setString(4, "REVIEWER"); ps.executeUpdate();
                    ps.setObject(1, factory); ps.setString(2, "TEST-factory"); ps.setString(3, factoryCipher); ps.setString(4, "FACTORY"); ps.executeUpdate();
                }
                try (var ps = c.prepareStatement("INSERT INTO context_provider (id,name,type,base_url,account_id) VALUES (?,'TEST-context','gitlab-issues','https://TEST-forge.example.test',?)")) {
                    ps.setObject(1, source); ps.setObject(2, reviewer); ps.executeUpdate();
                }
                Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("60").load().migrate();
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT p.id,p.auth_secret,l.account_id,l.workspace,p.role FROM scm_provider p JOIN repository_legacy_account l ON l.account_id=p.id ORDER BY p.role")) {
                    assertTrue(rs.next()); assertEquals(factory, rs.getObject(1, UUID.class)); assertEquals(factoryCipher, rs.getString(2));
                    assertEquals("TEST-factory-token", encryption.decryptString(rs.getString(2), "provider:" + rs.getObject(1)));
                    assertTrue(rs.next()); assertEquals(reviewer, rs.getObject(1, UUID.class)); assertEquals(reviewerCipher, rs.getString(2));
                    assertEquals("TEST-review-token", encryption.decryptString(rs.getString(2), "provider:" + rs.getObject(1)));
                    assertEquals("TEST-group/nested", rs.getString(4)); assertFalse(rs.next());
                }
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT id,account_id,auth_secret FROM context_provider")) {
                    assertTrue(rs.next()); assertEquals(source, rs.getObject(1, UUID.class)); assertEquals(reviewer, rs.getObject(2, UUID.class)); assertNull(rs.getString(3));
                }
            } finally {
                c.setSchema(previous);
                try (var st = c.createStatement()) { st.execute("DROP SCHEMA " + schema + " CASCADE"); }
            }
        }
    }
}
