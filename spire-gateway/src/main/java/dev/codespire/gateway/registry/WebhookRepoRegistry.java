package dev.codespire.gateway.registry;

import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

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

    /** Both of this service's conditions derive from webhook_repo, so its writes are the push points. */
    @Inject
    dev.codespire.gateway.attention.WebhookAttentionBroadcaster attention;

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
    public WebhookRepoSecret create(WebhookRepoInput in) {
        UUID id = UUID.randomUUID();
        String secret = newSecret(); // minted server-side, returned once (never client-supplied)
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
        return new WebhookRepoSecret(get(id).orElseThrow(), secret);
    }

    @Transactional
    public Optional<WebhookRepoView> update(UUID id, WebhookRepoInput in) {
        // The secret is never touched here — it is rotated only via rotateSecret().
        try (Connection c = dataSource.getConnection()) {
            if (!exists(c, id)) {
                return Optional.empty();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE webhook_repo SET provider_type=?, scope=?, target=?, enabled=?, updated_at=now() "
                            + "WHERE id=?")) {
                ps.setString(1, in.providerType());
                ps.setString(2, in.scope());
                ps.setString(3, in.target().trim());
                ps.setBoolean(4, in.enabled() == null || in.enabled());
                ps.setObject(5, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update webhook repo " + id, e);
        }
        return get(id);
    }

    /** Mint a fresh secret for an existing registration and return it once. Empty if unknown. */
    @Transactional
    public Optional<WebhookRepoSecret> rotateSecret(UUID id) {
        String secret = newSecret();
        try (Connection c = dataSource.getConnection()) {
            if (!exists(c, id)) {
                return Optional.empty();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE webhook_repo SET webhook_secret=?, updated_at=now() WHERE id=?")) {
                ps.setString(1, encryption.encryptString(secret, aad(id)));
                ps.setObject(2, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to rotate webhook secret " + id, e);
        }
        return get(id).map(view -> new WebhookRepoSecret(view, secret));
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

    // ---- attention panel (rejection tracking) ------------------------------

    /** A registration whose deliveries are being refused, for the attention panel. */
    /**
     * A registration whose deliveries are being refused. Carries {@code providerType} because a
     * repo path alone cannot identify a registration: the same workspace name can be registered on
     * two different providers, so an operator told only {@code owner/repo} would not know which
     * provider's webhook settings to open.
     */
    public record Rejection(String id, String providerType, String target, String reason, int count) {
    }

    /** An enabled registration, identified the way an operator has to act on it. */
    public record Registration(String id, String providerType, String target) {
    }

    /**
     * Count one refused delivery against the registration this key resolves to. An unknown key
     * matches no row and is silently ignored — there is nothing to attach a counter to, which is
     * why a wrong URL stays a log-only condition.
     *
     * @param reason one of the closed neutral set; never an exception message
     */
    @Transactional
    public void recordRejection(String webhookKey, String reason) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE webhook_repo
                        SET rejection_count = rejection_count + 1,
                            last_rejection_reason = ?,
                            last_rejected_at = now()
                      WHERE webhook_key = ?
                     """)) {
            ps.setString(1, reason);
            ps.setString(2, webhookKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record a webhook rejection", e);
        }
        attention.refresh();
    }

    /**
     * A verified delivery landed, so this registration is healthy again. Guarded on a non-zero
     * count so the hot path does no write in the normal case.
     */
    @Transactional
    public void clearRejections(String webhookKey) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE webhook_repo
                        SET rejection_count = 0, last_rejection_reason = NULL, last_rejected_at = NULL
                      WHERE webhook_key = ? AND rejection_count > 0
                     """)) {
            ps.setString(1, webhookKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear webhook rejections", e);
        }
        attention.refresh();
    }

    /** Enabled registrations currently refusing deliveries. */
    public List<Rejection> rejecting() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, provider_type, target, last_rejection_reason, rejection_count FROM webhook_repo "
                             + "WHERE enabled = TRUE AND rejection_count > 0 ORDER BY target");
             ResultSet rs = ps.executeQuery()) {
            List<Rejection> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Rejection(rs.getObject("id", UUID.class).toString(),
                        rs.getString("provider_type"), rs.getString("target"),
                        rs.getString("last_rejection_reason"), rs.getInt("rejection_count")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list rejecting webhook repos", e);
        }
    }

    /** Enabled registrations with no shared secret — they can never verify a delivery. */
    public List<Registration> missingSecret() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, provider_type, target FROM webhook_repo WHERE enabled = TRUE "
                             + "AND (webhook_secret IS NULL OR webhook_secret = '') ORDER BY target");
             ResultSet rs = ps.executeQuery()) {
            List<Registration> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Registration(rs.getObject("id", UUID.class).toString(),
                        rs.getString("provider_type"), rs.getString("target")));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list webhook repos without a secret", e);
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

    /** A fresh 32-byte HMAC/token secret (base64url, unpadded), minted server-side and shown once. */
    private static String newSecret() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return KEY_ENCODER.encodeToString(buf);
    }

    private static String aad(UUID id) {
        return "webhook:" + id;
    }
}
