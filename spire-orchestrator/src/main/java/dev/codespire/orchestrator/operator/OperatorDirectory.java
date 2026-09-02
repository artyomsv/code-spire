package dev.codespire.orchestrator.operator;

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

/**
 * Who has signed in, so an admin can pick an operator instead of typing an opaque id.
 *
 * <p>Recorded from the session the dashboard already establishes. Deliberately not a directory of
 * everyone the identity provider knows: this deployment has no right to enumerate that and no way
 * to keep it current, and the only operators an admin can usefully link are the ones who have
 * actually been here.
 *
 * <p>A row grants nothing. Authorization is the token's roles on every request, exactly as before —
 * this table is a display convenience, and treating it as anything more would be a second, stale
 * source of truth about who may do what.
 */
@ApplicationScoped
public class OperatorDirectory {

    /** {@code subject} is the stable OIDC id; {@code username} is mutable and shown, never keyed on. */
    public record Operator(String subject, String username, String displayName) {
    }

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(OperatorDirectory.class);

    @Inject
    DataSource dataSource;

    /**
     * Records that this operator was here.
     *
     * <p>Never throws. It runs on the session probe every screen calls, and a dashboard that refuses
     * to load because a convenience table could not be written would be a worse outcome than an
     * admin having to type one id.
     */
    @Transactional
    public void seen(String subject, String username, String displayName) {
        if (subject == null || subject.isBlank()) {
            return;
        }
        String sql = """
                INSERT INTO operator_seen (oidc_subject, username, display_name)
                VALUES (?, ?, ?)
                ON CONFLICT (oidc_subject) DO UPDATE SET
                    username = EXCLUDED.username,
                    display_name = EXCLUDED.display_name,
                    last_seen_at = now()
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, subject);
            ps.setString(2, username == null ? "" : username);
            ps.setString(3, displayName == null ? "" : displayName);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("could not record the signed-in operator", e);
        }
    }

    public List<Operator> all() {
        String sql = """
                SELECT oidc_subject, username, display_name
                  FROM operator_seen ORDER BY last_seen_at DESC
                """;
        List<Operator> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Operator(rs.getString("oidc_subject"), rs.getString("username"),
                        rs.getString("display_name")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list the operators seen", e);
        }
        return out;
    }
}
