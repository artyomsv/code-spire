package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.Finding;
import dev.codespire.encryption.EncryptionService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * How a {@code review_finding} row is written — separated from {@link FindingProjection}, which
 * decides <em>which</em> row.
 *
 * <p>That is the whole boundary, and it is a real one: the interesting part of this projection is the
 * row-selection logic (a verdict must reach the newest not-yet-judged row across all rounds, a thread
 * ref must not be scoped to a round), and it was getting hard to see through the JDBC plumbing around
 * it. Nothing here makes a decision; everything here binds a value.
 */
final class FindingRows {

    private FindingRows() {
    }

    /** Clears a round so it can be rewritten. The idempotency half of delete-then-insert. */
    static void deleteRound(Connection c, String reviewId, int round) throws SQLException {
        try (PreparedStatement ps =
                     c.prepareStatement("DELETE FROM review_finding WHERE review_id = ? AND round = ?")) {
            ps.setString(1, reviewId);
            ps.setInt(2, round);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts one row per finding.
     *
     * <p>{@code message} and {@code suggestion} are encrypted with the reviewId as AAD — the same
     * binding {@code review_status.findings_json} uses, because both quote the source under review.
     * Everything else is stored in clear so the table can be grouped server-side.
     */
    static void insertAll(Connection c, EncryptionService encryption, String reviewId, int round,
                          String commit, List<Finding> findings) throws SQLException {
        if (findings == null || findings.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO review_finding (review_id, round, commit_sha, path, start_line, end_line,
                                            severity, category, origin, message, suggestion)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Finding finding : findings) {
                if (finding == null || finding.path() == null || finding.range() == null) {
                    continue;
                }
                bind(ps, encryption, reviewId, round, commit, finding);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** One row's eleven columns, in one place, so the insert loop stays readable. */
    private static void bind(PreparedStatement ps, EncryptionService encryption, String reviewId,
                             int round, String commit, Finding finding) throws SQLException {
        ps.setString(1, reviewId);
        ps.setInt(2, round);
        ps.setString(3, commit == null ? "" : commit);
        ps.setString(4, finding.path());
        ps.setInt(5, finding.range().startLine());
        ps.setInt(6, finding.range().endLine());
        ps.setString(7, finding.severity() == null ? "" : finding.severity().name());
        setNullable(ps, 8, finding.category() == null ? null : finding.category().name());
        ps.setString(9, FindingProjection.ORIGIN_REVIEW);
        setNullable(ps, 10, encrypted(encryption, finding.message(), reviewId));
        setNullable(ps, 11, encrypted(encryption, finding.suggestion(), reviewId));
    }

    static String encrypted(EncryptionService encryption, String value, String reviewId) {
        return value == null ? null : encryption.encryptString(value, reviewId);
    }

    static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /**
     * The round a verdict landed in, or NULL when it could not be read.
     *
     * <p>NULL rather than a guess, and the analytics read excludes those rows rather than treating
     * them as zero: an unknown duration averaged in as "fixed in the round it was raised" would bias
     * the number toward exactly the wrong answer the old median query already gave.
     */
    static void setRound(PreparedStatement ps, int index, int round) throws SQLException {
        if (round <= 0) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, round);
        }
    }
}
