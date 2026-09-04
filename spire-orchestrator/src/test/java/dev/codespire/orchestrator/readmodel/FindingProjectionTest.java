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

    /**
     * The rule {@code /fix} dispatches on, and it is NOT "the newest row wins".
     *
     * <p>Writing this test corrected the production comment. A finding re-posted at the same anchor
     * does get a new row each round, but {@code ATTACH_THREAD_REF} orders by
     * {@code (thread_ref = ?) DESC}, so a row already carrying the ref beats the newest unattached
     * one — deliberately, so a redelivery lands where it landed the first time. The consequence is
     * that AT MOST ONE row ever carries a ref, and it is the round that first posted the thread.
     *
     * <p>That is the right target: it is the finding actually posted in the thread the author is
     * replying to. It also explains why flipping the query to {@code ASC} changed nothing — the
     * match set has one element. The claim that it was a newest-wins rule was the defect.
     */
    @Test
    void findByThreadAnswersTheFindingTheThreadWasAttachedTo() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(finding("src/A.java", 10, Severity.MINOR, null)));
        findings.recordThreadRefs(REVIEW, List.of(new PostedInline("thread-live", "src/A.java", 10)));
        findings.recordGenerated(REVIEW, 2, COMMIT, List.of(finding("src/A.java", 10, Severity.BLOCKER, null)));
        findings.recordThreadRefs(REVIEW, List.of(new PostedInline("thread-live", "src/A.java", 10)));

        FindingProjection.TargetFinding target = findings.findByThread(REVIEW, "thread-live").orElseThrow();
        assertEquals(1, target.round(), "the ref stays on the round that first posted the thread");
        assertEquals(Severity.MINOR.name(), target.severity(), "round 1's row, not round 2's");
        assertEquals("review", target.origin(), "a model-generated finding carries a description");
    }

    /** Empty means the thread names no finding — the one answer this method may give for that. */
    @Test
    void findByThreadAnswersEmptyForAThreadThatNamesNoFinding() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(finding("src/A.java", 10, Severity.MINOR, null)));

        assertTrue(findings.findByThread(REVIEW, "thread-nothing").isEmpty());
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

    /**
     * <b>A redelivered verdict must not land on the round it arrived with.</b>
     *
     * <p>Verdicts judge findings from EARLIER rounds. Without that bound, a redelivered
     * {@code ReviewGenerated} whose prior row was already judged fell past the thread rule — which
     * could not distinguish "no such thread" from "already judged" — into the location rule, and
     * stamped the finding this same event had just inserted for the current round.
     *
     * <p>The consequence is not cosmetic: a stray {@code ACKNOWLEDGED} counts as a dismissal in the
     * proposal scan, which is the number that decides whether the reviewer starts hiding findings.
     */
    @Test
    void aRedeliveredVerdictDoesNotStampTheFindingItsOwnEventJustInserted() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(finding("src/A.java", 5, Severity.MAJOR, null)));
        findings.recordThreadRefs(REVIEW, List.of(new PostedInline("thread-x", "src/A.java", 5)));
        var judged = new FindingVerdict("thread-x", "src/A.java", 5, FindingVerdict.Status.RESOLVED, null);
        findings.recordVerdicts(REVIEW, 2, List.of(judged));
        // Round 2 raises the same location again, as a re-review of an unfixed area does. It is
        // unjudged, and it sits inside the earlier-round window a round-3 redelivery would search --
        // which is what makes this discriminate the PROBE rather than the round bound. At round 2
        // the bound alone already excluded it, so the obvious version of this test proved nothing.
        findings.recordGenerated(REVIEW, 2, COMMIT, List.of(finding("src/A.java", 5, Severity.MAJOR, null)));

        findings.recordVerdicts(REVIEW, 3, List.of(judged));

        assertEquals("RESOLVED", verdictForRound(1), "round 1 keeps the judgment it was given");
        assertNull(verdictForRound(2),
                "the current round's fresh finding must not inherit an older round's verdict");
    }

    /**
     * The same bound on the location path, which a never-posted finding takes.
     *
     * <p>A finding that failed to post carries no thread ref, so its verdict can only be matched by
     * location — and if the current round raised anything at that location, the newest row won and
     * the older one stayed unjudged forever.
     */
    @Test
    void aVerdictWithNoThreadRefJudgesTheOlderRowRatherThanThisRoundsNewOne() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(finding("src/B.java", 7, Severity.MINOR, null)));
        findings.recordGenerated(REVIEW, 2, COMMIT, List.of(finding("src/B.java", 7, Severity.MINOR, null)));

        findings.recordVerdicts(REVIEW, 2, List.of(
                new FindingVerdict(null, "src/B.java", 7, FindingVerdict.Status.ACKNOWLEDGED, null)));

        assertEquals("ACKNOWLEDGED", verdictForRound(1));
        assertNull(verdictForRound(2), "this round's finding has not been judged by anyone yet");
    }

    /**
     * <b>A redelivered {@code CommentsPosted} must re-stamp the row it stamped the first time.</b>
     *
     * <p>That handler has no idempotency guard of its own — unlike {@code ReviewGenerated}'s
     * {@code ifCurrentRun} — and "newest row still awaiting a ref" is not stable across two
     * deliveries: once round 2's row is stamped it stops being a candidate, so the second delivery
     * walked down and stamped round 1's never-posted row instead. That falsifies the
     * "generated, never posted" fact AND hands the verdict rule a thread ref pointing at the wrong
     * finding, so the two defects compound.
     */
    @Test
    void aRedeliveredCommentsPostedReStampsItsOwnRowRatherThanALaterUnpostedOne() {
        // Round 1 posts. Round 2 raises the same location and fails to post, so its row is NEWER
        // and still unattached — which is what makes this discriminate: "newest row awaiting a ref"
        // now points somewhere else entirely. The obvious ordering (round 2 posted, round 1 did not)
        // proves nothing, because there the correct row is also the newest one.
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(finding("src/C.java", 10, Severity.NIT, null)));
        var posted = List.of(new PostedInline("thread-b", "src/C.java", 10));
        findings.recordThreadRefs(REVIEW, posted);
        findings.recordGenerated(REVIEW, 2, COMMIT, List.of(finding("src/C.java", 10, Severity.NIT, null)));

        findings.recordThreadRefs(REVIEW, posted);

        assertEquals("thread-b", threadRefForRound(1), "the redelivery belongs to round 1's post");
        assertNull(threadRefForRound(2),
                "round 2 was generated and never posted — a redelivery must not rewrite that, and two "
                        + "rows claiming one thread ref would then mislead the verdict rule as well");
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

    private String threadRefForRound(int round) {
        return queryOne("SELECT thread_ref FROM review_finding WHERE review_id = ? AND round = ?"
                + " ORDER BY id DESC LIMIT 1", ps -> {
            ps.setString(1, REVIEW);
            ps.setInt(2, round);
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
