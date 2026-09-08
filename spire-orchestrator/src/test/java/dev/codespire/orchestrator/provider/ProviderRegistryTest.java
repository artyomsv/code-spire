package dev.codespire.orchestrator.provider;

import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provider registry against real Postgres: encrypted-at-rest, keep/rotate, resolve, delete. */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ProviderRegistryTest {

    @Inject
    ProviderRegistry registry;

    @Inject
    DataSource dataSource;

    private static ProviderInput bearer(String workspace, String secret, List<String> authors) {
        return new ProviderInput("CF Bitbucket", "bitbucket-cloud", "https://api.bitbucket.org/2.0",
                workspace, "bearer", null, secret, "acct-1", true, authors, null, null);
    }

    @Test
    void createsEncryptsAtRestAndResolves() throws Exception {
        ProviderView created = registry.create(bearer("ws-create", "tok-SECRET-123", List.of("alice", "bob")));
        assertNotNull(created.id());
        assertTrue(created.hasSecret());
        assertEquals(List.of("alice", "bob"), created.authors());

        assertFalse(rawSecret(created.id()).contains("tok-SECRET-123"), "token must be encrypted at rest");

        ScmProvider resolved = registry.resolve("bitbucket-cloud", "ws-create").orElseThrow();
        assertEquals("tok-SECRET-123", resolved.secret(), "resolve decrypts the token");
    }

    @Test
    void updateKeepsSecretWhenBlankAndReplacesAuthors() {
        ProviderView created = registry.create(bearer("ws-keep", "tok-keep", List.of("alice")));
        UUID id = UUID.fromString(created.id());
        registry.update(id, new ProviderInput("Renamed", "bitbucket-cloud", "https://api.bitbucket.org/2.0",
                "ws-keep", "bearer", null, null, "acct-1", true, List.of("carol"), null, null));

        assertEquals("tok-keep", registry.resolve("bitbucket-cloud", "ws-keep").orElseThrow().secret());
        ProviderView view = registry.get(id).orElseThrow();
        assertEquals("Renamed", view.name());
        assertEquals(List.of("carol"), view.authors());
    }

    @Test
    void updateRotatesSecretWhenProvided() {
        ProviderView created = registry.create(bearer("ws-rotate", "old-tok", List.of()));
        registry.update(UUID.fromString(created.id()), bearer("ws-rotate", "new-tok", List.of()));
        assertEquals("new-tok", registry.resolve("bitbucket-cloud", "ws-rotate").orElseThrow().secret());
    }

    @Test
    void deleteRemovesProvider() {
        ProviderView created = registry.create(bearer("ws-delete", "tok", List.of("alice")));
        UUID id = UUID.fromString(created.id());
        assertTrue(registry.delete(id));
        assertTrue(registry.get(id).isEmpty());
        assertTrue(registry.resolve("bitbucket-cloud", "ws-delete").isEmpty());
    }

    @Test
    void disabledProviderIsNotResolved() {
        registry.create(new ProviderInput("Off", "bitbucket-cloud", "https://api.bitbucket.org/2.0",
                "ws-disabled", "bearer", null, "tok", "acct-1", false, List.of(), null, null));
        assertTrue(registry.resolve("bitbucket-cloud", "ws-disabled").isEmpty());
    }

    private static ProviderInput withRole(String workspace, String role) {
        return new ProviderInput("Role bot", "github", "https://api.github.com", workspace, "bearer", null,
                "TEST-token", "TEST-acct", true, List.of(), "role-bot", null, role);
    }

    /**
     * A registration's role is fixed for its lifetime (spec 2026-09-07 §4, decision 2).
     *
     * <p>Changing it re-purposes one token under the other authority set — a reviewer that suddenly
     * holds the push identity, or a factory account the review path starts posting as. HISTORY records
     * the day a role-less PUT did that by accident (V44's COALESCE is the repair); an explicit change
     * is the same event on purpose. The cure is the one an operator applies to any service account:
     * register the new one, delete the old one.
     */
    @Test
    void aRoleIsFixedAtRegistration() {
        UUID id = UUID.fromString(registry.create(withRole("ws-role-fixed", "FACTORY")).id());

        assertThrows(ProviderRegistry.RoleIsFixedAtRegistration.class,
                () -> registry.update(id, withRole("ws-role-fixed", "REVIEWER")));
        assertEquals("FACTORY", registry.get(id).orElseThrow().role(), "a refused change writes nothing");

        // The same role, and no role at all, both mean "keep it" — the dashboard's edit form sends
        // the stored role; older clients send none.
        assertTrue(registry.update(id, withRole("ws-role-fixed", "FACTORY")).isPresent());
        assertTrue(registry.update(id, withRole("ws-role-fixed", null)).isPresent());
        assertEquals("FACTORY", registry.get(id).orElseThrow().role());
    }

    private String rawSecret(String id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT auth_secret FROM scm_provider WHERE id = ?")) {
            ps.setObject(1, UUID.fromString(id));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString("auth_secret");
            }
        }
    }
}
