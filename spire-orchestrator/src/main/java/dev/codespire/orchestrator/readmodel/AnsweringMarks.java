package dev.codespire.orchestrator.readmodel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The replies the bot is answering (V122, review of PR #457, code-quality/M-1). Several can be outstanding at once,
 * so each is a {@code review_answering} row, and {@code review_status.answering}, the flag the dashboard reads, is
 * true while any row is left. {@link #start} adds a reply's row, {@link #finish} removes only its own: reply B's
 * decline leaves the mark up while reply A's answer is still being written.
 *
 * <p>Both run in one transaction that first locks the review's {@code review_status} row, so two finishes cannot each
 * see the other's row and leave the flag up with none left. A clear that names no comment (a new run, an archive, a
 * closed pull request, an answer posted before V122) sets the flag false through {@link ReviewProjection}, and V122's
 * trigger drops the review's rows in that statement.
 */
@ApplicationScoped
public class AnsweringMarks {

    private static final String LOCK = "SELECT 1 FROM review_status WHERE review_id = ? FOR UPDATE";
    private static final String ADD = """
            INSERT INTO review_answering (review_id, comment_id) VALUES (?, ?) ON CONFLICT DO NOTHING
            """;
    private static final String REMOVE = "DELETE FROM review_answering WHERE review_id = ? AND comment_id = ?";
    /** Flips the flag only where it disagrees with the rows, so the count says whether the dashboard must hear. */
    private static final String MATCH = """
            UPDATE review_status SET answering = NOT answering
             WHERE review_id = ?
               AND answering <> EXISTS (SELECT 1 FROM review_answering a WHERE a.review_id = review_status.review_id)
            """;

    @Inject
    DataSource dataSource;

    @Inject
    ReviewProjection projection;

    /** The bot is answering {@code commentId}: the mark shows until this reply and every other one has finished. */
    public void start(String reviewId, String commentId) {
        if (write(ADD, reviewId, commentId) >= 0) {
            projection.touch(reviewId);
        }
    }

    /**
     * {@code commentId}'s reply ended: its row goes, and the mark clears when no other reply is outstanding. A result
     * for a reply already finished, or one cleared with the rest, changes nothing.
     */
    public void finish(String reviewId, String commentId) {
        if (write(REMOVE, reviewId, commentId) > 0) {
            projection.touch(reviewId);
        }
    }

    /** {@code -1} when the review has no row; otherwise how many flags {@link #MATCH} flipped. */
    private int write(String rowSql, String reviewId, String commentId) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int flipped = apply(c, rowSql, reviewId, commentId);
                c.commit();
                return flipped;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("review_answering write failed", e);
        }
    }

    private static int apply(Connection c, String rowSql, String reviewId, String commentId) throws SQLException {
        try (PreparedStatement lock = c.prepareStatement(LOCK)) {
            lock.setString(1, reviewId);
            try (ResultSet rs = lock.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
            }
        }
        try (PreparedStatement row = c.prepareStatement(rowSql)) {
            row.setString(1, reviewId);
            row.setString(2, commentId);
            row.executeUpdate();
        }
        try (PreparedStatement match = c.prepareStatement(MATCH)) {
            match.setString(1, reviewId);
            return match.executeUpdate();
        }
    }
}
