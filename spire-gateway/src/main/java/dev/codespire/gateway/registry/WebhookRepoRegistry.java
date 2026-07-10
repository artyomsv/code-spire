package dev.codespire.gateway.registry;

import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The gateway-owned webhook registry — CRUD over its own {@code webhook_repo} table
 * plus the hot-path {@link #findByKey} the edge uses to verify inbound signatures.
 * Registrations are scoped to a single repo or a whole org (see {@code scope}).
 * Secrets are Tink-encrypted at rest under the dedicated webhook keyset (AAD bound to
 * the row id) and are NEVER returned by the API — only the non-secret routing
 * {@code webhook_key} is. The gateway never reads the orchestrator's schema.
 */
@ApplicationScoped
public class WebhookRepoRegistry {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption; // webhook keyset only — see GatewayEncryptionProducer

    /** A resolved registration carrying the DECRYPTED secret — for edge verification. */
    public record Resolved(String providerType, String scope, String target, String secret) {
    }

    /** Hot path: resolve an enabled registration by its routing key, decrypting the secret. */
    public Optional<Resolved> findByKey(String webhookKey) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, provider_type, scope, target, webhook_secret "
                             + "FROM webhook_repo WHERE webhook_key = ? AND enabled = TRUE")) {
            ps.setString(1, webhookKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID id = rs.getObject("id", UUID.class);
                String secret = encryption.decryptString(rs.getString("webhook_secret"), aad(id));
                return Optional.of(new Resolved(
                        rs.getString("provider_type"), rs.getString("scope"), rs.getString("target"), secret));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve webhook repo", e);
        }
    }

    // ---- CRUD (API) --------------------------------------------------------

    public List<WebhookRepoView> list() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM webhook_repo ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            List<WebhookRepoView> out = new ArrayList<>();
            while (rs.next()) {
                out.add(toView(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list webhook repos", e);
        }
    }

    public Optional<WebhookRepoView> get(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM webhook_repo WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toView(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load webhook repo " + id, e);
        }
    }

    @Transactional
    public WebhookRepoView create(WebhookRepoInput in) {
        UUID id = UUID.randomUUID();
        String secret = require(in.secret(), "secret");
        String key = newWebhookKey();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO webhook_repo (id, provider_type, scope, target, webhook_key, webhook_secret, enabled)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, id);
            ps.setString(2, in.providerType());
            ps.setString(3, in.scope());
            ps.setString(4, in.target().trim());
            ps.setString(5, key);
            ps.setString(6, encryption.encryptString(secret, aad(id)));
            ps.setBoolean(7, in.enabled() == null || in.enabled());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create webhook repo", e);
        }
        return get(id).orElseThrow();
    }

    @Transactional
    public Optional<WebhookRepoView> update(UUID id, WebhookRepoInput in) {
        try (Connection c = dataSource.getConnection()) {
            if (!exists(c, id)) {
                return Optional.empty();
            }
            boolean rotateSecret = in.secret() != null && !in.secret().isBlank();
            String sql = "UPDATE webhook_repo SET provider_type=?, scope=?, target=?, enabled=?, updated_at=now()"
                    + (rotateSecret ? ", webhook_secret=?" : "") + " WHERE id=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, in.providerType());
                ps.setString(2, in.scope());
                ps.setString(3, in.target().trim());
                ps.setBoolean(4, in.enabled() == null || in.enabled());
                int idx = 5;
                if (rotateSecret) {
                    ps.setString(idx++, encryption.encryptString(in.secret(), aad(id)));
                }
                ps.setObject(idx, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update webhook repo " + id, e);
        }
        return get(id);
    }

    @Transactional
    public boolean delete(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM webhook_repo WHERE id = ?")) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete webhook repo " + id, e);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private WebhookRepoView toView(ResultSet rs) throws SQLException {
        String secret = rs.getString("webhook_secret");
        return new WebhookRepoView(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("provider_type"),
                rs.getString("scope"),
                rs.getString("target"),
                rs.getString("webhook_key"),
                secret != null && !secret.isBlank(),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant());
    }

    private boolean exists(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM webhook_repo WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** A 32-char URL-safe random routing token — unguessable, distinct from the row id so it can be rotated. */
    private static String newWebhookKey() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return KEY_ENCODER.encodeToString(buf);
    }

    private static String aad(UUID id) {
        return "webhook:" + id;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Webhook repo '" + field + "' is required");
        }
        return value;
    }
}
