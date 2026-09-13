package dev.codespire.orchestrator.context;

import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.provider.ProviderClients;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Moves legacy secrets after Flyway and CDI encryption initialization, one atomic row at a time. */
@ApplicationScoped
public class ContextCredentialReconciler {
    private static final Logger LOG = Logger.getLogger(ContextCredentialReconciler.class);
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;

    void start(@Observes StartupEvent event) {
        reconcile();
    }

    public int reconcile() {
        List<UUID> ids = new ArrayList<>();
        try (var c = dataSource.getConnection();
             var ps = c.prepareStatement("SELECT id FROM context_provider WHERE account_id IS NULL "
                     + "AND auth_secret IS NOT NULL ORDER BY created_at, id");
             var rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getObject(1, UUID.class));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not list legacy context credentials", e);
        }
        int migrated = 0;
        for (UUID id : ids) {
            try {
                if (QuarkusTransaction.requiringNew().call(() -> migrate(id))) migrated++;
            } catch (Exception e) {
                // Never log the exception: crypto/SQL diagnostics may contain credential material.
                LOG.warnf("Context credential migration failed for row %s; retained for next startup", id);
            }
        }
        LOG.infof("Migrated %d context credential rows; %d require retry", migrated, ids.size() - migrated);
        return migrated;
    }

    private boolean migrate(UUID id) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            // Serialize concurrent startup reconcilers through commit, including duplicate lookup.
            try (var lock = c.prepareStatement("SELECT pg_advisory_xact_lock(148, 140)")) {
                lock.execute();
            }
            try (var ps = c.prepareStatement("SELECT * FROM context_provider WHERE id = ? "
                    + "AND account_id IS NULL AND auth_secret IS NOT NULL FOR UPDATE")) {
                ps.setObject(1, id);
                try (var source = ps.executeQuery()) {
                    if (!source.next()) return false;
                    String secret = encryption.decryptString(source.getString("auth_secret"), "context-provider:" + id);
                    String type = ProviderClients.legacyContextAccountType(source.getString("type"), source.getString("base_url"));
                    UUID account = matchingAccount(c, source, type, secret);
                    if (account == null) {
                        account = UUID.randomUUID();
                        try (var insert = c.prepareStatement("""
                                INSERT INTO scm_provider (id, name, type, base_url, workspace, role,
                                    auth_kind, auth_username, auth_secret)
                                VALUES (?, ?, ?, ?, NULL, 'CONTEXT', ?, ?, ?)
                                """)) {
                            insert.setObject(1, account);
                            String name = source.getString("name");
                            insert.setString(2, name.substring(0, Math.min(name.length(), 244)) + " (migrated)");
                            insert.setString(3, type);
                            insert.setString(4, source.getString("base_url"));
                            insert.setString(5, source.getString("auth_kind"));
                            insert.setString(6, source.getString("auth_username"));
                            insert.setString(7, encryption.encryptString(secret, "provider:" + account));
                            insert.executeUpdate();
                        }
                    }
                    try (var update = c.prepareStatement("UPDATE context_provider SET account_id = ?, "
                            + "auth_kind = NULL, auth_username = NULL, auth_secret = NULL WHERE id = ?")) {
                        update.setObject(1, account);
                        update.setObject(2, id);
                        update.executeUpdate();
                    }
                    return true;
                }
            }
        }
    }

    private UUID matchingAccount(Connection c, ResultSet source, String type, String secret) throws SQLException {
        try (var ps = c.prepareStatement("SELECT a.id,a.base_url,a.auth_kind,a.auth_username,a.auth_secret FROM scm_provider a WHERE a.type = ? "
                + "AND a.role = 'CONTEXT' AND a.enabled = TRUE "
                + "AND EXISTS (SELECT 1 FROM context_provider s WHERE s.account_id = a.id) ORDER BY a.created_at, a.id")) {
            ps.setString(1, type);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!origin(source.getString("base_url")).equals(origin(rs.getString("base_url")))
                            || !Objects.equals(source.getString("auth_kind"), rs.getString("auth_kind"))
                            || !Objects.equals(source.getString("auth_username"), rs.getString("auth_username"))) continue;
                    UUID candidate = rs.getObject("id", UUID.class);
                    try {
                        if (secret.equals(encryption.decryptString(rs.getString("auth_secret"), "provider:" + candidate))) {
                            return candidate;
                        }
                    } catch (RuntimeException e) {
                        // An unreadable candidate cannot prove equality. Preserve this source separately.
                    }
                }
            }
        }
        return null;
    }

    private static String origin(String url) {
        URI uri = URI.create(url);
        int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
