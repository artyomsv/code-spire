package dev.codespire.gateway.registry;

import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RepositorySnapshotMigrationTest {
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;

    @Test void upgradeQueuesExistingRegistrationWithoutChangingKeyOrSecret() throws Exception {
        String schema = "test_registry_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        UUID id = UUID.randomUUID();
        String ciphertext = encryption.encryptString("TEST-webhook-secret", "webhook:" + id);
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("2").load().migrate();
        try (var c = dataSource.getConnection()) {
            String previous = c.getSchema();
            try {
                c.setSchema(schema);
                try (var ps = c.prepareStatement("INSERT INTO webhook_repo (id,provider_type,scope,target,webhook_key,webhook_secret) VALUES (?,'gitlab','repo','TEST-group/nested/TEST-repo','TEST-routing-key',?)")) {
                    ps.setObject(1, id); ps.setString(2, ciphertext); ps.executeUpdate();
                }
                Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3").load().migrate();
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT w.id,w.webhook_key,w.webhook_secret,o.payload::text FROM webhook_repo w JOIN repository_snapshot_outbox o ON o.registration_id=w.id")) {
                    assertTrue(rs.next()); assertEquals(id, rs.getObject(1, UUID.class)); assertEquals("TEST-routing-key", rs.getString(2));
                    assertEquals(ciphertext, rs.getString(3)); assertEquals("TEST-webhook-secret", encryption.decryptString(rs.getString(3), "webhook:" + id));
                    assertFalse(rs.getString(4).contains("TEST-webhook-secret")); assertFalse(rs.getString(4).contains(ciphertext)); assertFalse(rs.next());
                }
            } finally {
                c.setSchema(previous);
                try (var st = c.createStatement()) { st.execute("DROP SCHEMA " + schema + " CASCADE"); }
            }
        }
    }
}
