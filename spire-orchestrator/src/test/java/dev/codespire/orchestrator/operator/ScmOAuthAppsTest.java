package dev.codespire.orchestrator.operator;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.OAuthApp;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credential an operator's sign-in depends on (P4 / FR-11).
 *
 * <p>Two properties, and both are about what happens to a secret nobody retyped. It must survive an
 * edit that did not mention it, and it must never be readable back out of the API.
 */
@QuarkusTest
class ScmOAuthAppsTest {

    private static final String TYPE = "github";

    @Inject
    ScmOAuthApps apps;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM scm_oauth_app WHERE provider_type = '" + TYPE + "'");
    }

    @Test
    void resolvesNothingForAPlatformThatWasNeverSetUp() {
        assertEquals(Optional.empty(), apps.resolve(ScmType.GITHUB));
    }

    @Test
    void storesAndReturnsTheAppWithItsSecretDecrypted() {
        apps.save(new ScmOAuthApps.Input(TYPE, null, null, "TEST-CLIENT", "TEST-SECRET"));

        OAuthApp resolved = apps.resolve(ScmType.GITHUB).orElseThrow();
        assertEquals("TEST-CLIENT", resolved.clientId());
        assertEquals("TEST-SECRET", resolved.clientSecret());
    }

    /**
     * The defect this rule exists for: an admin correcting a base URL, without retyping a secret
     * they cannot see, would otherwise erase it. Nothing on the screen would change, and the failure
     * would surface later as every operator's sign-in being refused.
     */
    @Test
    void anEditThatOmitsTheSecretKeepsTheStoredOne() {
        apps.save(new ScmOAuthApps.Input(TYPE, null, null, "TEST-CLIENT", "TEST-SECRET"));

        apps.save(new ScmOAuthApps.Input(TYPE, "https://github.example.invalid", null, "TEST-CLIENT", ""));

        OAuthApp resolved = apps.resolve(ScmType.GITHUB).orElseThrow();
        assertEquals("TEST-SECRET", resolved.clientSecret());
        assertEquals("https://github.example.invalid", resolved.webBaseUrl());
    }

    @Test
    void replacesTheSecretWhenOneIsGiven() {
        apps.save(new ScmOAuthApps.Input(TYPE, null, null, "TEST-CLIENT", "TEST-SECRET"));

        apps.save(new ScmOAuthApps.Input(TYPE, null, null, "TEST-CLIENT", "TEST-SECRET-2"));

        assertEquals("TEST-SECRET-2", apps.resolve(ScmType.GITHUB).orElseThrow().clientSecret());
    }

    /** A read says whether a secret is set and never what it is — the provider registry's rule. */
    @Test
    void neverReturnsTheSecretThroughAListing() {
        apps.save(new ScmOAuthApps.Input(TYPE, null, null, "TEST-CLIENT", "TEST-SECRET"));

        ScmOAuthApps.View view = apps.list().stream()
                .filter(v -> v.providerType().equals(TYPE)).findFirst().orElseThrow();

        assertTrue(view.hasSecret());
        assertFalse(view.toString().contains("TEST-SECRET"),
                "a listing must carry no secret at all, not even in a field nobody renders");
    }

    /** Encrypted at rest like every other credential, so a database dump is not a credential dump. */
    @Test
    void storesTheSecretEncrypted() {
        apps.save(new ScmOAuthApps.Input(TYPE, null, null, "TEST-CLIENT", "TEST-SECRET"));

        assertFalse(storedSecret().contains("TEST-SECRET"));
    }

    @Test
    void deletingRemovesTheAppEntirely() {
        apps.save(new ScmOAuthApps.Input(TYPE, null, null, "TEST-CLIENT", "TEST-SECRET"));

        assertTrue(apps.delete(TYPE));
        assertEquals(Optional.empty(), apps.resolve(ScmType.GITHUB));
        assertFalse(apps.delete(TYPE), "a second delete has nothing to remove");
    }

    private String storedSecret() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT client_secret FROM scm_oauth_app WHERE provider_type = ?")) {
            ps.setString(1, TYPE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the stored secret", e);
        }
    }

    private void exec(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}
