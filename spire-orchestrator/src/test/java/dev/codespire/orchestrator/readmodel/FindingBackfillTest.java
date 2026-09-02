package dev.codespire.orchestrator.readmodel;

import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovering the findings the read model still holds (ADR-027, amended).
 *
 * <p>ADR-027 declined a backfill because a salvage would be "one unrepresentative round per review
 * with no verdicts". Measured against a real deployment, half of that is false — {@code
 * reconciliation_json} carries the ADR-019 verdicts for every review that ran a second round. These
 * tests pin the half that is still true, which is what the recovered rows must NOT claim: no
 * category, and no round history.
 */
@QuarkusTest
class FindingBackfillTest {

    private static final String WS = "TEST-BACKFILL-WS";

    @Inject
    FindingBackfill backfill;

    @Inject
    EncryptionService encryption;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM review_finding WHERE review_id LIKE 'review::" + WS + "/%'");
        exec("DELETE FROM review_status WHERE workspace = '" + WS + "'");
        exec("DELETE FROM app_setting WHERE key = '" + FindingBackfill.DONE_KEY + "'");
    }

    @Test
    void recoversAStoredFindingWithItsSeverityAndLocation() {
        seed("#1", """
                [{"sev":"critical","loc":"src/Main.java:42","msg":"TEST-MESSAGE","threadRef":"t-1"}]
                """, null);

        backfill.run();

        Row row = only(reviewId("#1"));
        assertEquals("src/Main.java", row.path());
        assertEquals(42, row.startLine());
        assertEquals(42, row.endLine());
        assertEquals("BLOCKER", row.severity());
        assertEquals("t-1", row.threadRef());
    }

    /** A multi-line finding keeps its span; the location is `path:start-end`. */
    @Test
    void recoversAMultiLineLocation() {
        seed("#2", """
                [{"sev":"warning","loc":"src/Pay.java:10-14","msg":"TEST-MESSAGE","threadRef":null}]
                """, null);

        backfill.run();

        Row row = only(reviewId("#2"));
        assertEquals(10, row.startLine());
        assertEquals(14, row.endLine());
        assertEquals("MAJOR", row.severity());
        assertNull(row.threadRef(), "a finding that was never posted has no thread, and that is a fact");
    }

    /**
     * The half of ADR-027's premise that measurement falsified. A review that ran a second round
     * carries its verdicts, and recovering them is the difference between a dismissal rate and a
     * column of blanks.
     */
    @Test
    void recoversTheVerdictOfAReconciledFinding() {
        seed("#3", """
                [{"sev":"warning","loc":"src/Pay.java:10","msg":"TEST-MESSAGE","threadRef":"t-3"}]
                """, """
                [{"sev":"warning","loc":"src/Pay.java:10","msg":"TEST-MESSAGE","status":"resolved",
                  "note":"TEST-NOTE","threadRef":"t-3","resolvedThread":true}]
                """);

        backfill.run();

        assertEquals("RESOLVED", only(reviewId("#3")).verdict());
    }

    /**
     * The round is genuinely gone, so it must not be invented. Round 0 says "before this record
     * began"; filing these as round 1 would claim every finding was raised first-round.
     */
    @Test
    void filesRecoveredRowsOutsideTheRealRoundNumbering() {
        seed("#4", """
                [{"sev":"nit","loc":"src/A.java:3","msg":"TEST-MESSAGE","threadRef":null}]
                """, null);

        backfill.run();

        assertEquals(0, only(reviewId("#4")).round());
    }

    /**
     * The tile this protects is "median rounds to fix", computed as
     * {@code verdict_round - round + 1}. A recovered row with both filled from one snapshot would
     * contribute 1.0 — every time, on any deployment, confidently. Leaving verdict_round null keeps
     * these rows out of the median entirely, which is the honest answer for a round nobody recorded.
     */
    @Test
    void leavesTheVerdictRoundNullSoTheMedianCannotBeFabricated() {
        seed("#5", """
                [{"sev":"warning","loc":"src/Pay.java:10","msg":"TEST-MESSAGE","threadRef":"t-5"}]
                """, """
                [{"sev":"warning","loc":"src/Pay.java:10","msg":"TEST-MESSAGE","status":"resolved",
                  "note":"TEST-NOTE","threadRef":"t-5","resolvedThread":true}]
                """);

        backfill.run();

        Row row = only(reviewId("#5"));
        assertEquals("RESOLVED", row.verdict());
        assertNull(row.verdictRound(), "the round a finding was resolved in was never recorded");
    }

    /**
     * A finding the author FIXED has already left the open snapshot, so matching verdicts against
     * that snapshot alone recovers only the ones still open. Measured on a real deployment that gave
     * 15 UNCHANGED and zero RESOLVED -- a "Fixed" column reading zero forever on a repository whose
     * findings plainly do get fixed. The reconciliation record still describes those findings in
     * full, so they are recovered from there.
     */
    @Test
    void recoversAFindingThatWasFixedAndHasLeftTheOpenSet() {
        seed("#14", """
                [{"sev":"warning","loc":"src/StillOpen.java:5","msg":"TEST-OPEN","threadRef":"t-open"}]
                """, """
                [{"sev":"critical","loc":"src/Fixed.java:9","msg":"TEST-FIXED","status":"resolved",
                  "note":"TEST-NOTE","threadRef":"t-fixed","resolvedThread":true}]
                """);

        backfill.run();

        List<Row> found = rows(reviewId("#14"));
        assertEquals(2, found.size(), "the fixed finding is recovered alongside the open one");
        Row fixed = found.stream().filter(r -> r.path().equals("src/Fixed.java")).findFirst().orElseThrow();
        assertEquals("RESOLVED", fixed.verdict());
        assertEquals("BLOCKER", fixed.severity());
        assertNull(fixed.verdictRound(), "still no round, even for one we know was settled");
    }

    /** A settled finding still present in the snapshot is one row, not two. */
    @Test
    void doesNotDuplicateAFindingThatIsInBothRecords() {
        seed("#15", """
                [{"sev":"warning","loc":"src/Both.java:3","msg":"TEST-MESSAGE","threadRef":"t-b"}]
                """, """
                [{"sev":"warning","loc":"src/Both.java:3","msg":"TEST-MESSAGE","status":"unchanged",
                  "note":"TEST-NOTE","threadRef":"t-b","resolvedThread":false}]
                """);

        backfill.run();

        assertEquals("UNCHANGED", only(reviewId("#15")).verdict());
    }

    /** The category field did not exist. Null already means "the model was not asked". */
    @Test
    void leavesTheCategoryNullBecauseNoneWasEverCollected() {
        seed("#6", """
                [{"sev":"warning","loc":"src/A.java:1","msg":"TEST-MESSAGE","threadRef":null}]
                """, null);

        backfill.run();

        assertNull(only(reviewId("#6")).category());
    }

    /** The message quotes the source under review, so it is encrypted exactly as a live row is. */
    @Test
    void storesTheMessageEncrypted() {
        seed("#7", """
                [{"sev":"warning","loc":"src/A.java:1","msg":"TEST-SECRET-MESSAGE","threadRef":null}]
                """, null);

        backfill.run();

        String stored = only(reviewId("#7")).message();
        assertFalse(stored.contains("TEST-SECRET-MESSAGE"), "a recovered message must not be in clear");
        assertEquals("TEST-SECRET-MESSAGE", encryption.decryptString(stored, reviewId("#7")));
    }

    /**
     * Once ever, not once per boot. Without the marker every restart would re-read every review, and
     * on a deployment with real history that is work with no result.
     */
    @Test
    void runsOnlyOnce() {
        seed("#8", """
                [{"sev":"warning","loc":"src/A.java:1","msg":"TEST-MESSAGE","threadRef":null}]
                """, null);

        // Not an exact count: run() is deployment-wide by design, and this suite shares its
        // database with every other one. What matters is that it did work and then refused to again.
        assertTrue(backfill.run() >= 1);
        assertEquals(-1, backfill.run(), "a second run must recognise it has already happened");
    }

    /**
     * A verdict spelling this build does not know is skipped, not defaulted. A wrong verdict inflates
     * the dismissal rate that decides whether the reviewer starts hiding findings.
     */
    @Test
    void skipsAVerdictItCannotRecognise() {
        seed("#9", """
                [{"sev":"warning","loc":"src/A.java:1","msg":"TEST-MESSAGE","threadRef":"t-9"}]
                """, """
                [{"sev":"warning","loc":"src/A.java:1","msg":"TEST-MESSAGE","status":"something else",
                  "note":"TEST-NOTE","threadRef":"t-9","resolvedThread":false}]
                """);

        backfill.run();

        assertNull(only(reviewId("#9")).verdict());
    }

    /** An unreadable column costs its own review's history, never the whole recovery. */
    @Test
    void keepsGoingWhenOneReviewCannotBeRead() {
        seedRaw("#10", "not-json-and-not-ciphertext", null);
        seed("#11", """
                [{"sev":"warning","loc":"src/A.java:1","msg":"TEST-MESSAGE","threadRef":null}]
                """, null);

        backfill.run();

        assertTrue(rows(reviewId("#10")).isEmpty(), "the unreadable review recovers nothing");
        assertEquals(1, rows(reviewId("#11")).size(), "and the readable one beside it still does");
    }

    /**
     * Round 0 is reserved, so a re-run replaces its own rows and never a real one — which is what
     * makes running this again safe if a future version recovers more.
     */
    @Test
    void replacesOnlyItsOwnRowsOnASecondRun() {
        seed("#12", """
                [{"sev":"warning","loc":"src/A.java:1","msg":"TEST-MESSAGE","threadRef":null}]
                """, null);
        backfill.run();
        insertRealRound(reviewId("#12"));

        exec("DELETE FROM app_setting WHERE key = '" + FindingBackfill.DONE_KEY + "'");
        backfill.run();

        List<Row> all = rows(reviewId("#12"));
        assertEquals(2, all.size(), "the real round must survive a re-run of the backfill");
        assertTrue(all.stream().anyMatch(r -> r.round() == 1));
        assertEquals(1, all.stream().filter(r -> r.round() == 0).count());
    }

    /** Nothing stored, nothing invented. */
    @Test
    void recoversNothingFromAReviewThatFoundNothing() {
        seed("#13", "[]", null);

        backfill.run();

        assertTrue(rows(reviewId("#13")).isEmpty());
    }

    // ---- fixtures ----------------------------------------------------------

    private static String reviewId(String pr) {
        return "review::" + WS + "/TEST-REPO" + pr;
    }

    /** Seeds a review whose findings column is encrypted, exactly as the projection writes it. */
    private void seed(String pr, String findingsJson, String reconciliationJson) {
        String id = reviewId(pr);
        seedRaw(pr, encryption.encryptString(findingsJson, id),
                reconciliationJson == null ? null : encryption.encryptString(reconciliationJson, id));
    }

    private void seedRaw(String pr, String findingsColumn, String reconciliationColumn) {
        String sql = """
                INSERT INTO review_status (review_id, workspace, slug, pr_id, commit_sha, status,
                                           findings_json, reconciliation_json)
                VALUES (?, ?, 'TEST-REPO', ?, 'TESTSHA', 'completed', ?, ?)
                ON CONFLICT (review_id) DO NOTHING
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId(pr));
            ps.setString(2, WS);
            ps.setLong(3, Long.parseLong(pr.substring(1)));
            ps.setString(4, findingsColumn);
            ps.setString(5, reconciliationColumn);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed a review", e);
        }
    }

    /** A row from a real review round, to prove the backfill leaves it alone. */
    private void insertRealRound(String reviewId) {
        String sql = """
                INSERT INTO review_finding (review_id, round, commit_sha, path, start_line, end_line,
                                            severity, origin)
                VALUES (?, 1, 'TESTSHA', 'src/Real.java', 7, 7, 'MAJOR', 'review')
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not insert a real round", e);
        }
    }

    private record Row(int round, String path, int startLine, int endLine, String severity,
                       String category, String message, String threadRef, String verdict,
                       Integer verdictRound) {
    }

    private Row only(String reviewId) {
        List<Row> found = rows(reviewId);
        assertEquals(1, found.size(), "expected exactly one recovered row for " + reviewId);
        return found.getFirst();
    }

    private List<Row> rows(String reviewId) {
        String sql = """
                SELECT round, path, start_line, end_line, severity, category, message, thread_ref,
                       verdict, verdict_round
                  FROM review_finding WHERE review_id = ? ORDER BY round, id
                """;
        List<Row> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Captured immediately: wasNull() reports on the LAST column read, so asking it
                    // after four more getters answers about `verdict` and a null round reads as 0.
                    int rawVerdictRound = rs.getInt("verdict_round");
                    Integer verdictRound = rs.wasNull() ? null : rawVerdictRound;
                    out.add(new Row(rs.getInt("round"), rs.getString("path"), rs.getInt("start_line"),
                            rs.getInt("end_line"), rs.getString("severity"), rs.getString("category"),
                            rs.getString("message"), rs.getString("thread_ref"), rs.getString("verdict"),
                            verdictRound));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read recovered rows", e);
        }
        return out;
    }

    private void exec(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}
