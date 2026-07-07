package dev.codespire.orchestrator.settings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Key/value store for runtime-mutable application settings ({@code app_setting}) —
 * the counterpart to boot-time SPIRE_* config. A missing key reads as empty so
 * callers fall back to their own default; writes upsert. Hand-rolled JDBC in the
 * same style as {@code ProviderRegistry}.
 */
@ApplicationScoped
public class AppSettingRepository {

    private static final Logger LOG = Logger.getLogger(AppSettingRepository.class);

    @Inject
    DataSource dataSource;

    /** The stored value for a key, or empty when unset (the caller falls back to its default). */
    public Optional<String> get(String key) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM app_setting WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString("value")) : Optional.empty();
            }
        } catch (SQLException e) {
            // A settings read must never break a caller (e.g. the review pipeline) —
            // fall back to empty so the caller uses its default.
            LOG.warnf(e, "app_setting read failed for '%s'", key);
            return Optional.empty();
        }
    }

    @Transactional
    public void set(String key, String value) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?, now())
                     ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                     """)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("app_setting write failed for '" + key + "'", e);
        }
    }
}
