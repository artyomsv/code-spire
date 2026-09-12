package dev.codespire.orchestrator.context;

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
import java.util.UUID;

/**
 * Sources own configuration and reference the account that owns their credential.
 */
@ApplicationScoped
public class ContextProviderRegistry {

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    /** A panel row derives from last_check_ok, so recording one is exactly when to re-push. */
    @Inject
    dev.codespire.orchestrator.attention.AttentionBroadcaster attention;

    private static final String VIEW = "SELECT s.*, a.name AS account_name, a.enabled AS account_enabled "
            + "FROM context_provider s LEFT JOIN scm_provider a ON a.id = s.account_id ";
    private static final String RESOLVED = "SELECT s.*, a.type AS account_type, "
            + "a.auth_kind AS account_auth_kind, a.auth_username AS account_auth_username, "
            + "a.auth_secret AS account_auth_secret FROM context_provider s "
            + "JOIN scm_provider a ON a.id = s.account_id ";

    // ---- reads (API) -------------------------------------------------------

    public List<ContextProviderView> list() {
        List<ContextProviderView> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(VIEW + "ORDER BY s.created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(toView(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list context providers", e);
        }
    }

    public Optional<ContextProviderView> get(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(VIEW + "WHERE s.id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toView(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load context provider " + id, e);
        }
    }

    // ---- writes (API) ------------------------------------------------------

    @Transactional
    public ContextProviderView create(ContextProviderInput in) {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            validateAccount(c, in);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO context_provider (id, name, type, base_url, account_id, project_keys, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
            // No default concept for context: every enabled provider participates, matched per reference.
            ps.setObject(1, id);
            ps.setString(2, in.name());
            ps.setString(3, in.type());
            ps.setString(4, in.baseUrl());
            ps.setObject(5, UUID.fromString(in.accountId()));
            ps.setString(6, blankToNull(in.projectKeys()));
            ps.setBoolean(7, in.enabled() == null || in.enabled());
            ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create context provider", e);
        }
        return get(id).orElseThrow();
    }

    @Transactional
    public Optional<ContextProviderView> update(UUID id, ContextProviderInput in) {
        try (Connection c = dataSource.getConnection()) {
            if (!exists(c, id)) {
                return Optional.empty();
            }
            validateAccount(c, in);
            String sql = "UPDATE context_provider SET name=?, type=?, base_url=?, account_id=?, "
                    + "project_keys=?, enabled=?, auth_kind=NULL, auth_username=NULL, auth_secret=NULL, "
                    + "last_check_at=NULL, last_check_ok=NULL, last_check_error=NULL, updated_at=now() WHERE id=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, in.name());
                ps.setString(2, in.type());
                ps.setString(3, in.baseUrl());
                ps.setObject(4, UUID.fromString(in.accountId()));
                ps.setString(5, blankToNull(in.projectKeys()));
                ps.setBoolean(6, in.enabled() == null || in.enabled());
                ps.setObject(7, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update context provider " + id, e);
        }
        return get(id);
    }

    @Transactional
    public boolean delete(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM context_provider WHERE id = ?")) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete context provider " + id, e);
        }
    }

    /**
     * Record the outcome of verifying this provider's credential. A passing check nulls the
     * stored error, so a stale message never outlives the failure it described.
     *
     * @param detail a safe, non-echoing reason on failure; null on success
     */
    @Transactional
    public void recordCheck(UUID id, boolean ok, String detail) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE context_provider SET last_check_at = now(), last_check_ok = ?, "
                             + "last_check_error = ? WHERE id = ?")) {
            ps.setBoolean(1, ok);
            ps.setString(2, ok ? null : detail);
            ps.setObject(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record the credential check for " + id, e);
        }
        attention.refresh();
    }

    // ---- resolution (internal — carries the decrypted secret) --------------

    /**
     * Every enabled provider with its secret decrypted, oldest first — the set brokered to the worker so a
     * PR's references can be matched against ALL registered sources (no single "default"; a review can pull
     * from Jira and Confluence at once).
     */
    public List<ContextProviderConfig> resolveAllEnabled() {
        List<ContextProviderConfig> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     RESOLVED + "WHERE s.enabled = TRUE AND a.enabled = TRUE ORDER BY s.created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(decrypted(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve enabled context providers", e);
        }
    }

    /** A single provider by id with its secret decrypted (for the connectivity check); empty when absent. */
    public Optional<ContextProviderConfig> resolveById(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(RESOLVED + "WHERE s.id = ? AND a.enabled = TRUE")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(decrypted(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve context provider " + id, e);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private ContextProviderConfig decrypted(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new ContextProviderConfig(id, rs.getString("name"), rs.getString("type"),
                rs.getString("account_type"), rs.getString("base_url"), rs.getString("account_auth_kind"), rs.getString("account_auth_username"),
                encryption.decryptString(rs.getString("account_auth_secret"), "provider:" + rs.getObject("account_id", UUID.class)),
                rs.getString("project_keys"),
                rs.getBoolean("enabled"));
    }

    private ContextProviderView toView(ResultSet rs) throws SQLException {
        return new ContextProviderView(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("name"), rs.getString("type"), rs.getString("base_url"),
                rs.getString("account_id"), rs.getString("account_name"), rs.getObject("account_enabled", Boolean.class),
                rs.getString("project_keys"), rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_check_at") == null
                        ? null : rs.getTimestamp("last_check_at").toInstant(),
                rs.getObject("last_check_ok", Boolean.class),
                rs.getString("last_check_error"));
    }

    private boolean exists(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM context_provider WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateAccount(Connection c, ContextProviderInput in) throws SQLException {
        UUID account;
        try {
            account = UUID.fromString(in.accountId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new jakarta.ws.rs.BadRequestException("A valid accountId is required");
        }
        // Keep the referenced kind/host stable until this source write commits.
        try (var ps = c.prepareStatement("SELECT type, base_url, auth_kind FROM scm_provider WHERE id = ? FOR SHARE")) {
            ps.setObject(1, account);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) throw new jakarta.ws.rs.BadRequestException("Selected account does not exist");
                if (!dev.codespire.orchestrator.provider.ProviderClients.supportsContext(in.type(), rs.getString("type"))) {
                    throw new jakarta.ws.rs.BadRequestException("Selected account kind cannot serve this source type");
                }
                if (!dev.codespire.orchestrator.provider.ProviderClients.supportsContextAuth(in.type(), rs.getString("auth_kind"))) {
                    throw new jakarta.ws.rs.BadRequestException("Selected account authentication cannot serve this source type");
                }
                if (!sameOrigin(in.baseUrl(), rs.getString("base_url"))) {
                    throw new jakarta.ws.rs.BadRequestException("Source URL must use the selected account's origin");
                }
            }
        }
    }

    public static boolean sameOrigin(String first, String second) {
        java.net.URI a = java.net.URI.create(first);
        java.net.URI b = java.net.URI.create(second);
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost()) && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(java.net.URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }
}
