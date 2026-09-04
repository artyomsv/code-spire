package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-F32's two caps, and the attempt number a re-dispatch takes.
 *
 * <p><b>Two axes, because one does not bound the loop the requirement is about.</b> Counting per
 * FINDING stops repeated attempts at one stubborn finding. It cannot stop the runaway FR-F32 names —
 * a finding spawns a fix, whose review raises a finding, which spawns a fix — because every hop
 * raises a NEW finding with a new identity, so a per-finding counter sees one run each and never
 * reaches N while reporting itself satisfied. The per-REVIEW axis is what bounds that chain, and
 * under ADR-040 a fix pushes to the branch the review already watches, so one review IS the chain.
 */
@QuarkusTest
class FixRunsTest {

    private static final String REVIEW = "review::TEST-WS/TEST-REPO#7001";
    private static final String OTHER_REVIEW = "review::TEST-WS/TEST-REPO#7002";
    private static final String FINDING = "thread-aaa";
    private static final String OTHER_FINDING = "thread-bbb";

    @Inject
    FixRuns fixRuns;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM factory_run WHERE run_id LIKE 'run::TEST-%'");
    }

    @Test
    void countsNothingBeforeAnyFixRunExists() {
        assertEquals(0, fixRuns.forFinding(REVIEW, FINDING));
        assertEquals(0, fixRuns.forReview(REVIEW));
    }

    @Test
    void countsTheFixRunsAlreadyDispatchedForOneFinding() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);
        insertFixRun("run::TEST-2", REVIEW, FINDING);

        assertEquals(2, fixRuns.forFinding(REVIEW, FINDING));
    }

    /**
     * The per-review axis counts the whole chain, which is the point: each hop of the runaway is a
     * DIFFERENT finding, so a per-finding count of them all reads 1.
     */
    @Test
    void countsEveryFixRunOnAReviewAcrossDifferentFindings() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);
        insertFixRun("run::TEST-2", REVIEW, OTHER_FINDING);

        assertEquals(1, fixRuns.forFinding(REVIEW, FINDING), "each finding has one run of its own");
        assertEquals(1, fixRuns.forFinding(REVIEW, OTHER_FINDING));
        assertEquals(2, fixRuns.forReview(REVIEW), "but the review has seen two — this is the chain");
    }

    /** Two reviews are two chains; one must not spend the other's budget. */
    @Test
    void doesNotCountAnotherReviewsFixRuns() {
        insertFixRun("run::TEST-1", OTHER_REVIEW, FINDING);

        assertEquals(0, fixRuns.forFinding(REVIEW, FINDING));
        assertEquals(0, fixRuns.forReview(REVIEW));
    }

    /** A BUILD run carries no target, so it is outside both counts. */
    @Test
    void doesNotCountABuildRunThatCarriesNoFindingTarget() {
        insertBuildRun("run::TEST-build");

        assertEquals(0, fixRuns.forReview(REVIEW));
    }

    /**
     * <b>What actually keeps a non-fix run out of the fix budget, and it is not the query.</b>
     *
     * <p>Dropping {@code kind = 'FIX'} from the count survives every other test here, because the
     * only non-fix row a fixture can build has a null {@code review_id} and {@code WHERE review_id =
     * ?} excludes it anyway. The filter is belt-and-braces; the V54 CHECK is the guard — it makes
     * carrying a review and not being a fix an unrepresentable state.
     *
     * <p>So this asserts the CHECK. It matters because the constraint is exactly what loosens when
     * SPEC and PLAN runs arrive (both already admitted by the same CHECK's kind list): the moment a
     * non-fix run may carry a {@code review_id}, the redundant filter stops being redundant and this
     * test is what says so.
     */
    @Test
    void theDatabaseRefusesARunThatCarriesAReviewWithoutBeingAFix() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert("run::TEST-liar", "BUILD", REVIEW, FINDING));

        assertTrue(rootCause(refused).contains("factory_run_fix_names_its_target"),
                rootCause(refused));
    }

    /** And the other direction: a fix run must name what it fixes, or the cap cannot see it. */
    @Test
    void theDatabaseRefusesAFixRunThatNamesNoTarget() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert("run::TEST-untargeted", "FIX", null, null));

        assertTrue(rootCause(refused).contains("factory_run_fix_names_its_target"),
                rootCause(refused));
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    @Test
    void allowsAFixWhileBothCapsHaveRoom() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);

        assertTrue(fixRuns.decide(REVIEW, FINDING, 2, 5).allowed());
    }

    /** The per-finding cap: this finding has had its N. */
    @Test
    void refusesWhenTheFindingHasHadItsFixRuns() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);
        insertFixRun("run::TEST-2", REVIEW, FINDING);

        FixRuns.Decision decision = fixRuns.decide(REVIEW, FINDING, 2, 99);
        assertFalse(decision.allowed());
        assertTrue(decision.why().contains("this finding"), decision.why());
    }

    /**
     * The per-review cap, on findings that have each had ONE run — the exact shape the per-finding
     * axis cannot see, and the reason there are two.
     */
    @Test
    void refusesWhenTheReviewHasHadItsFixRunsAcrossDifferentFindings() {
        insertFixRun("run::TEST-1", REVIEW, "thread-1");
        insertFixRun("run::TEST-2", REVIEW, "thread-2");
        insertFixRun("run::TEST-3", REVIEW, "thread-3");

        FixRuns.Decision decision = fixRuns.decide(REVIEW, "thread-4", 2, 3);
        assertFalse(decision.allowed(), "per-finding sees zero for thread-4; per-review sees three");
        assertTrue(decision.why().contains("this pull request"), decision.why());
    }

    /** Unset means unlimited, matching every other cap in this deployment (ADR-025). */
    @Test
    void treatsANonPositiveCapAsUnlimited() {
        insertFixRun("run::TEST-1", REVIEW, FINDING);
        insertFixRun("run::TEST-2", REVIEW, FINDING);

        assertTrue(fixRuns.decide(REVIEW, FINDING, 0, 0).allowed());
    }

    /**
     * The attempt a re-dispatch takes.
     *
     * <p>{@code RunIds} embeds the attempt, and {@code RunResource} pins it to 1 with a 409 on any
     * repeat — "M0 runs each subject once". FR-F32's N is unreachable while that holds, because every
     * retry of one finding would collide on the same run id.
     */
    @Test
    void countsTheNextAttemptFromTheRunsAlreadyDispatched() {
        assertEquals(1, fixRuns.nextAttempt(REVIEW, FINDING), "the first fix for a finding is attempt 1");

        insertFixRun("run::TEST-1", REVIEW, FINDING);
        assertEquals(2, fixRuns.nextAttempt(REVIEW, FINDING));
    }

    // --- fixtures ---------------------------------------------------------------------------

    private void insertFixRun(String runId, String reviewId, String findingRef) {
        insert(runId, "FIX", reviewId, findingRef);
    }

    private void insertBuildRun(String runId) {
        insert(runId, "BUILD", null, null);
    }

    private void insert(String runId, String kind, String reviewId, String findingRef) {
        exec("""
                INSERT INTO factory_run (run_id, provider_type, workspace, slug, subject, attempt,
                                         status, harness, model, base_branch, base_commit, branch,
                                         kind, review_id, finding_ref)
                VALUES (?, 'github', 'TEST-WS', 'TEST-REPO', 'TEST-SUBJECT', 1,
                        'queued', 'codex', 'TEST-MODEL', 'main', 'TESTSHA0', 'spire/TEST-SUBJECT',
                        ?, ?, ?)
                """, runId, kind, reviewId, findingRef);
    }

    private void exec(String sql, String... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
