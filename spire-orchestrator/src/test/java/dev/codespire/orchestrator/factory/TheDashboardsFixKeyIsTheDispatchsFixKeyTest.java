package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.IntegrationEvent.CommentsPosted.PostedInline;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingCategory;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.Severity;
import dev.codespire.orchestrator.readmodel.FindingProjection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference a finding shows is the reference a fix run is keyed on — one value, not two.
 *
 * <p>M2's roadmap asks for "a finding reference on {@code ReviewDetail.FindingView}, so the dashboard
 * has an id to dispatch with". <b>It is already there</b>, and it arrived through conversation
 * linking rather than through this task: {@code FindingView.threadRef}. So the work T8 owes is not a
 * new field — it is the assertion that the two are the SAME key, which nothing made.
 *
 * <p>That matters because they are reached by different code. The dashboard reads
 * {@code review_finding.thread_ref} through {@code ReviewProjection}; the dispatch resolves a comment
 * to its conversation root and calls {@code FindingProjection.findByThread} with it, and both fix
 * caps count on that same value. Two paths to one key is the shape this branch has already paid for
 * — two encodings of one rule that agree until one is edited.
 *
 * <p><b>And the key is deliberately NOT {@code review_finding.id}.</b> P4 rewrites those rows
 * delete-then-insert per {@code (review_id, round)}, so the id is not stable across rounds: a
 * dashboard that dispatched by it would send a run at a finding that no longer exists. ADR-019's
 * reconciliation keys on the thread ref for the same reason, and this pins that the fix path agrees.
 */
@QuarkusTest
class TheDashboardsFixKeyIsTheDispatchsFixKeyTest {

    private static final String REVIEW = "review::TEST-WS/TEST-REPO#8500";
    private static final String COMMIT = "TESTSHA000000000000000000000000000000500";
    private static final String THREAD = "TEST-thread-8500";

    @Inject
    FindingProjection findings;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM review_finding WHERE review_id LIKE 'review::TEST-%'");
    }

    /**
     * Drive a finding all the way to a posted comment, then look it up the way {@code /fix} does.
     *
     * <p>The lookup uses the value the DASHBOARD would render, not a value this test invented — that
     * is the whole point. If the two ever diverge, the ref shown to a person stops resolving and the
     * button built on it refuses every finding with "I could not match this thread".
     */
    @Test
    void theRefAFindingShowsIsTheRefTheFixLookupResolves() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(new Finding("src/A.java",
                new LineRange(10, 14), Severity.BLOCKER, FindingCategory.SECURITY,
                "the lock is taken in the opposite order", null)));
        findings.recordThreadRefs(REVIEW, List.of(
                new PostedInline(THREAD, "src/A.java", 10)));

        String shown = threadRefColumn("src/A.java");
        assertNotNull(shown, "the finding must carry a ref at all, or there is nothing to dispatch with");
        assertEquals(THREAD, shown);

        // And that exact value resolves through the path the dispatch takes.
        FindingProjection.TargetFinding target = findings.findByThread(REVIEW, shown).orElseThrow();
        assertEquals("src/A.java", target.path());
        assertEquals(10, target.startLine());
    }

    /**
     * A finding with no posted comment carries NO ref, rather than a blank one.
     *
     * <p>A blank would render as a dispatchable control that cannot work: the lookup would match
     * nothing and the author would be told the thread names no finding, about a finding right in
     * front of them. Null is the honest "there is nothing to dispatch against yet".
     */
    @Test
    void aFindingWithNoThreadYetCarriesNoRefRatherThanABlankOne() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(new Finding("src/B.java",
                new LineRange(20, 20), Severity.NIT, null, "a nit", null)));

        assertNull(threadRefColumn("src/B.java"),
                "generated but never posted, so there is no conversation to fix against");
    }

    /**
     * <b>The row id is NOT the key: re-recording a round REPLACES the row the id named.</b>
     *
     * <p>An earlier version of this test asserted the new id differed from the old one, and a
     * review showed that assertion could not fail. {@code FIND_BY_THREAD} is
     * {@code ORDER BY id DESC LIMIT 1} over a monotonic serial, so the id it returns after a second
     * insert is higher BY CONSTRUCTION — delete the delete-then-insert entirely and both rows would
     * exist, the newer one would still win the ORDER BY, and the test would still pass. It measured
     * the sequence, not the replacement.
     *
     * <p>What discriminates is that the OLD row is gone. That is what makes an id unusable as a
     * dispatch key: a dashboard holding one would aim a paid run at a row that no longer exists.
     *
     * <p>This re-records the SAME round, which is what {@code deleteRound} is scoped to — the
     * earlier comment said "round two" while passing {@code round = 1}, and the code was the honest
     * half. A genuine second round would not delete round one's row at all.
     */
    @Test
    void reRecordingARoundReplacesTheRowSoItsIdCannotBeADispatchKey() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(new Finding("src/A.java",
                new LineRange(10, 14), Severity.BLOCKER, FindingCategory.SECURITY, "m", null)));
        findings.recordThreadRefs(REVIEW, List.of(new PostedInline(THREAD, "src/A.java", 10)));
        long idBefore = findings.findByThread(REVIEW, THREAD).orElseThrow().id();

        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(new Finding("src/A.java",
                new LineRange(10, 14), Severity.BLOCKER, FindingCategory.SECURITY, "m", null)));
        findings.recordThreadRefs(REVIEW, List.of(new PostedInline(THREAD, "src/A.java", 10)));

        assertEquals(0, rowsWithId(idBefore),
                "the row a dashboard would have dispatched by is GONE, not merely outranked");
        assertEquals(1, rowsAtPath("src/A.java"),
                "and replaced rather than duplicated — ORDER BY id DESC would have hidden a duplicate");
        assertEquals(THREAD, threadRefColumn("src/A.java"), "while the ref is the same one throughout");
    }

    private int rowsWithId(long id) {
        return count("SELECT count(*) FROM review_finding WHERE id = " + id);
    }

    private int rowsAtPath(String path) {
        return count("SELECT count(*) FROM review_finding WHERE review_id = '" + REVIEW
                + "' AND path = '" + path + "'");
    }

    /** Test-only, over literals this file controls; no caller-supplied value reaches it. */
    private int count(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private String threadRefColumn(String path) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT thread_ref FROM review_finding WHERE review_id = ? AND path = ?"
                             + " ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, REVIEW);
            ps.setString(2, path);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the thread ref for " + path, e);
        }
    }

    private void exec(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
