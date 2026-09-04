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
import java.util.List;
import java.util.Optional;

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

    // A row already carrying THIS ref wins over the newest unattached one, which is what makes
    // a redelivery land on the row it landed on the first time. CommentsPosted has no
    // idempotency guard of its own (unlike ReviewGenerated's ifCurrentRun), and "newest row
    // still awaiting a ref" is not stable across two deliveries: once round 2's row is stamped
    // it stops being a candidate, so the second delivery walked down and stamped round 1's
    // never-posted row instead -- falsifying the "generated, never posted" fact AND handing the
    // verdict rule a thread ref pointing at the wrong finding.
        private static final String ATTACH_THREAD_REF = """
                UPDATE review_finding SET thread_ref = ?
                 WHERE id = (SELECT id FROM review_finding
                              WHERE review_id = ? AND path = ? AND start_line = ?
                                AND (thread_ref IS NULL OR thread_ref = ?)
                              ORDER BY (thread_ref = ?) DESC NULLS LAST, id DESC
                              LIMIT 1)
                """;

    /** A human-filed finding carries no message by design (DATA-MODEL §5), so the slot stays NULL. */
    private static final String INSERT_CONVERSATION_FINDING = """
                INSERT INTO review_finding (review_id, round, commit_sha, path, start_line, end_line,
                                            severity, category, origin, message, suggestion, thread_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, NULL, ?)
                """;

    /**
     * Newest row for the thread wins.
     *
     * <p>Several rows can share one thread ref across rounds — a finding re-posted at the same
     * anchor gets a new row each time it is generated — and the live one is the newest. This is the
     * same rule {@code newerThreadRef} settled for reconciliation, and for the same reason: keeping
     * an arbitrary older row targets a finding the author has already seen closed.
     */
    private static final String FIND_BY_THREAD = """
                SELECT id, round, path, start_line, end_line, severity, verdict
                  FROM review_finding
                 WHERE review_id = ? AND thread_ref = ?
                 ORDER BY id DESC
                 LIMIT 1
                """;

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    /**
     * Enough of a finding to decide whether a fix run may target it, and to name it when refusing.
     *
     * <p>Deliberately carries no {@code message} or {@code suggestion}. Those are the encrypted
     * columns, they are what a fix run's PROMPT needs rather than what this decision needs, and
     * adding them here would put decrypted finding text on a path that only has to answer "does this
     * thread name an open finding". The dispatch reads them when it builds the prompt.
     *
     * @param verdict the reconciliation verdict, or null for a finding not yet judged — which is a
     *     different thing from judged-and-unchanged, and only {@code RESOLVED} closes the door
     */
    public record TargetFinding(long id, int round, String path, int startLine, int endLine,
                                String severity, String verdict) {

        /** Reconciliation has already closed this one; a fix run would produce an empty diff. */
        public boolean isResolved() {
            return "RESOLVED".equals(verdict);
        }
    }

    /**
     * The open finding a thread names, or empty when the thread names none.
     *
     * <p><b>A read fault throws rather than answering empty</b>, and that is the whole reason this
     * method does not follow the log-and-continue style of its neighbours. Empty is reported to a
     * human as "no finding on this thread" — a claim about their repository that they will act on by
     * hunting for a comment that is right in front of them. Unknown is not zero (ADR-023) and here
     * unknown is not absent, so the record goes to the dead-letter queue where an operator sees it.
     *
     * @param threadRef the CONVERSATION ROOT, already normalized by the caller — a raw comment id
     *     from an SCM that threads by immediate parent names no finding
     */
    public Optional<TargetFinding> findByThread(String reviewId, String threadRef) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(FIND_BY_THREAD)) {
            ps.setString(1, reviewId);
            ps.setString(2, threadRef);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TargetFinding(rs.getLong("id"), rs.getInt("round"),
                        rs.getString("path"), rs.getInt("start_line"), rs.getInt("end_line"),
                        rs.getString("severity"), rs.getString("verdict")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the finding on thread " + threadRef
                    + " of " + reviewId, e);
        }
    }

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
                FindingRows.deleteRound(c, reviewId, round);
                FindingRows.insertAll(c, encryption, reviewId, round, commit, findings);
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
        // See ATTACH_THREAD_REF.

        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(ATTACH_THREAD_REF)) {
            for (PostedInline entry : posted) {
                if (entry.line() <= 0 || entry.path() == null || entry.threadRef() == null) {
                    continue;
                }
                ps.setString(1, entry.threadRef());
                ps.setString(2, reviewId);
                ps.setString(3, entry.path());
                ps.setInt(4, entry.line());
                ps.setString(5, entry.threadRef());
                ps.setString(6, entry.threadRef());
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
     * <p>The three matching rules and the reason each exists live in {@link FindingVerdicts} — they
     * are the subtlest thing in this projection and every obvious alternative fails silently.
     */
    public void recordVerdicts(String reviewId, int round, List<FindingVerdict> verdicts) {
        if (verdicts == null || verdicts.isEmpty()) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            FindingVerdicts.apply(c, reviewId, round, verdicts);
        } catch (SQLException e) {
            LOG.warnf(e, "Could not record verdicts for %s — dismissal rates will under-count", reviewId);
        }
    }
    /** A finding a human filed with {@code /finding}. Carries no message by design (DATA-MODEL §5). */
    public void recordConversationFinding(String reviewId, int round, ConversationFinding finding) {
        if (round <= 0) {
            return;
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT_CONVERSATION_FINDING)) {
            ps.setString(1, reviewId);
            ps.setInt(2, round);
            ps.setString(3, finding.commit() == null ? "" : finding.commit());
            ps.setString(4, finding.path());
            ps.setInt(5, finding.line());
            ps.setInt(6, finding.line());
            ps.setString(7, finding.severity());
            ps.setString(8, ORIGIN_CONVERSATION);
            ps.setString(9, finding.threadRef());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "Could not record the conversation finding for %s", reviewId);
        }
    }

    /**
     * Applies a verdict through its thread ref, and reports whether the thread SETTLED it.
     *
     * <p>Probe-then-apply rather than a conditional UPDATE, because an UPDATE that touches no rows
     * cannot say WHY. The old form returned false for "no such thread" and for "that thread's
     * finding is already judged" alike, and the caller treated both as "try the location instead" —
     * so a redelivered batch fell past a settled thread and stamped whatever the location rule found
     * next. A thread ref is the finding's own identity; once it names a judged row, there is nothing
     * left to look for.
     *
     * @return true when this verdict is finished with — either just applied, or already recorded.
     */
    private boolean applyByThread(PreparedStatement probe, PreparedStatement byId, String reviewId,
                                  int round, FindingVerdict verdict) throws SQLException {
        if (verdict.threadRef() == null || verdict.threadRef().isBlank()) {
            return false;
        }
        probe.setString(1, reviewId);
        probe.setString(2, verdict.threadRef());
        probe.setInt(3, round);
        long id;
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

    private void applyByLocation(PreparedStatement ps, String reviewId, int round,
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

    /**
     * The round a verdict landed in, or NULL when it could not be read.
     *
     * <p>NULL rather than a guess, and the analytics read excludes those rows rather than treating
     * them as zero: an unknown duration averaged in as "fixed in the round it was raised" would bias
     * the number toward exactly the wrong answer the old query already gave.
     */
    /**
     * Marks the findings a learned preference hid, naming the preference responsible.
     *
     * <p>The rows stay in the corpus rather than being dropped. That is what makes a wrong preference
     * detectable: if one starts hiding findings the team would have acted on, the evidence is still
     * there to count, and revoking it restores them on the next review.
     */
    public void markSuppressed(String reviewId, int round, SuppressionBatch batch) {
        if (round <= 0 || batch.hidden().isEmpty()) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            FindingSuppressions.mark(c, reviewId, round, batch);
        } catch (SQLException e) {
            LOG.warnf(e, "Could not mark suppressed findings for %s", reviewId);
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
