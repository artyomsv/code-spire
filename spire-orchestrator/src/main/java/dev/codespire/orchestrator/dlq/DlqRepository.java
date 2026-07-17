package dev.codespire.orchestrator.dlq;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Hand-rolled JDBC over {@code dlq_entry} (V15) — persisted dead-letter records for inspection + replay. */
@ApplicationScoped
public class DlqRepository {

    @Inject
    DataSource dataSource;

    public void record(UUID id, String key, String type, String topic, String reason, String payload) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO dlq_entry (id, kafka_key, message_type, original_topic, reason, payload)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, id);
            ps.setString(2, key);
            ps.setString(3, type);
            ps.setString(4, topic);
            ps.setString(5, reason);
            ps.setString(6, payload);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record dead-letter entry " + id, e);
        }
    }

    public List<DlqEntry> list(boolean pendingOnly) {
        String sql = "SELECT * FROM dlq_entry"
                + (pendingOnly ? " WHERE status = 'pending'" : "")
                + " ORDER BY created_at DESC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<DlqEntry> out = new ArrayList<>();
            while (rs.next()) {
                out.add(toEntry(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list dead-letter entries", e);
        }
    }

    public Optional<DlqEntry> get(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM dlq_entry WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toEntry(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load dead-letter entry " + id, e);
        }
    }

    public boolean markReplayed(UUID id) {
        return updateStatus(id, "replayed");
    }

    public boolean markDiscarded(UUID id) {
        return updateStatus(id, "discarded");
    }

    private boolean updateStatus(UUID id, String status) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE dlq_entry SET status = ?, updated_at = now() WHERE id = ? AND status = 'pending'")) {
            ps.setString(1, status);
            ps.setObject(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update dead-letter entry " + id, e);
        }
    }

    private DlqEntry toEntry(ResultSet rs) throws SQLException {
        return new DlqEntry(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("kafka_key"),
                rs.getString("message_type"),
                rs.getString("original_topic"),
                rs.getString("reason"),
                rs.getString("payload"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }
}
