package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.event.IntegrationEvent.CommentsPosted.PostedInline;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * The durable per-finding record (P4 / ADR-027) — `review_finding`.
 *
 * <p>Separate from {@link ReviewProjection}, which is already far past the size this project's own
 * rules allow and answers a different question: that one keeps <em>one row per review</em> for the
 * dashboard, this one keeps <em>one row per finding per round</em> so the history survives. The
 * overwriting {@code review_status} does is exactly what makes "did this finding ever get fixed"
 * unanswerable.
 *
 * <p><b>Nothing here is a source of truth.</b> Every write is best-effort in the sense that matters:
 * a failure is logged and the review continues. The corpus losing a row costs recall in a dashboard;
 * a review failing because a projection could not write would cost an operator their review.
 */
@ApplicationScoped
public class FindingProjection {

    private static final Logger LOG = Logger.getLogger(FindingProjection.class);

    /** Written on every row rather than left null — the analytics reads filter on it. */
    static final String ORIGIN_REVIEW = "review";

    /** Matches the literal the UI's findings card and {@code PriorFinding} already use. */
    static final String ORIGIN_CONVERSATION = "conversation";

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    /**
     * Records the findings one round generated, replacing anything already stored for that round.
     *
     * <p><b>Delete-then-insert, in one transaction, is the idempotency mechanism</b> — not a unique
     * constraint. A redelivered {@code ReviewGenerated} passes {@code ifCurrentRun} in the window
     * between generation and {@code ReviewCompleted} (it checks {@code isReviewing()} plus the
     * commit, the exact window the V30 double-charge lived in), so this handler genuinely re-runs and
     * has to be safe when it does. A unique key could not do the job: {@code category} is nullable by
     * design, Postgres treats NULLs as distinct, and so the rows a customized prompt leaves
     * uncategorized — the ones least able to afford it — would be the ones it failed to deduplicate.
     *
     * @param round the review round, or a non-positive value when it could not be read, in which case
     *     nothing is written. Filing round-N findings under round 1 would merge them into round 1's
     *     rows; losing a round from the corpus is recoverable, mis-attributing one is not.
     */
    public void recordGenerated(String reviewId, int round, String commit, List<Finding> findings) {
        if (round <= 0) {
            LOG.warnf("Skipping the finding projection for %s — the round number could not be read, "
                    + "and filing these under round 1 would corrupt round 1", reviewId);
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                deleteRound(c, reviewId, round);
                insertAll(c, reviewId, round, commit, findings);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            LOG.warnf(e, "Could not record findings for %s round %d — the corpus will be missing "
                    + "this round", reviewId, round);
        }
    }

    /**
     * Attaches the thread each posted finding landed in.
     *
     * <p>Thread refs are born at {@code CommentsPosted}, after generation, which is why this is a
     * separate write and why {@code thread_ref} is null on a row until it happens. A row that keeps
     * a null one was generated and never posted — a degraded run's partial list, or a per-finding
     * post failure — and that is a fact worth recording rather than a gap to paper over.
     *
     * <p><b>Not scoped to a round, deliberately.</b> The obvious version takes the current round from
     * {@code ReviewRuns}, and that is a race: a push between generation and posting appends a new
     * {@code ReviewRequested}, so the round would have moved on and the refs would attach to a round
     * that generated nothing. The newest row still awaiting a ref for the location is the right
     * target regardless of which round raised it — recency by row order, never id arithmetic (V26).
     *
     * <p>The partial-retry branch emits entries with line 0, which match no finding; they are skipped
     * rather than written as a finding on line zero.
     */
    public void recordThreadRefs(String reviewId, List<PostedInline> posted) {
        if (posted == null || posted.isEmpty()) {
            return;
        }
        String sql = """
                UPDATE review_finding SET thread_ref = ?
                 WHERE id = (SELECT id FROM review_finding
                              WHERE review_id = ? AND path = ? AND start_line = ?
                                AND thread_ref IS NULL
                              ORDER BY id DESC LIMIT 1)
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (PostedInline entry : posted) {
                if (entry.line() <= 0 || entry.path() == null || entry.threadRef() == null) {
                    continue;
                }
                ps.setString(1, entry.threadRef());
                ps.setString(2, reviewId);
                ps.setString(3, entry.path());
                ps.setInt(4, entry.line());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOG.warnf(e, "Could not attach thread refs for %s", reviewId);
        }
    }

    /**
     * Applies a reconcile call's verdicts to the findings they judge.
     *
     * <p><b>The obvious rule — match the previous round — is wrong, and wrong silently.</b> Verdicts
     * do not judge the previous round's findings. {@code GenerateReview.priorRun} is built from
     * {@code posted_findings_json}, which is {@code COALESCE(open_findings_json, findings_json)}: the
     * carried-forward OPEN set (V20), spanning every earlier round. A finding raised in round 1,
     * still open through rounds 2 and 3 and fixed in round 4 still has its row at <em>round 1</em>,
     * so a {@code round - 1} rule would update round 3 and the {@code RESOLVED} verdict would never
     * land. A missed {@code UPDATE} affects zero rows and throws nothing, so "median rounds to
     * resolved" and the dismissal rate driving learned memory would be quietly, systematically wrong.
     *
     * <p>So: the newest <em>not yet judged</em> row for the location, across all rounds, preferring
     * the verdict's own thread ref when it has one. Recency is row order, never id arithmetic — the
     * V26 lesson applied on day one instead of after a replay of the GitLab defect.
     *
     * <p>Matching by location rather than only by thread ref matters on renames: the persisted
     * verdicts carry the first run's remapped paths, and a verdict for a finding that was never
     * posted has no thread ref at all.
     */
    public void recordVerdicts(String reviewId, List<FindingVerdict> verdicts) {
        if (verdicts == null || verdicts.isEmpty()) {
            return;
        }
        String byThread = """
                UPDATE review_finding SET verdict = ?, verdict_at = now()
                 WHERE id = (SELECT id FROM review_finding
                              WHERE review_id = ? AND thread_ref = ? AND verdict IS NULL
                              ORDER BY id DESC LIMIT 1)
                """;
        String byLocation = """
                UPDATE review_finding SET verdict = ?, verdict_at = now()
                 WHERE id = (SELECT id FROM review_finding
                              WHERE review_id = ? AND path = ? AND start_line = ? AND verdict IS NULL
                              ORDER BY id DESC LIMIT 1)
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement thread = c.prepareStatement(byThread);
             PreparedStatement location = c.prepareStatement(byLocation)) {
            for (FindingVerdict verdict : verdicts) {
                if (verdict == null || verdict.status() == null) {
                    continue;
                }
                if (applyByThread(thread, reviewId, verdict)) {
                    continue;
                }
                applyByLocation(location, reviewId, verdict);
            }
        } catch (SQLException e) {
            LOG.warnf(e, "Could not record verdicts for %s — dismissal rates will under-count", reviewId);
        }
    }

    /** A finding a human filed with {@code /finding}. Carries no message by design (DATA-MODEL §5). */
    public void recordConversationFinding(String reviewId, int round, String commit, String path,
                                          int line, String severity, String threadRef) {
        if (round <= 0) {
            return;
        }
        String sql = """
                INSERT INTO review_finding (review_id, round, commit_sha, path, start_line, end_line,
                                            severity, category, origin, message, suggestion, thread_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, NULL, ?)
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            ps.setInt(2, round);
            ps.setString(3, commit == null ? "" : commit);
            ps.setString(4, path);
            ps.setInt(5, line);
            ps.setInt(6, line);
            ps.setString(7, severity);
            ps.setString(8, ORIGIN_CONVERSATION);
            ps.setString(9, threadRef);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "Could not record the conversation finding for %s", reviewId);
        }
    }

    private boolean applyByThread(PreparedStatement ps, String reviewId, FindingVerdict verdict)
            throws SQLException {
        if (verdict.threadRef() == null || verdict.threadRef().isBlank()) {
            return false;
        }
        ps.setString(1, verdict.status().name());
        ps.setString(2, reviewId);
        ps.setString(3, verdict.threadRef());
        return ps.executeUpdate() > 0;
    }

    private void applyByLocation(PreparedStatement ps, String reviewId, FindingVerdict verdict)
            throws SQLException {
        if (verdict.path() == null) {
            return;
        }
        ps.setString(1, verdict.status().name());
        ps.setString(2, reviewId);
        ps.setString(3, verdict.path());
        ps.setInt(4, verdict.line());
        ps.executeUpdate();
    }

    private static void deleteRound(Connection c, String reviewId, int round) throws SQLException {
        try (PreparedStatement ps =
                     c.prepareStatement("DELETE FROM review_finding WHERE review_id = ? AND round = ?")) {
            ps.setString(1, reviewId);
            ps.setInt(2, round);
            ps.executeUpdate();
        }
    }

    private void insertAll(Connection c, String reviewId, int round, String commit,
                           List<Finding> findings) throws SQLException {
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
                ps.setString(1, reviewId);
                ps.setInt(2, round);
                ps.setString(3, commit == null ? "" : commit);
                ps.setString(4, finding.path());
                ps.setInt(5, finding.range().startLine());
                ps.setInt(6, finding.range().endLine());
                ps.setString(7, finding.severity() == null ? "" : finding.severity().name());
                setNullable(ps, 8, finding.category() == null ? null : finding.category().name());
                ps.setString(9, ORIGIN_REVIEW);
                // Findings quote the source under review — encrypted at rest, AAD = reviewId, the
                // same binding review_status.findings_json uses.
                setNullable(ps, 10, encrypted(finding.message(), reviewId));
                setNullable(ps, 11, encrypted(finding.suggestion(), reviewId));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private String encrypted(String value, String reviewId) {
        return value == null ? null : encryption.encryptString(value, reviewId);
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /** Row count for one review — the seam a test asserts on, and the dashboard's cheapest read. */
    public int countFor(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM review_finding WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOG.debugf(e, "Could not count findings for %s", reviewId);
            return 0;
        }
    }
}
