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
 * see the other's row and leave the flag up with none left. Removing the row is how a result is consumed: only the one
 * finish that removed it may record the reply's ending, so a redelivered or late result records nothing. Every write
 * that changed a row or the flag broadcasts the review (code-quality/M-3), also while another reply keeps the flag up.
 * A clear that names no comment (a new run, an archive, a closed pull request, an answer posted before V122) sets the
 * flag false through {@link ReviewProjection}, and V122's trigger drops the review's rows in that statement.
 */
@ApplicationScoped
public class AnsweringMarks {

    private static final String LOCK = "SELECT 1 FROM review_status WHERE review_id = ? FOR UPDATE";
    private static final String ADD = """
            INSERT INTO review_answering (review_id, comment_id) VALUES (?, ?) ON CONFLICT DO NOTHING
            """;
    private static final String REMOVE = "DELETE FROM review_answering WHERE review_id = ? AND comment_id = ?";
    /** Flips the flag only where it disagrees with the rows, so the count says whether the flag changed. */
    private static final String MATCH = """
            UPDATE review_status SET answering = NOT answering
             WHERE review_id = ?
               AND answering <> EXISTS (SELECT 1 FROM review_answering a WHERE a.review_id = review_status.review_id)
            """;
    private static final Runnable RECORD_NOTHING = () -> { };

    @Inject
    DataSource dataSource;

    @Inject
    ReviewProjection projection;

    /** What one write changed: the reply's own row, and the review-wide flag. */
    private record Written(int rows, int flipped) {

        static final Written NOTHING = new Written(0, 0);

        boolean changed() {
            return rows > 0 || flipped > 0;
        }
    }

    /** The bot is answering this reply: the mark shows until this reply and every other one has finished. */
    public void start(ReplyKey reply) {
        if (write(ADD, reply).changed()) {
            projection.touch(reply.reviewId());
        }
    }

    /** This reply ended with nothing of its own to record (an answer, whose turn is filed elsewhere). */
    public void finish(ReplyKey reply) {
        finish(reply, RECORD_NOTHING);
    }

    /**
     * This reply ended: its row goes, and the mark clears when no other reply is outstanding. {@code recordEnding}
     * runs only when this call removed the row, so a result for a reply already finished, or one cleared with the rest
     * (a close, a new run), records and changes nothing. The broadcast follows the record, so a dashboard that re-reads
     * on it sees the recorded line.
     */
    public void finish(ReplyKey reply, Runnable recordEnding) {
        Written written = write(REMOVE, reply);
        try {
            if (written.rows() > 0) {
                recordEnding.run();
            }
        } finally {
            if (written.changed()) {
                projection.touch(reply.reviewId());
            }
        }
    }

    private Written write(String rowSql, ReplyKey reply) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Written written = apply(c, rowSql, reply);
                c.commit();
                return written;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("review_answering write failed for " + reply.reviewId(), e);
        }
    }

    /** Nothing when the read model does not hold the review yet: its row is the lock. */
    private static Written apply(Connection c, String rowSql, ReplyKey reply) throws SQLException {
        if (!locked(c, reply.reviewId())) {
            return Written.NOTHING;
        }
        int rows;
        try (PreparedStatement row = c.prepareStatement(rowSql)) {
            row.setString(1, reply.reviewId());
            row.setString(2, reply.commentId());
            rows = row.executeUpdate();
        }
        try (PreparedStatement match = c.prepareStatement(MATCH)) {
            match.setString(1, reply.reviewId());
            return new Written(rows, match.executeUpdate());
        }
    }

    private static boolean locked(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement lock = c.prepareStatement(LOCK)) {
            lock.setString(1, reviewId);
            try (ResultSet rs = lock.executeQuery()) {
                return rs.next();
            }
        }
    }
}
