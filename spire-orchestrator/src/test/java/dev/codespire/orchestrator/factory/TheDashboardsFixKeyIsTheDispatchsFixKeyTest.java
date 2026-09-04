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

        assertEquals(null, threadRefColumn("src/B.java"),
                "generated but never posted, so there is no conversation to fix against");
    }

    /**
     * <b>The row id is NOT the key, and this shows why.</b>
     *
     * <p>P4 rewrites a review's findings delete-then-insert per {@code (review_id, round)}, so a
     * second round replaces the row and its id with it. The thread ref survives that; the id does
     * not. A dashboard that dispatched by id would aim a paid run at a row that no longer exists.
     */
    @Test
    void theRowIdDoesNotSurviveARoundButTheThreadRefDoes() {
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(new Finding("src/A.java",
                new LineRange(10, 14), Severity.BLOCKER, FindingCategory.SECURITY, "m", null)));
        findings.recordThreadRefs(REVIEW, List.of(new PostedInline(THREAD, "src/A.java", 10)));
        long idInRoundOne = findings.findByThread(REVIEW, THREAD).orElseThrow().id();

        // Round two re-records the same review's findings, which is a delete-then-insert.
        findings.recordGenerated(REVIEW, 1, COMMIT, List.of(new Finding("src/A.java",
                new LineRange(10, 14), Severity.BLOCKER, FindingCategory.SECURITY, "m", null)));
        findings.recordThreadRefs(REVIEW, List.of(new PostedInline(THREAD, "src/A.java", 10)));
        long idAfter = findings.findByThread(REVIEW, THREAD).orElseThrow().id();

        assertTrue(idInRoundOne != idAfter,
                "if the id survived, this test proves nothing about why the ref is the key");
        assertEquals(THREAD, threadRefColumn("src/A.java"), "and the ref is the same one throughout");
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
