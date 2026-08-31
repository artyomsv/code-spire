package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.FindingVerdict;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Which finding a verdict judges — the hardest question in this projection, and the one where every
 * obvious answer is wrong in a way that throws nothing.
 *
 * <p>Three rules, each earned:
 *
 * <p><b>1. Across all rounds, not the previous one.</b> {@code GenerateReview.priorRun} is built from
 * {@code posted_findings_json}, which is {@code COALESCE(open_findings_json, findings_json)} — the
 * carried-forward OPEN set (V20), spanning every earlier round. A finding raised in round 1, still
 * open through rounds 2 and 3 and fixed in round 4 still has its row at round 1. A {@code round - 1}
 * rule updates round 3, matches nothing, and reports nothing: a missed {@code UPDATE} affects zero
 * rows. Every dismissal rate and every rounds-to-fix figure would be quietly wrong.
 *
 * <p><b>2. Earlier rounds only.</b> A verdict judges a finding that already existed — never one the
 * same event just inserted. Without the bound, a redelivered {@code ReviewGenerated} whose prior row
 * was already judged fell through to the location rule and stamped the CURRENT round's fresh finding
 * with the old verdict. A stray {@code ACKNOWLEDGED} then counts as a dismissal in the proposal scan,
 * which is the number deciding whether the reviewer starts hiding findings.
 *
 * <p><b>3. A settled thread stops the search.</b> The thread ref is the finding's own identity. Once
 * it names a judged row there is nothing left to look for, so the location rule must not run as a
 * fallback — which is why this probes and reads the verdict rather than firing a conditional UPDATE
 * and inferring from the row count. An UPDATE that touches no rows cannot say whether the thread was
 * absent or already answered, and those need opposite handling.
 */
final class FindingVerdicts {

    /** Newest candidate for a thread, restricted to rounds that existed before this one. */
    private static final String PROBE_THREAD = """
            SELECT id, verdict FROM review_finding
             WHERE review_id = ? AND thread_ref = ? AND round < ?
             ORDER BY id DESC LIMIT 1
            """;

    private static final String APPLY_TO_ID = """
            UPDATE review_finding SET verdict = ?, verdict_at = now(), verdict_round = ?
             WHERE id = ?
            """;

    /**
     * The fallback for a verdict with no thread ref — which is what a finding that failed to post
     * has. Newest not-yet-judged row for the location, from an earlier round.
     */
    private static final String BY_LOCATION = """
            UPDATE review_finding SET verdict = ?, verdict_at = now(), verdict_round = ?
             WHERE id = (SELECT id FROM review_finding
                          WHERE review_id = ? AND path = ? AND start_line = ?
                            AND round < ? AND verdict IS NULL
                          ORDER BY id DESC LIMIT 1)
            """;

    private FindingVerdicts() {
    }

    /** Applies every verdict in the batch. Caller owns the connection and the failure handling. */
    static void apply(Connection c, String reviewId, int round, List<FindingVerdict> verdicts)
            throws SQLException {
        try (PreparedStatement probe = c.prepareStatement(PROBE_THREAD);
             PreparedStatement byId = c.prepareStatement(APPLY_TO_ID);
             PreparedStatement byLocation = c.prepareStatement(BY_LOCATION)) {
            for (FindingVerdict verdict : verdicts) {
                if (verdict == null || verdict.status() == null) {
                    continue;
                }
                if (!settledByThread(probe, byId, reviewId, round, verdict)) {
                    applyByLocation(byLocation, reviewId, round, verdict);
                }
            }
        }
    }

    /** @return true when the verdict is finished with — either just applied, or already recorded. */
    private static boolean settledByThread(PreparedStatement probe, PreparedStatement byId,
                                           String reviewId, int round, FindingVerdict verdict)
            throws SQLException {
        if (verdict.threadRef() == null || verdict.threadRef().isBlank()) {
            return false;
        }
        long id;
        probe.setString(1, reviewId);
        probe.setString(2, verdict.threadRef());
        probe.setInt(3, round);
        try (ResultSet rs = probe.executeQuery()) {
            if (!rs.next()) {
                return false;
            }
            if (rs.getString("verdict") != null) {
                return true;
            }
            id = rs.getLong("id");
        }
        byId.setString(1, verdict.status().name());
        FindingRows.setRound(byId, 2, round);
        byId.setLong(3, id);
        byId.executeUpdate();
        return true;
    }

    /**
     * Matching by location matters on a rename: persisted verdicts carry the first run's remapped
     * paths, and a verdict for a finding that was never posted has no thread ref at all.
     */
    private static void applyByLocation(PreparedStatement ps, String reviewId, int round,
                                        FindingVerdict verdict) throws SQLException {
        if (verdict.path() == null) {
            return;
        }
        ps.setString(1, verdict.status().name());
        FindingRows.setRound(ps, 2, round);
        ps.setString(3, reviewId);
        ps.setString(4, verdict.path());
        ps.setInt(5, verdict.line());
        ps.setInt(6, round);
        ps.executeUpdate();
    }
}
