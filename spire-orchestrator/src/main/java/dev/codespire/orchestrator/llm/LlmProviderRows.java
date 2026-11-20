package dev.codespire.orchestrator.llm;

import dev.codespire.encryption.EncryptionService;

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
 * The {@code llm_provider} row's SQL and mapping, for {@link LlmProviderRegistry}: each helper works on the caller's
 * connection, inside the caller's transaction. Moved out so the registry keeps the decisions and this the statements.
 */
final class LlmProviderRows {

    private static final double DEFAULT_TEMPERATURE = 0.2;

    private LlmProviderRows() {
    }

    /** A new row: its id, what the caller sent, the key already encrypted, and whether it is the default. */
    record NewRow(UUID id, LlmProviderInput in, String encryptedKey, boolean isDefault) {
    }

    static void insert(Connection c, NewRow row) throws SQLException {
        LlmProviderInput in = row.in();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO llm_provider (id, name, type, base_url, api_key, temperature,
                        max_tokens, enabled, is_default)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, row.id());
            ps.setString(2, in.name());
            ps.setString(3, in.type());
            ps.setString(4, in.baseUrl());
            ps.setString(5, row.encryptedKey());
            ps.setDouble(6, temperatureOf(in));
            setIntOrNull(ps, 7, in.maxTokens());
            ps.setBoolean(8, in.enabled() == null || in.enabled());
            ps.setBoolean(9, row.isDefault());
            ps.executeUpdate();
        }
    }

    static LlmProviderConfig decrypted(ResultSet rs, EncryptionService encryption) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new LlmProviderConfig(id, rs.getString("name"), rs.getString("type"),
                rs.getString("base_url"),
                encryption.decryptString(rs.getString("api_key"), aad(id)),
                rs.getDouble("temperature"), intOrNull(rs, "max_tokens"),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"));
    }

    static LlmProviderView toView(ResultSet rs) throws SQLException {
        String key = rs.getString("api_key");
        return new LlmProviderView(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("name"), rs.getString("type"), rs.getString("base_url"),
                rs.getDouble("temperature"), intOrNull(rs, "max_tokens"),
                key != null && !key.isBlank(),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_check_at") == null
                        ? null : rs.getTimestamp("last_check_at").toInstant(),
                rs.getObject("last_check_ok", Boolean.class),
                rs.getString("last_check_error"));
    }

    static void clearDefault(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE llm_provider SET is_default = FALSE, updated_at = now() WHERE is_default = TRUE")) {
            ps.executeUpdate();
        }
    }

    static Optional<String> typeOf(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT type FROM llm_provider WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    static boolean hasEnabledDefault(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM llm_provider WHERE is_default = TRUE AND enabled = TRUE");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    static boolean isEmpty(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM llm_provider LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return !rs.next();
        }
    }

    /** Lock the row (see {@link LlmProviderRegistry#delete}) and read whether it is the default; false when absent. */
    static boolean lockIsDefault(Connection c, UUID id) throws SQLException {
        try (PreparedStatement lock = c.prepareStatement("SELECT is_default FROM llm_provider WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, id);
            try (ResultSet rs = lock.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    static List<String> repositoriesNaming(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT workspace || '/' || slug FROM repository"
                + " WHERE review_llm_provider_id = ? ORDER BY workspace, slug")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    static double temperatureOf(LlmProviderInput in) {
        return in.temperature() == null ? DEFAULT_TEMPERATURE : in.temperature();
    }

    static void setIntOrNull(PreparedStatement ps, int idx, Integer value) throws SQLException {
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

    static String aad(UUID id) {
        return "llm-provider:" + id;
    }
}
