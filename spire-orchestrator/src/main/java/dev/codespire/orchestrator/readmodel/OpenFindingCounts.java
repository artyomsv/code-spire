package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.Severity;
import dev.codespire.orchestrator.repository.BlockApprovalAt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * A review's reconciled open findings, counted for the list row, the detail page and the verdict decision, and
 * the level a repository blocks approval at (V115, ADR-047).
 *
 * @param carriedOver how many of {@code open} came from a previous run rather than this one.
 *                    Split out because a bare total moving 1 -> 2 between rounds reads as a
 *                    regression the author caused, when it can equally be one new finding beside
 *                    one that was already open and simply never fixed.
 */
record OpenFindingCounts(int open, int openBlockers, int carriedOver, List<Severity> openSeverities) {

    /**
     * Counts a review's open findings from their severity slugs, keyed by anchor or thread.
     *
     * @param newlyRaised how many of the keys this run's own review call raised
     */
    static OpenFindingCounts of(Map<String, String> openSevByKey, int newlyRaised) {
        int blockers = (int) openSevByKey.values().stream().filter("critical"::equals).count();
        // The stored slug back to a severity with the one reverse map; "critical" is BLOCKER and nothing else is,
        // so the count at BLOCKER is exactly `blockers`. A missing slug is INFO, which never blocks.
        List<Severity> severities = openSevByKey.values().stream()
                .map(slug -> slug == null ? Severity.INFO : ReviewProjection.severityFromSlug(slug)).toList();
        return new OpenFindingCounts(openSevByKey.size(), blockers, openSevByKey.size() - newlyRaised, severities);
    }

    /** The open findings a repository blocking at {@code level} counts; BLOCKER is {@code openBlockers}. */
    int atOrAbove(BlockApprovalAt level) {
        return (int) openSeverities.stream().filter(level::blocks).count();
    }

    /** The review's repository's "Block approval at"; {@code BLOCKER} when its repository is unknown. */
    static BlockApprovalAt levelOf(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT r.block_approval_at FROM review_status s JOIN repository r ON r.id = s.repository_id
                 WHERE s.review_id = ?
                """)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? BlockApprovalAt.valueOf(rs.getString(1)) : BlockApprovalAt.DEFAULT;
            }
        }
    }
}
