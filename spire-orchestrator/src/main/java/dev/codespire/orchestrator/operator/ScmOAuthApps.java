package dev.codespire.orchestrator.operator;

import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.OAuthApp;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The OAuth applications operators sign into, one per platform.
 *
 * <p>The client secret is Tink-encrypted with the platform name as AAD, like every other credential
 * this deployment holds, and is never returned by any API — {@link View} reports only whether one is
 * set. Same rule as the provider registry, for the same reason: a secret that a read can return is a
 * secret an over-broad read can leak.
 */
@ApplicationScoped
public class ScmOAuthApps {

    /** What a read returns: everything an admin needs to check the setup, and no secret. */
    public record View(String providerType, String webBaseUrl, String apiBaseUrl, String clientId,
                       boolean hasSecret) {
    }

    /** What a write accepts. A blank secret on an update KEEPS the stored one. */
    public record Input(String providerType, String webBaseUrl, String apiBaseUrl, String clientId,
                        String clientSecret) {
    }

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    public List<View> list() {
        String sql = """
                SELECT provider_type, web_base_url, api_base_url, client_id, client_secret
                  FROM scm_oauth_app ORDER BY provider_type
                """;
        List<View> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new View(rs.getString("provider_type"), rs.getString("web_base_url"),
                        rs.getString("api_base_url"), rs.getString("client_id"),
                        rs.getString("client_secret") != null));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list OAuth apps", e);
        }
        return out;
    }

    /**
     * The app for a platform, decrypted and ready to use.
     *
     * <p>Empty when nothing is configured, which every caller must treat as "this platform cannot be
     * connected" rather than falling back to some default: there is no default, and inventing one
     * would send an operator's browser to a sign-in that refuses them with a stranger's client id.
     */
    public Optional<OAuthApp> resolve(ScmType type) {
        String sql = """
                SELECT web_base_url, api_base_url, client_id, client_secret
                  FROM scm_oauth_app WHERE provider_type = ?
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, type.providerType());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OAuthApp(type, rs.getString("web_base_url"),
                        rs.getString("api_base_url"), rs.getString("client_id"),
                        encryption.decryptString(rs.getString("client_secret"), aad(type.providerType()))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load the OAuth app for " + type.providerType(), e);
        }
    }

    /**
     * Creates or replaces the app for one platform.
     *
     * <p>A blank secret keeps the stored one. Without that rule an admin correcting a base URL would
     * silently wipe the credential — the same trap the provider settings form already documents,
     * where sending an empty string erased a working token.
     */
    @Transactional
    public View save(Input in) {
        // Resolved BEFORE the statement rather than with a COALESCE over EXCLUDED: Postgres checks
        // NOT NULL while forming the tuple, so the conflict clause never gets to substitute.
        String secret = in.clientSecret() == null || in.clientSecret().isBlank()
                ? storedSecret(in.providerType())
                : encryption.encryptString(in.clientSecret(), aad(in.providerType()));
        if (secret == null) {
            // Enforced here as well as at the resource, deliberately: this is the invariant's own
            // boundary, and a second caller reaching the registry directly would otherwise create an
            // application that can only ever fail at the platform. ADR-023 learned this the
            // expensive way, when a rule lived only on the one REST path that happened to exist.
            throw new IllegalArgumentException(
                    "A first sign-in application for " + in.providerType() + " needs a client secret.");
        }
        String sql = """
                INSERT INTO scm_oauth_app (provider_type, web_base_url, api_base_url, client_id,
                                           client_secret, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (provider_type) DO UPDATE SET
                    web_base_url = EXCLUDED.web_base_url,
                    api_base_url = EXCLUDED.api_base_url,
                    client_id    = EXCLUDED.client_id,
                    client_secret = EXCLUDED.client_secret,
                    updated_at   = now()
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, in.providerType());
            ps.setString(2, blankToNull(in.webBaseUrl()));
            ps.setString(3, blankToNull(in.apiBaseUrl()));
            ps.setString(4, in.clientId().trim());
            ps.setString(5, secret);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save the OAuth app for " + in.providerType(), e);
        }
        return list().stream()
                .filter(v -> v.providerType().equals(in.providerType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("saved app vanished: " + in.providerType()));
    }

    @Transactional
    public boolean delete(String providerType) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM scm_oauth_app WHERE provider_type = ?")) {
            ps.setString(1, providerType);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete the OAuth app for " + providerType, e);
        }
    }

    /** The stored ciphertext, still encrypted — a keep never decrypts and re-encrypts for nothing. */
    private String storedSecret(String providerType) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT client_secret FROM scm_oauth_app WHERE provider_type = ?")) {
            ps.setString(1, providerType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the stored OAuth secret for " + providerType, e);
        }
    }

    /** Binds ciphertext to its platform, so a row copied to another platform will not decrypt. */
    private static String aad(String providerType) {
        return "scm-oauth-app:" + providerType;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
