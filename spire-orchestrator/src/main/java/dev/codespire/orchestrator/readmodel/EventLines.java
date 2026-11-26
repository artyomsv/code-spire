package dev.codespire.orchestrator.readmodel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * The {@code review_event} insert. seq is allocated atomically (MAX+1 inside the INSERT) and guarded by UNIQUE
 * (review_id, seq) (V6): several consumers append to the same review, so a writer that loses the allocation race gets
 * 23505 and retries with a fresh MAX. {@link ReviewProjection#appendEvent(EventLine)} retries on its own connection;
 * {@link #insertInTransaction} retries inside a caller's transaction, so the line commits or rolls back with the
 * caller's other writes (code-quality/M-5, review of PR #457).
 */
final class EventLines {

    static final String UNIQUE_VIOLATION = "23505";
    static final int SEQ_RETRY_LIMIT = 50;

    private static final String INSERT = """
            INSERT INTO review_event (review_id, seq, lane, type, detail, thread_ref, comment_id)
            SELECT ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?, ?, ? FROM review_event WHERE review_id = ?
            """;

    private EventLines() {
    }

    /** One attempt: a lost seq race throws the unique violation for the caller to retry. */
    static void insert(Connection c, EventLine line) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setString(1, line.reviewId());
            ps.setString(2, line.lane());
            ps.setString(3, line.type());
            ps.setString(4, line.detail() == null ? "" : line.detail());
            ps.setString(5, line.threadRef());
            ps.setString(6, line.commentId());
            ps.setString(7, line.reviewId());
            ps.executeUpdate();
        }
    }

    /**
     * Inside an open transaction: each attempt runs under a savepoint, since a failed statement aborts a Postgres
     * transaction, and a lost seq race rolls back to it and tries again. Any other failure is thrown, and the caller
     * rolls its whole transaction back.
     */
    static void insertInTransaction(Connection c, EventLine line) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            Savepoint before = c.setSavepoint();
            try {
                insert(c, line);
                c.releaseSavepoint(before);
                return;
            } catch (SQLException e) {
                c.rollback(before);
                if (!UNIQUE_VIOLATION.equals(e.getSQLState()) || attempt == SEQ_RETRY_LIMIT) {
                    throw e;
                }
            }
        }
    }
}
