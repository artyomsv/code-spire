package dev.codespire.orchestrator.context;

import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real V58 -> V59 upgrade with populated registries, independently of startup reconciliation. */
@QuarkusTest
class AccountsSchemaMigrationTest {
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;

    @Test
    void appliesOverSixAccountsAndFiveLegacySourcesWithoutChangingCiphertext() throws Exception {
        String schema = "accounts_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("58").load().migrate();
        try (var c = dataSource.getConnection()) {
            String originalSchema = c.getSchema();
            try {
                c.setSchema(schema);
                for (String kind : List.of("github", "gitlab", "bitbucket-cloud")) {
                    for (String role : List.of("REVIEWER", "FACTORY")) {
                        UUID id = UUID.randomUUID();
                        try (var ps = c.prepareStatement("INSERT INTO scm_provider "
                                + "(id,name,type,base_url,workspace,auth_kind,auth_secret,role) VALUES (?,?,?,'https://forge.example.test','ws','bearer',?,?)")) {
                            ps.setObject(1, id); ps.setString(2, kind + role); ps.setString(3, kind);
                            ps.setString(4, encryption.encryptString("account-fixture", "provider:" + id));
                            ps.setString(5, role); ps.executeUpdate();
                        }
                    }
                }
                var ciphertexts = new java.util.HashMap<UUID, String>();
                for (String type : List.of("jira", "confluence", "github-issues", "gitlab-issues", "code")) {
                    UUID id = UUID.randomUUID();
                    String encrypted = encryption.encryptString("source-fixture-" + type, "context-provider:" + id);
                    ciphertexts.put(id, encrypted);
                    try (var ps = c.prepareStatement("INSERT INTO context_provider "
                            + "(id,name,type,base_url,auth_kind,auth_secret) VALUES (?,?,?,'https://source.example.test','bearer',?)")) {
                        ps.setObject(1, id); ps.setString(2, type); ps.setString(3, type);
                        ps.setString(4, encrypted); ps.executeUpdate();
                    }
                }
                Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT id, account_id, auth_secret FROM context_provider")) {
                    int count = 0;
                    while (rs.next()) {
                        count++;
                        assertNull(rs.getObject("account_id"));
                        assertEquals(ciphertexts.get(rs.getObject("id", UUID.class)), rs.getString("auth_secret"));
                    }
                    assertEquals(5, count);
                }
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM scm_provider")) {
                    assertTrue(rs.next()); assertEquals(6, rs.getInt(1));
                }
            } finally {
                c.setSchema(originalSchema);
                // A generated schema in the test container, never an application schema.
                try (var st = c.createStatement()) { st.execute("DROP SCHEMA " + schema + " CASCADE"); }
            }
        }
    }
}
