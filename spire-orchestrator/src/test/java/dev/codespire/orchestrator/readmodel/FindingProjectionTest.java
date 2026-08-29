package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.event.IntegrationEvent.CommentsPosted.PostedInline;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingCategory;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.Severity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durable finding record (P4 / ADR-027).
 *
 * <p>The projection is the whole feature's foundation: analytics and learned memory both read it and
 * neither can be more correct than it is. Its two dangerous properties are that a wrong write is
 * silent — a missed {@code UPDATE} affects zero rows and throws nothing — and that its inputs arrive
 * on redelivery, so these tests aim at both.
 */
@QuarkusTest
class FindingProjectionTest {

    private static final String REVIEW = "review::TEST-WS/TEST-REPO#4001";
    /** The round a verdict is recorded as landing in -- later than every round these tests raise. */
    private static final int VERDICT_ROUND = 9;

    private static final String COMMIT = "TESTSHA000000000000000000000000000000001";

    @Inject
    FindingProjection findings;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM review_finding WHERE review_id LIKE 'review::TEST-%'", ps -> { });
    }

    @Test
    void recordsEveryFindingAGeneratedReviewCarries() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(
                finding("src/A.java", 10, Severity.BLOCKER, FindingCategory.SECURITY),
                finding("src/B.java", 20, Severity.NIT, FindingCategory.NAMING)));

        assertEquals(2, findings.countFor(REVIEW));
        assertEquals("SECURITY", column("src/A.java", "category"));
        assertEquals("NIT", column("src/B.java", "severity"));
    }

    /**
     * A redelivered {@code ReviewGenerated} passes {@code ifCurrentRun} in the window between
     * generation and completion — the exact window the V30 double-charge lived in — so the handler
     * genuinely re-runs. Idempotency is delete-then-insert, because a unique key cannot do the job:
     * {@code category} is nullable by design and Postgres treats NULLs as distinct.
     */
    @Test
    void aRedeliveredRoundReplacesItsRowsRatherThanDuplicatingThem() {
        List<Finding> generated = List.of(finding("src/A.java", 10, Severity.MAJOR, null));

        findings.recordGenerated(REVIEW, 1, COMMIT, generated);
        findings.recordGenerated(REVIEW, 1, COMMIT, generated);

        assertEquals(1, findings.countFor(REVIEW), "an uncategorized row must still deduplicate");
    }

    /**
     * {@code ReviewRuns.currentRun} answers FIRST_RUN when it cannot read — the safe direction for
     * the ledger and the wrong one here, since it would merge round N into round 1's rows. Losing a
     * round is recoverable; mis-attributing one is not.
     */
    @Test
    void skipsTheWriteWhenTheRoundCouldNotBeRead() {
        findings.recordGenerated(REVIEW, 0, COMMIT,
                List.of(finding("src/A.java", 10, Severity.MAJOR, null)));

        assertEquals(0, findings.countFor(REVIEW));
    }

    @Test
    void attachesTheThreadEachPostedFindingLandedIn() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(
                finding("src/A.java", 10, Severity.MAJOR, null),
                finding("src/B.java", 20, Severity.MINOR, null)));

        findings.recordThreadRefs(REVIEW, List.of(
                new PostedInline("thread-a", "src/A.java", 10),
                // The worker's partial-retry branch emits line 0; it matches no finding and must not
                // be written as a finding on line zero.
                new PostedInline("thread-junk", "src/B.java", 0)));

        assertEquals("thread-a", column("src/A.java", "thread_ref"));
        assertNull(column("src/B.java", "thread_ref"),
                "a generated-but-unposted finding keeps a null thread ref — that is the fact worth storing");
    }

    /**
     * <b>The rule the obvious implementation gets wrong.</b>
     *
     * <p>Verdicts do not judge the previous round. {@code priorRun} is built from the carried-forward
     * OPEN set (V20), which spans every earlier round — so a finding raised in round 1 and still open
     * through rounds 2 and 3 is judged in round 4 while its row still sits at round 1. A
     * {@code round - 1} rule would update round 3, match nothing, and lose the verdict silently.
     */
    @Test
    void aVerdictLandsOnAFindingRaisedSeveralRoundsEarlier() {
        findings.recordGenerated(REVIEW, 1, COMMIT,
                List.of(finding("src/Old.java", 42, Severity.MAJOR, FindingCategory.CORRECTNESS)));
        findings.recordGenerated(REVIEW, 2, COMMIT, List.of(finding("src/New.java", 7, Severity.NIT, null)));
        findings.recordGenerated(REVIEW, 3, COMMIT, List.of(finding("src/Other.java", 9, Severity.NIT, null)));

        findings.recordVerdicts(REVIEW, VERDICT_ROUND, List.of(
                new FindingVerdict(null, "src/Old.java", 42, FindingVerdict.Status.RESOLVED, null)));

        assertEquals("RESOLVED", column("src/Old.java", "verdict"),
                "the verdict must reach the round-1 row, not the previous round");
        assertNotNull(column("src/Old.java", "verdict_at"));
        assertNull(column("src/New.java", "verdict"), "unjudged findings stay NULL, never a default");
    }

    /** A thread ref identifies the finding directly, and survives a rename that moved its path. */
    @Test
    void aVerdictPrefersTheThreadRefWhenItHasOne() {
        findings.recordGenerated(REVIEW, 1, COMMIT,
                List.of(finding("src/Renamed.java", 5, Severity.MAJOR, null)));
        findings.recordThreadRefs(REVIEW, List.of(new PostedInline("thread-x", "src/Renamed.java", 5)));

        findings.recordVerdicts(REVIEW, VERDICT_ROUND, List.of(new FindingVerdict(
                "thread-x", "src/MovedElsewhere.java", 999, FindingVerdict.Status.ACKNOWLEDGED, null)));

        assertEquals("ACKNOWLEDGED", column("src/Renamed.java", "verdict"));
    }

    /**
     * A judgment already made must not be moved by a redelivered verdict batch.
     *
     * <p>This is the case the {@code verdict IS NULL} guard exists for, and the only one that proves
     * it: an obvious version of this test — judge round 1, raise round 2, judge again — holds with the
     * guard deleted, because round 2's row is both the newest AND the unjudged one, so
     * {@code ORDER BY id DESC} alone picks it. The discriminating case needs every row already
     * judged, which is exactly what a redelivered {@code ReviewGenerated} produces.
     */
    @Test
    void aRedeliveredVerdictBatchDoesNotRewriteAJudgmentAlreadyMade() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(finding("src/A.java", 3, Severity.MINOR, null)));
        findings.recordVerdicts(REVIEW, VERDICT_ROUND,
                List.of(new FindingVerdict(null, "src/A.java", 3, FindingVerdict.Status.STILL_OPEN, null)));
        findings.recordGenerated(REVIEW, 2, COMMIT, List.of(finding("src/A.java", 3, Severity.MINOR, null)));
        findings.recordVerdicts(REVIEW, VERDICT_ROUND,
                List.of(new FindingVerdict(null, "src/A.java", 3, FindingVerdict.Status.RESOLVED, null)));

        // The same batch arrives again -- Kafka is at-least-once and ReviewGenerated carries verdicts.
        findings.recordVerdicts(REVIEW, VERDICT_ROUND,
                List.of(new FindingVerdict(null, "src/A.java", 3, FindingVerdict.Status.STILL_OPEN, null)));

        assertEquals("STILL_OPEN", verdictForRound(1), "round 1's judgment is history and must not move");
        assertEquals("RESOLVED", verdictForRound(2), "a redelivery must not undo the newer judgment");
    }

    /** Findings quote the source under review, so the message never sits in clear (DATA-MODEL §5). */
    @Test
    void theMessageIsEncryptedAtRestWhileTheCoordinatesStayQueryable() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(new Finding("src/A.java",
                new LineRange(1, 1), Severity.BLOCKER, FindingCategory.SECURITY,
                "TEST-CANARY-SECRET-STRING", null)));

        String stored = column("src/A.java", "message");
        assertNotNull(stored);
        assertTrue(!stored.contains("TEST-CANARY-SECRET-STRING"),
                "the finding message must not be readable in the column");
        assertEquals("src/A.java", column("src/A.java", "path"), "coordinates stay in clear to be grouped");
    }

    private static Finding finding(String path, int line, Severity severity, FindingCategory category) {
        return new Finding(path, new LineRange(line, line), severity, category, "why it matters", null);
    }

    private String column(String path, String name) {
        return queryOne("SELECT " + validated(name) + " FROM review_finding WHERE review_id = ? AND path = ?"
                + " ORDER BY id DESC LIMIT 1", ps -> {
            ps.setString(1, REVIEW);
            ps.setString(2, path);
        });
    }

    private String verdictForRound(int round) {
        return queryOne("SELECT verdict FROM review_finding WHERE review_id = ? AND round = ?"
                + " ORDER BY id DESC LIMIT 1", ps -> {
            ps.setString(1, REVIEW);
            ps.setInt(2, round);
        });
    }

    /**
     * A column name cannot be a bind parameter, so it is checked against a closed list rather than
     * asserted safe in a comment — the same move {@code AttentionQueries} made when its table name
     * could not be bound either.
     */
    private static String validated(String column) {
        List<String> allowed = List.of("category", "severity", "thread_ref", "verdict", "verdict_at",
                "message", "path", "origin", "round");
        if (!allowed.contains(column)) {
            throw new IllegalArgumentException("not a queryable column: " + column);
        }
        return column;
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private String queryOne(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("read failed: " + sql, e);
        }
    }

    private void exec(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}
