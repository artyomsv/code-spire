package dev.codespire.orchestrator.provider;

import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Populated V71 evidence survives until the explicit final migration; only its account column goes. */
@QuarkusTest
class AccountWorkspaceDropMigrationTest {
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;

    @Test void dropsOnlyLegacyWorkspaceAfterPreservingEveryOtherAccountFieldAndReference() throws Exception {
        String schema = "test_workspace_drop_" + UUID.randomUUID().toString().replace("-", "");
        var upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema);
        upgrade.target("71").load().migrate();
        try (Connection connection = dataSource.getConnection()) {
            String previous = connection.getSchema();
            try {
                connection.setSchema(schema);
                UUID reviewer = UUID.randomUUID(), factory = UUID.randomUUID(), repository = UUID.randomUUID(), source = UUID.randomUUID();
                for (var account : List.of(reviewer, factory)) {
                    String role = account.equals(reviewer) ? "REVIEWER" : "FACTORY";
                    try (var insert = connection.prepareStatement("INSERT INTO scm_provider(id,name,type,base_url,workspace,auth_kind,auth_secret,role) VALUES (?,?,'github','https://TEST-forge.example.test','TEST-retained-workspace','bearer',?,?)")) {
                        insert.setObject(1, account); insert.setString(2, "TEST-" + role);
                        insert.setString(3, encryption.encryptString("TEST-secret-" + role, "provider:" + account));
                        insert.setString(4, role); assertEquals(1, insert.executeUpdate());
                    }
                }
                try (var insert = connection.prepareStatement("INSERT INTO repository(id,scm_type,forge_origin,workspace,slug) VALUES (?,'github','https://TEST-forge.example.test','TEST-retained-workspace','TEST-repo')")) {
                    insert.setObject(1, repository); assertEquals(1, insert.executeUpdate());
                }
                try (var insert = connection.prepareStatement("INSERT INTO repository_account(repository_id,account_id,role) VALUES (?,?,'REVIEWER'),(?,?,'FACTORY')")) {
                    insert.setObject(1, repository); insert.setObject(2, reviewer); insert.setObject(3, repository); insert.setObject(4, factory);
                    assertEquals(2, insert.executeUpdate());
                }
                try (var insert = connection.prepareStatement("INSERT INTO context_provider(id,name,type,base_url,account_id) VALUES (?,'TEST-context','github-issues','https://TEST-forge.example.test',?)")) {
                    insert.setObject(1, source); insert.setObject(2, reviewer); assertEquals(1, insert.executeUpdate());
                }
                try (var statement = connection.createStatement()) {
                    assertEquals(2, statement.executeUpdate("INSERT INTO repository_legacy_account SELECT id,type,base_url,workspace,role FROM scm_provider"));
                }
                assertTrue(hasWorkspace(connection, schema), "The populated rollback column must still exist at V71");
                var accounts = rows(connection, "SELECT (to_jsonb(p)-'workspace')::text FROM scm_provider p ORDER BY id");
                var bindings = rows(connection, "SELECT to_jsonb(r)::text FROM repository_account r ORDER BY role");
                var contexts = rows(connection, "SELECT to_jsonb(c)::text FROM context_provider c ORDER BY id");
                var legacy = rows(connection, "SELECT to_jsonb(l)::text FROM repository_legacy_account l ORDER BY account_id");
                assertEquals(2, accounts.size()); assertEquals(2, bindings.size()); assertEquals(1, contexts.size());
                assertEquals(2, legacy.size()); assertTrue(legacy.stream().allMatch(row -> row.contains("TEST-retained-workspace")));

                upgrade.target("72").load().migrate();

                assertFalse(hasWorkspace(connection, schema), "V72 must actually drop the column, not merely stop using it");
                assertEquals(accounts, rows(connection, "SELECT to_jsonb(p)::text FROM scm_provider p ORDER BY id"));
                assertEquals(bindings, rows(connection, "SELECT to_jsonb(r)::text FROM repository_account r ORDER BY role"));
                assertEquals(contexts, rows(connection, "SELECT to_jsonb(c)::text FROM context_provider c ORDER BY id"));
                assertEquals(legacy, rows(connection, "SELECT to_jsonb(l)::text FROM repository_legacy_account l ORDER BY account_id"));
                try (var query = connection.prepareStatement("SELECT auth_secret FROM scm_provider WHERE id=?")) {
                    query.setObject(1, reviewer);
                    try (var result = query.executeQuery()) {
                        assertTrue(result.next()); assertEquals("TEST-secret-REVIEWER", encryption.decryptString(result.getString(1), "provider:" + reviewer));
                    }
                }
            } finally { connection.setSchema(previous); }
        } finally {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).cleanDisabled(false).load().clean();
        }
    }

    private boolean hasWorkspace(Connection connection, String schema) throws Exception {
        try (var query = connection.prepareStatement("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='scm_provider' AND column_name='workspace'")) {
            query.setString(1, schema);
            try (var result = query.executeQuery()) { assertTrue(result.next()); return result.getInt(1) == 1; }
        }
    }

    private List<String> rows(Connection connection, String sql) throws Exception {
        var rows = new ArrayList<String>();
        try (var query = connection.prepareStatement(sql); var result = query.executeQuery()) {
            while (result.next()) rows.add(result.getString(1));
        }
        return rows;
    }
}
