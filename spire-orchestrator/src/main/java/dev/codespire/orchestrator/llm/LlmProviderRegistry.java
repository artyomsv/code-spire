package dev.codespire.orchestrator.llm;

import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.settings.AppSettingRepository;
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

    /** A panel row derives from last_check_ok, so recording one is exactly when to re-push. */
    @Inject
    dev.codespire.orchestrator.attention.AttentionBroadcaster attention;

    /** The default model lives beside the default provider; a default change writes both on one connection. */
    @Inject
    AppSettingRepository appSettings;

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

    /**
     * Register a provider: a connection, with no model. The first provider becomes the default provider, and the
     * operator then picks its model. A provider created with {@code isDefault} replaces the default in the same
     * transaction: with {@code defaultModel} the model is written beside it (the caller has checked the choice),
     * without one the old default's model is cleared, so no review spends a model chosen for another provider.
     *
     * @throws IllegalArgumentException when {@code isDefault} would replace an enabled default and no
     *         {@code defaultModel} is given
     */
    @Transactional
    public LlmProviderView create(LlmProviderInput in) {
        UUID id = UUID.randomUUID();
        String apiKey = require(in.apiKey(), "apiKey");
        boolean asked = Boolean.TRUE.equals(in.isDefault());
        try (Connection c = dataSource.getConnection()) {
            if (asked && in.defaultModel() == null && hasEnabledDefault(c)) {
                throw new IllegalArgumentException("A default provider exists; send defaultModel with isDefault, so the"
                        + " default provider and its model change together.");
            }
            // First provider is auto-defaulted; else honor the flag. Only one default.
            boolean makeDefault = asked || isEmpty(c);
            if (makeDefault) {
                clearDefault(c);
                replaceDefaultModel(c, asked ? in.defaultModel() : null);
            }
            insert(c, id, in, apiKey, makeDefault);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create LLM provider", e);
        }
        attention.refresh();
        return get(id).orElseThrow();
    }

    private void replaceDefaultModel(Connection c, String model) throws SQLException {
        if (model == null) {
            appSettings.delete(c, LlmDefaultModel.KEY);
        } else {
            LlmDefaultModel.write(c, model);
        }
    }

    private void insert(Connection c, UUID id, LlmProviderInput in, String apiKey, boolean makeDefault)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO llm_provider (id, name, type, base_url, api_key, temperature,
                        max_tokens, enabled, is_default)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, id);
            ps.setString(2, in.name());
            ps.setString(3, in.type());
            ps.setString(4, in.baseUrl());
            ps.setString(5, encryption.encryptString(apiKey, aad(id)));
            ps.setDouble(6, temperatureOf(in));
            setIntOrNull(ps, 7, in.maxTokens());
            ps.setBoolean(8, in.enabled() == null || in.enabled());
            ps.setBoolean(9, makeDefault);
            ps.executeUpdate();
        }
    }

    /**
     * Update everything except the type and the default flag (the default changes through
     * {@link LlmDefaultModel#setDefaultProvider}, with its model).
     *
     * @throws IllegalArgumentException when the type would change: the models chosen for this provider are of its
     *         vendor, so another vendor is another provider
     */
    @Transactional
    public Optional<LlmProviderView> update(UUID id, LlmProviderInput in) {
        try (Connection c = dataSource.getConnection()) {
            Optional<String> storedType = typeOf(c, id);
            if (storedType.isEmpty()) {
                return Optional.empty();
            }
            if (!storedType.orElseThrow().equals(in.type())) {
                throw new IllegalArgumentException("Provider type cannot change; create a new provider for another vendor.");
            }
            boolean rotateKey = in.apiKey() != null && !in.apiKey().isBlank();
            String sql = "UPDATE llm_provider SET name=?, base_url=?, temperature=?, "
                    + "max_tokens=?, enabled=?, updated_at=now()"
                    + (rotateKey ? ", api_key=?" : "") + " WHERE id=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, in.name());
                ps.setString(2, in.baseUrl());
                ps.setDouble(3, temperatureOf(in));
                setIntOrNull(ps, 4, in.maxTokens());
                ps.setBoolean(5, in.enabled() == null || in.enabled());
                int idx = 6;
                if (rotateKey) {
                    ps.setString(idx++, encryption.encryptString(in.apiKey(), aad(id)));
                }
                ps.setObject(idx, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update LLM provider " + id, e);
        }
        attention.refresh();
        return get(id);
    }

    /**
     * Mark a provider the default on the caller's connection, inside the caller's transaction, which also writes the
     * default model ({@link LlmDefaultModel#setDefaultProvider}). Never on its own: a default provider without its
     * model would spend the previous default's model, of whatever vendor.
     */
    void setDefault(Connection c, UUID id) throws SQLException {
        clearDefault(c);
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE llm_provider SET is_default = TRUE, updated_at = now() WHERE id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    /** The enabled default provider, without its key; empty when none. "A default provider exists" reads this. */
    public Optional<LlmProviderView> enabledDefault() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM llm_provider WHERE is_default = TRUE AND enabled = TRUE")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toView(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the default LLM provider", e);
        }
    }

    /**
     * Delete a provider no repository reviews with.
     *
     * <p>The check and the delete share one transaction, and the provider row is locked first. A
     * repository save that names it takes a key-share lock on the same row for its foreign key, so the
     * two are ordered: a save committed before the lock is seen by the check and refused here, and a
     * later one waits and then finds the provider gone (a 400 there), never a key violation here.
     *
     * <p>Deleting the default provider clears the default model in the same transaction: the model was chosen for
     * this provider's vendor, and while it stays set the model-in-use guard would keep it from being deleted.
     *
     * @throws ProviderInUse when a repository names this provider; nothing is deleted
     */
    @Transactional
    public boolean delete(UUID id) {
        try (Connection c = dataSource.getConnection()) {
            boolean wasDefault = lockIsDefault(c, id);
            List<String> naming = repositoriesNaming(c, id);
            if (!naming.isEmpty()) {
                throw new ProviderInUse(naming);
            }
            boolean deleted;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM llm_provider WHERE id = ?")) {
                ps.setObject(1, id);
                deleted = ps.executeUpdate() > 0;
            }
            if (wasDefault) {
                appSettings.delete(c, LlmDefaultModel.KEY);
            }
            attention.refresh();
            return deleted;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete LLM provider " + id, e);
        }
    }

    /** Lock the row (see {@link #delete}) and read whether it is the default; false when there is no such row. */
    private static boolean lockIsDefault(Connection c, UUID id) throws SQLException {
        try (PreparedStatement lock = c.prepareStatement("SELECT is_default FROM llm_provider WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, id);
            try (ResultSet rs = lock.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** A provider that repositories review with; switch it off instead, or choose another for them first. */
    public static final class ProviderInUse extends RuntimeException {
        public ProviderInUse(List<String> repositories) {
            super("This LLM provider is used by repositories: " + String.join(", ", repositories)
                    + ". Choose another provider in their settings first, or switch this one off.");
        }
    }

    private static List<String> repositoriesNaming(Connection c, UUID id) throws SQLException {
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
                     "UPDATE llm_provider SET last_check_at = now(), last_check_ok = ?, "
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

    /** One provider with its key decrypted, for a re-check. Empty when unknown. */
    public Optional<LlmProviderConfig> resolveById(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM llm_provider WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(decrypted(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve LLM provider " + id, e);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private LlmProviderConfig decrypted(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new LlmProviderConfig(id, rs.getString("name"), rs.getString("type"),
                rs.getString("base_url"),
                encryption.decryptString(rs.getString("api_key"), aad(id)),
                rs.getDouble("temperature"), intOrNull(rs, "max_tokens"),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"));
    }

    private LlmProviderView toView(ResultSet rs) throws SQLException {
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

    private void clearDefault(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE llm_provider SET is_default = FALSE, updated_at = now() WHERE is_default = TRUE")) {
            ps.executeUpdate();
        }
    }

    private static Optional<String> typeOf(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT type FROM llm_provider WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private static boolean hasEnabledDefault(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM llm_provider WHERE is_default = TRUE AND enabled = TRUE");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
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
