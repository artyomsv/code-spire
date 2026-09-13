package dev.codespire.orchestrator.context;

import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ContextCredentialReconcilerTest {
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;
    @Inject ContextCredentialReconciler reconciler;
    @Inject dev.codespire.orchestrator.attention.AttentionQueries attention;
    private final List<UUID> sources = new ArrayList<>();

    @Test
    void ambiguousLegacyCodeHostRemainsRecoverableAndVisible() throws Exception {
        UUID id = legacy("code", "https://forge.example.test", "legacy-secret");
        reconciler.reconcile();
        assertNull(account(id));
        assertTrue(attention.collect().stream().anyMatch(row ->
                row.code().equals("CONTEXT_ACCOUNT_MIGRATION_REQUIRED") && row.action().endsWith(id.toString())));
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var c = dataSource.getConnection()) {
            for (UUID id : sources) {
                UUID account = account(id);
                try (var ps = c.prepareStatement("DELETE FROM context_provider WHERE id = ?")) {
                    ps.setObject(1, id);
                    ps.executeUpdate();
                }
                if (account != null) {
                    try (var ps = c.prepareStatement("DELETE FROM scm_provider WHERE id = ? "
                            + "AND NOT EXISTS (SELECT 1 FROM context_provider WHERE account_id = ?)")) {
                        ps.setObject(1, account);
                        ps.setObject(2, account);
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    @Test
    void migratesFiveSourcesWithoutLosingSecretsAndIsIdempotent() throws Exception {
        String host = "https://" + UUID.randomUUID() + ".atlassian.net";
        UUID jira = legacy("jira", host, "same-atlassian-secret");
        UUID confluence = legacy("confluence", host + "/wiki", "same-atlassian-secret");
        UUID issues = legacy("github-issues", "https://api.github.com", "same-forge-secret");
        UUID code = legacy("code", "https://api.github.com", "same-forge-secret");
        UUID gitlab = legacy("gitlab-issues", "https://gitlab.com", "other-forge-secret");
        assertEquals(5, reconciler.reconcile());
        assertEquals(account(jira), account(confluence));
        assertEquals(account(issues), account(code));
        assertAccount(jira, "atlassian", "same-atlassian-secret");
        assertAccount(code, "github", "same-forge-secret");
        assertAccount(gitlab, "gitlab", "other-forge-secret");
        UUID original = account(jira);
        assertEquals(0, reconciler.reconcile());
        assertEquals(original, account(jira));
    }

    @Test
    void differentSecretsAndOriginsRemainSeparate() throws Exception {
        UUID one = legacy("jira", "https://a.example.test", "first-secret");
        UUID two = legacy("confluence", "https://a.example.test/wiki", "second-secret");
        UUID three = legacy("jira", "https://b.example.test", "first-secret");
        assertEquals(3, reconciler.reconcile());
        assertNotEquals(account(one), account(two));
        assertNotEquals(account(one), account(three));
        assertAccount(one, "atlassian", "first-secret");
        assertAccount(two, "atlassian", "second-secret");
    }

    @Test
    void unreadableRowSurvivesWhileOtherRowsMigrate() throws Exception {
        UUID first = legacy("jira", "https://a.example.test", "valid-first");
        UUID broken = legacy("jira", "https://a.example.test", "invalid-aad");
        String ciphertext = encryption.encryptString("do-not-log-this-secret", "wrong-aad");
        execute("UPDATE context_provider SET auth_secret = ? WHERE id = ?", ciphertext, broken);
        UUID last = legacy("gitlab-issues", "https://gitlab.com", "valid-last");
        assertEquals(2, reconciler.reconcile());
        assertNotNull(account(first));
        assertNull(account(broken));
        assertEquals(ciphertext, value("SELECT auth_secret FROM context_provider WHERE id = ?", broken));
        assertNotNull(account(last));
    }

    @Test
    void migrationDiagnosticsNeverLogPlaintextEvenAtTraceLevel() throws Exception {
        String secret = "migration-log-sentinel-" + UUID.randomUUID();
        UUID valid = legacy("jira", "https://logging.example.test", secret);
        UUID broken = legacy("jira", "https://logging.example.test", secret);
        execute("UPDATE context_provider SET auth_secret = ? WHERE id = ?", secret, broken);
        var logger = java.util.logging.Logger.getLogger(ContextCredentialReconciler.class.getName());
        var previous = logger.getLevel();
        List<String> messages = new ArrayList<>();
        var handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) {
                messages.add(record.getMessage() + java.util.Arrays.toString(record.getParameters()));
            }
            public void flush() {}
            public void close() {}
        };
        handler.setLevel(java.util.logging.Level.ALL);
        logger.setLevel(java.util.logging.Level.ALL);
        logger.addHandler(handler);
        try {
            assertEquals(1, reconciler.reconcile());
            assertNotNull(account(valid));
            assertFalse(messages.isEmpty(), "must observe real migration diagnostics");
            assertTrue(messages.stream().noneMatch(m -> m.contains(secret)));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    @Test
    void failureAfterAccountInsertRollsBackOnlyThatSource() throws Exception {
        UUID first = legacy("jira", "https://first.example.test", "first-secret");
        UUID broken = legacy("jira", "https://broken.example.test", "mid-transaction-secret");
        UUID last = legacy("jira", "https://last.example.test", "last-secret");
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("CREATE FUNCTION fail_context_migration_test() RETURNS trigger LANGUAGE plpgsql AS $$ "
                    + "BEGIN IF NEW.id = '" + broken + "'::uuid THEN RAISE EXCEPTION 'test write failure'; "
                    + "END IF; RETURN NEW; END $$");
            st.execute("CREATE TRIGGER fail_context_migration_test BEFORE UPDATE ON context_provider "
                    + "FOR EACH ROW EXECUTE FUNCTION fail_context_migration_test()");
            try {
                assertEquals(2, reconciler.reconcile());
                assertNotNull(account(first));
                assertNotNull(account(last));
                assertNull(account(broken));
                assertNotNull(value("SELECT auth_secret FROM context_provider WHERE id = ?", broken));
                try (var rs = st.executeQuery("SELECT count(*) FROM scm_provider WHERE base_url = 'https://broken.example.test'")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "failed source must not leave an orphan account");
                }
            } finally {
                st.execute("DROP TRIGGER fail_context_migration_test ON context_provider");
                st.execute("DROP FUNCTION fail_context_migration_test()");
            }
        }
        assertEquals(1, reconciler.reconcile());
        assertAccount(broken, "atlassian", "mid-transaction-secret");
    }

    @Test
    void databaseRejectsBothAndNeitherCredentialShapes() throws Exception {
        UUID source = legacy("jira", "https://check.example.test", "schema-secret");
        assertThrows(SQLException.class, () -> execute("UPDATE context_provider SET auth_secret = NULL WHERE id = ?", source));
        assertEquals(1, reconciler.reconcile());
        assertThrows(SQLException.class, () -> execute("UPDATE context_provider SET auth_secret = 'extra' WHERE id = ?", source));
        execute("UPDATE scm_provider SET workspace = 'TEST-retained-rollback-evidence' WHERE id = ?", account(source));
        assertEquals("TEST-retained-rollback-evidence", value("SELECT workspace FROM scm_provider WHERE id = ?", account(source)));
        assertThrows(SQLException.class, () -> execute("UPDATE scm_provider SET role = 'TEST-UNKNOWN' WHERE id = ?", account(source)));
    }

    private UUID legacy(String type, String url, String secret) throws Exception {
        UUID id = UUID.randomUUID();
        sources.add(id);
        execute("INSERT INTO context_provider (id, name, type, base_url, auth_kind, auth_username, auth_secret) "
                + "VALUES (?, ?, ?, ?, 'bearer', NULL, ?)", id, "source-" + id, type, url,
                encryption.encryptString(secret, "context-provider:" + id));
        return id;
    }

    private UUID account(UUID source) throws Exception {
        String id = value("SELECT account_id FROM context_provider WHERE id = ?", source);
        return id == null ? null : UUID.fromString(id);
    }

    private void assertAccount(UUID source, String type, String secret) throws Exception {
        UUID account = account(source);
        assertNotNull(account);
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement("SELECT * FROM scm_provider WHERE id = ?")) {
            ps.setObject(1, account);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(type, rs.getString("type"));
                assertEquals("CONTEXT", rs.getString("role"));
                assertNull(rs.getString("workspace"));
                String encrypted = rs.getString("auth_secret");
                assertFalse(encrypted.contains(secret));
                assertEquals(secret, encryption.decryptString(encrypted, "provider:" + account));
                assertThrows(RuntimeException.class, () -> encryption.decryptString(encrypted, "context-provider:" + source));
            }
        }
        assertNull(value("SELECT auth_secret FROM context_provider WHERE id = ?", source));
        assertNull(value("SELECT auth_kind FROM context_provider WHERE id = ?", source));
        assertNull(value("SELECT auth_username FROM context_provider WHERE id = ?", source));
    }

    private String value(String sql, UUID id) throws Exception {
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
            ps.executeUpdate();
        }
    }
}
