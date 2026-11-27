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
 * finish that removed it records the reply's ending, so a redelivered or late result records nothing. The ending's
 * line is inserted in that same transaction (code-quality/M-5): a failed insert rolls the removal back, so the reply
 * stays outstanding and the redelivery records it. Every write that changed a row or the flag broadcasts the review
 * after its commit (code-quality/M-3), also while another reply keeps the flag up.
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
    @Inject
    DataSource dataSource;

    @Inject
    ReviewProjection projection;

    /** One write: the statement on the reply's own row, and the line to insert when it removed the row (or null). */
    private record Change(String rowSql, ReplyKey reply, EventLine ending) {
    }

    /** What one write changed: the reply's own row, and the review-wide flag. */
    private record Written(int rows, int flipped) {

        static final Written NOTHING = new Written(0, 0);

        boolean changed() {
            return rows > 0 || flipped > 0;
        }
    }

    /** The bot is answering this reply: the mark shows until this reply and every other one has finished. */
    public void start(ReplyKey reply) {
        writeAndBroadcast(new Change(ADD, reply, null));
    }

    /** This reply ended with nothing of its own to record (an answer, whose turn is filed elsewhere). */
    public void finish(ReplyKey reply) {
        writeAndBroadcast(new Change(REMOVE, reply, null));
    }

    /**
     * This reply ended and its caller recorded a line for it elsewhere (a posted answer, a harness refusal or failure),
     * so the review changed even when no row or flag did: it is broadcast exactly once, whatever the write changed.
     */
    public void finishAndBroadcast(ReplyKey reply) {
        write(new Change(REMOVE, reply, null));
        projection.touch(reply.reviewId());
    }

    /**
     * This reply ended: its row goes, and the mark clears when no other reply is outstanding. {@code ending} is
     * inserted only when this call removed the row, in the same transaction, so a result for a reply already finished,
     * or one cleared with the rest (a close, a new run), records and changes nothing, and a failed insert leaves the
     * reply outstanding. The broadcast follows the commit, so a dashboard that re-reads on it sees the line.
     */
    public void finish(ReplyKey reply, EventLine ending) {
        writeAndBroadcast(new Change(REMOVE, reply, ending));
    }

    private void writeAndBroadcast(Change change) {
        if (write(change).changed()) {
            projection.touch(change.reply().reviewId());
        }
    }

    private Written write(Change change) {
        ReplyKey reply = change.reply();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                Written written = apply(c, change);
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
    private static Written apply(Connection c, Change change) throws SQLException {
        ReplyKey reply = change.reply();
        if (!locked(c, reply.reviewId())) {
            return Written.NOTHING;
        }
        int rows;
        try (PreparedStatement row = c.prepareStatement(change.rowSql())) {
            row.setString(1, reply.reviewId());
            row.setString(2, reply.commentId());
            rows = row.executeUpdate();
        }
        if (rows > 0 && change.ending() != null) {
            EventLines.insertInTransaction(c, change.ending());
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
