package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.scm.ThreadRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Lightweight per-thread state (spec §5): turn count + ownership. No conversation text is stored. */
@ApplicationScoped
public class ReviewThreadView {

    @Inject
    DataSource dataSource;

    public int turnCount(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT turn_count FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read thread turn count", e);
        }
    }

    public void bumpTurn(String reviewId, ThreadRef thread, String lastCommentId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO review_thread (review_id, thread_ref, turn_count, last_comment_id)
                     VALUES (?, ?, 1, ?)
                     ON CONFLICT (review_id, thread_ref)
                     DO UPDATE SET turn_count = review_thread.turn_count + 1,
                                   last_comment_id = EXCLUDED.last_comment_id
                     """)) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            ps.setString(3, lastCommentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to bump thread turn count", e);
        }
    }

    public void markOurThread(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO review_thread (review_id, thread_ref, is_ours) VALUES (?, ?, TRUE)
                     ON CONFLICT (review_id, thread_ref) DO UPDATE SET is_ours = TRUE
                     """)) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark owned thread", e);
        }
    }

    public boolean isOurThread(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_ours FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read thread ownership", e);
        }
    }
}
