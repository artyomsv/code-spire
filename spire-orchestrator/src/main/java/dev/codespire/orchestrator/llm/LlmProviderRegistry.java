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
                out.add(LlmProviderRows.toView(rs));
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
                return rs.next() ? Optional.of(LlmProviderRows.toView(rs)) : Optional.empty();
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
            LlmDefaultModel.lockDefaultPair(c);
            if (asked && in.defaultModel() == null && LlmProviderRows.hasEnabledDefault(c)) {
                throw new IllegalArgumentException("A default provider exists; send defaultModel with isDefault, so the"
                        + " default provider and its model change together.");
            }
            // First provider is auto-defaulted; else honor the flag. Only one default.
            boolean makeDefault = asked || LlmProviderRows.isEmpty(c);
            if (makeDefault) {
                LlmProviderRows.clearDefault(c);
                replaceDefaultModel(c, asked ? in.defaultModel() : null);
            }
            LlmProviderRows.insert(c, new LlmProviderRows.NewRow(id, in,
                    encryption.encryptString(apiKey, LlmProviderRows.aad(id)), makeDefault));
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
            LlmDefaultModel.lockDefaultPair(c);
            Optional<String> storedType = LlmProviderRows.typeOf(c, id);
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
                ps.setDouble(3, LlmProviderRows.temperatureOf(in));
                LlmProviderRows.setIntOrNull(ps, 4, in.maxTokens());
                ps.setBoolean(5, in.enabled() == null || in.enabled());
                int idx = 6;
                if (rotateKey) {
                    ps.setString(idx++, encryption.encryptString(in.apiKey(), LlmProviderRows.aad(id)));
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
        LlmProviderRows.clearDefault(c);
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
                return rs.next() ? Optional.of(LlmProviderRows.toView(rs)) : Optional.empty();
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
            LlmDefaultModel.lockDefaultPair(c);
            boolean wasDefault = LlmProviderRows.lockIsDefault(c, id);
            List<String> naming = LlmProviderRows.repositoriesNaming(c, id);
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

    /** A provider that repositories review with; switch it off instead, or choose another for them first. */
    public static final class ProviderInUse extends RuntimeException {
        public ProviderInUse(List<String> repositories) {
            super("This LLM provider is used by repositories: " + String.join(", ", repositories)
                    + ". Choose another provider in their settings first, or switch this one off.");
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

    /** The enabled default provider, key decrypted, with the default review model (null when none is chosen). */
    public record ResolvedDefault(LlmProviderConfig provider, String model) {
    }

    /**
     * The default provider and the default review model in one statement, so a review never pairs one committed
     * default's provider with another's model. A database fault throws, so the review is redelivered rather than
     * refused for a hiccup.
     */
    public Optional<ResolvedDefault> resolveDefaultWithModel() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT p.*, s.value AS default_model FROM llm_provider p"
                     + " LEFT JOIN app_setting s ON s.key = ? WHERE p.is_default = TRUE AND p.enabled = TRUE")) {
            ps.setString(1, LlmDefaultModel.KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ResolvedDefault(LlmProviderRows.decrypted(rs, encryption), rs.getString("default_model")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve the default LLM provider and model", e);
        }
    }

    /** The global default, enabled provider with its key decrypted; empty when none is set. */
    public Optional<LlmProviderConfig> resolveDefault() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM llm_provider WHERE is_default = TRUE AND enabled = TRUE")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(LlmProviderRows.decrypted(rs, encryption)) : Optional.empty();
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
                return rs.next() ? Optional.of(LlmProviderRows.decrypted(rs, encryption)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve LLM provider " + id, e);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("LLM provider '" + field + "' is required");
        }
        return value;
    }
}
