package dev.codespire.orchestrator.repository;

import dev.codespire.orchestrator.provider.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/** TEST-only fixtures in Quarkus Dev Services; no live database or forge calls. */
abstract class RepositoryFixture {
    @Inject DataSource dataSource;
    @Inject ProviderRegistry providers;
    @Inject RepositoryRegistry repositories;
    @Inject RepositoryAccounts accounts;
    @Inject RepositoryMigrationBridge bridge;
    @Inject RepositoryMappings mappings;
    String workspace;
    final java.util.Set<UUID> createdAccounts = new java.util.HashSet<>();
    final java.util.Set<UUID> createdRegistrations = new java.util.HashSet<>();
    final String origin = "https://TEST-forge.example.test".toLowerCase();

    @BeforeEach void nameFixture() { workspace = "TEST-" + UUID.randomUUID() + "/nested"; }

    ProviderInput input(String role, String host, boolean enabled, String identity) {
        return new ProviderInput("TEST-" + role, "gitlab", host, "bearer", null,
                "TEST-secret-" + role, identity, enabled, List.of(), "TEST-login-" + role, null, role);
    }

    UUID account(String role) { return account(role, origin); }
    UUID account(String role, String host) {
        UUID id = UUID.fromString(providers.create(input(role, host, true, "TEST-id-" + role)).id());
        createdAccounts.add(id);
        return id;
    }

    RepositoryInput repository(UUID reviewer, UUID factory) {
        return new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, reviewer, factory);
    }

    void snapshotAccounts() throws Exception {
        execute("INSERT INTO repository_legacy_account SELECT id,type,base_url,workspace,role FROM scm_provider WHERE workspace=? ON CONFLICT DO NOTHING", workspace);
    }

    void execute(String sql, Object... parameters) throws Exception {
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) ps.setObject(i + 1, parameters[i]);
            ps.executeUpdate();
        }
    }

    @AfterEach void removeFixture() throws Exception {
        execute("DELETE FROM repository_unregistered_event WHERE workspace=?", workspace);
        execute("DELETE FROM review_status WHERE workspace=?", workspace);
        execute("DELETE FROM factory_run WHERE workspace=?", workspace);
        execute("DELETE FROM repository_registration_bridge WHERE target=?", workspace + "/TEST-repo");
        for (UUID id : createdRegistrations) execute("DELETE FROM repository_registration_bridge WHERE registration_id=?", id);
        execute("DELETE FROM repository_account WHERE repository_id IN (SELECT id FROM repository WHERE workspace=?)", workspace);
        execute("DELETE FROM repository WHERE workspace=?", workspace);
        execute("DELETE FROM repository_legacy_account WHERE workspace=?", workspace);
        execute("DELETE FROM scm_provider WHERE workspace=?", workspace);
        for (UUID id : createdAccounts) execute("DELETE FROM scm_provider WHERE id=?", id);
    }
}
