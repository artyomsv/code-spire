package dev.codespire.orchestrator.llm;

import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The LLM provider registry (ADR-018) — CRUD over {@code llm_provider}, the single
 * owner of LLM API keys. Keys are Tink-encrypted at rest (AAD bound to the row id)
 * and are NEVER returned by the API — only resolved internally to broker a
 * credential to the worker. Mirrors {@link dev.codespire.orchestrator.provider.ProviderRegistry}.
 */
@ApplicationScoped
public class LlmProviderRegistry {

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    // ---- reads (API) -------------------------------------------------------

    public List<LlmProviderView> list() {
        List<LlmProviderView> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_provider ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(toView(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list LLM providers", e);
        }
    }

    public Optional<LlmProviderView> get(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_provider WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toView(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load LLM provider " + id, e);
        }
    }

    // ---- writes (API) ------------------------------------------------------

    @Transactional
    public LlmProviderView create(LlmProviderInput in) {
        UUID id = UUID.randomUUID();
        String apiKey = require(in.apiKey(), "apiKey");
        try (Connection c = dataSource.getConnection()) {
            // First provider is auto-defaulted; else honor the flag. Only one default.
            boolean makeDefault = Boolean.TRUE.equals(in.isDefault()) || isEmpty(c);
            if (makeDefault) {
                clearDefault(c);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO llm_provider (id, name, type, base_url, api_key, model, temperature,
                            max_tokens, enabled, is_default)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setObject(1, id);
                ps.setString(2, in.name());
                ps.setString(3, in.type());
                ps.setString(4, in.baseUrl());
                ps.setString(5, encryption.encryptString(apiKey, aad(id)));
                ps.setString(6, in.model());
                ps.setDouble(7, temperatureOf(in));
                setIntOrNull(ps, 8, in.maxTokens());
                ps.setBoolean(9, in.enabled() == null || in.enabled());
                ps.setBoolean(10, makeDefault);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create LLM provider", e);
        }
        return get(id).orElseThrow();
    }

    /** Update everything except the default flag (managed via {@link #setDefault}). */
    @Transactional
    public Optional<LlmProviderView> update(UUID id, LlmProviderInput in) {
        try (Connection c = dataSource.getConnection()) {
            if (!exists(c, id)) {
                return Optional.empty();
            }
            boolean rotateKey = in.apiKey() != null && !in.apiKey().isBlank();
            String sql = "UPDATE llm_provider SET name=?, type=?, base_url=?, model=?, temperature=?, "
                    + "max_tokens=?, enabled=?, updated_at=now()"
                    + (rotateKey ? ", api_key=?" : "") + " WHERE id=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, in.name());
                ps.setString(2, in.type());
                ps.setString(3, in.baseUrl());
                ps.setString(4, in.model());
                ps.setDouble(5, temperatureOf(in));
                setIntOrNull(ps, 6, in.maxTokens());
                ps.setBoolean(7, in.enabled() == null || in.enabled());
                int idx = 8;
                if (rotateKey) {
                    ps.setString(idx++, encryption.encryptString(in.apiKey(), aad(id)));
                }
                ps.setObject(idx, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update LLM provider " + id, e);
        }
        return get(id);
    }

    @Transactional
    public Optional<LlmProviderView> setDefault(UUID id) {
        try (Connection c = dataSource.getConnection()) {
            if (!exists(c, id)) {
                return Optional.empty();
            }
            clearDefault(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE llm_provider SET is_default = TRUE, updated_at = now() WHERE id = ?")) {
                ps.setObject(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set default LLM provider " + id, e);
        }
        return get(id);
    }

    @Transactional
    public boolean delete(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM llm_provider WHERE id = ?")) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete LLM provider " + id, e);
        }
    }

    // ---- resolution (internal — carries the decrypted key) -----------------

    /** The global default, enabled provider with its key decrypted; empty when none is set. */
    public Optional<LlmProviderConfig> resolveDefault() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM llm_provider WHERE is_default = TRUE AND enabled = TRUE")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(decrypted(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve the default LLM provider", e);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private LlmProviderConfig decrypted(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new LlmProviderConfig(id, rs.getString("name"), rs.getString("type"),
                rs.getString("base_url"),
                encryption.decryptString(rs.getString("api_key"), aad(id)),
                rs.getString("model"), rs.getDouble("temperature"), intOrNull(rs, "max_tokens"),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"));
    }

    private LlmProviderView toView(ResultSet rs) throws SQLException {
        String key = rs.getString("api_key");
        return new LlmProviderView(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("name"), rs.getString("type"), rs.getString("base_url"),
                rs.getString("model"), rs.getDouble("temperature"), intOrNull(rs, "max_tokens"),
                key != null && !key.isBlank(),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"),
                rs.getTimestamp("created_at").toInstant());
    }

    private void clearDefault(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE llm_provider SET is_default = FALSE, updated_at = now() WHERE is_default = TRUE")) {
            ps.executeUpdate();
        }
    }

    private boolean exists(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM llm_provider WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean isEmpty(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM llm_provider LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return !rs.next();
        }
    }

    private static double temperatureOf(LlmProviderInput in) {
        return in.temperature() == null ? 0.2 : in.temperature();
    }

    private static void setIntOrNull(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.INTEGER);
        } else {
            ps.setInt(idx, value);
        }
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static String aad(UUID id) {
        return "llm-provider:" + id;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("LLM provider '" + field + "' is required");
        }
        return value;
    }
}
