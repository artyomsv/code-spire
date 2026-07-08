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
 * The context-provider registry (CONTRACT §7) — CRUD over {@code context_provider},
 * the single owner of context-source secrets. Secrets are Tink-encrypted at rest
 * (AAD bound to the row id) and are NEVER returned by the API — only resolved
 * internally to broker a credential to the worker. Mirrors
 * {@link dev.codespire.orchestrator.llm.LlmProviderRegistry}.
 */
@ApplicationScoped
public class ContextProviderRegistry {

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    // ---- reads (API) -------------------------------------------------------

    public List<ContextProviderView> list() {
        List<ContextProviderView> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM context_provider ORDER BY created_at");
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
             PreparedStatement ps = c.prepareStatement("SELECT * FROM context_provider WHERE id = ?")) {
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
        String secret = require(in.secret(), "secret");
        try (Connection c = dataSource.getConnection()) {
            // First provider is auto-defaulted; else honor the flag. Only one default.
            boolean makeDefault = Boolean.TRUE.equals(in.isDefault()) || isEmpty(c);
            if (makeDefault) {
                clearDefault(c);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO context_provider (id, name, type, base_url, auth_kind, auth_username,
                            auth_secret, project_keys, enabled, is_default)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setObject(1, id);
                ps.setString(2, in.name());
                ps.setString(3, in.type());
                ps.setString(4, in.baseUrl());
                ps.setString(5, in.authKind());
                ps.setString(6, in.username());
                ps.setString(7, encryption.encryptString(secret, aad(id)));
                ps.setString(8, blankToNull(in.projectKeys()));
                ps.setBoolean(9, in.enabled() == null || in.enabled());
                ps.setBoolean(10, makeDefault);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create context provider", e);
        }
        return get(id).orElseThrow();
    }

    /** Update everything except the default flag (managed via {@link #setDefault}). */
    @Transactional
    public Optional<ContextProviderView> update(UUID id, ContextProviderInput in) {
        try (Connection c = dataSource.getConnection()) {
            if (!exists(c, id)) {
                return Optional.empty();
            }
            boolean rotateSecret = in.secret() != null && !in.secret().isBlank();
            String sql = "UPDATE context_provider SET name=?, type=?, base_url=?, auth_kind=?, "
                    + "auth_username=?, project_keys=?, enabled=?, updated_at=now()"
                    + (rotateSecret ? ", auth_secret=?" : "") + " WHERE id=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, in.name());
                ps.setString(2, in.type());
                ps.setString(3, in.baseUrl());
                ps.setString(4, in.authKind());
                ps.setString(5, in.username());
                ps.setString(6, blankToNull(in.projectKeys()));
                ps.setBoolean(7, in.enabled() == null || in.enabled());
                int idx = 8;
                if (rotateSecret) {
                    ps.setString(idx++, encryption.encryptString(in.secret(), aad(id)));
                }
                ps.setObject(idx, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update context provider " + id, e);
        }
        return get(id);
    }

    @Transactional
    public Optional<ContextProviderView> setDefault(UUID id) {
        try (Connection c = dataSource.getConnection()) {
            if (!exists(c, id)) {
                return Optional.empty();
            }
            clearDefault(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE context_provider SET is_default = TRUE, updated_at = now() WHERE id = ?")) {
                ps.setObject(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set default context provider " + id, e);
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

    // ---- resolution (internal — carries the decrypted secret) --------------

    /** The global default, enabled provider with its secret decrypted; empty when none is set. */
    public Optional<ContextProviderConfig> resolveDefault() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM context_provider WHERE is_default = TRUE AND enabled = TRUE")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(decrypted(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve the default context provider", e);
        }
    }

    /** A single provider by id with its secret decrypted (for the connectivity check); empty when absent. */
    public Optional<ContextProviderConfig> resolveById(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM context_provider WHERE id = ?")) {
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
                rs.getString("base_url"), rs.getString("auth_kind"), rs.getString("auth_username"),
                encryption.decryptString(rs.getString("auth_secret"), aad(id)),
                rs.getString("project_keys"),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"));
    }

    private ContextProviderView toView(ResultSet rs) throws SQLException {
        String secret = rs.getString("auth_secret");
        return new ContextProviderView(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("name"), rs.getString("type"), rs.getString("base_url"),
                rs.getString("auth_kind"), rs.getString("auth_username"), rs.getString("project_keys"),
                secret != null && !secret.isBlank(),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"),
                rs.getTimestamp("created_at").toInstant());
    }

    private void clearDefault(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE context_provider SET is_default = FALSE, updated_at = now() WHERE is_default = TRUE")) {
            ps.executeUpdate();
        }
    }

    private boolean exists(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM context_provider WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean isEmpty(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM context_provider LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return !rs.next();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String aad(UUID id) {
        return "context-provider:" + id;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Context provider '" + field + "' is required");
        }
        return value;
    }
}
