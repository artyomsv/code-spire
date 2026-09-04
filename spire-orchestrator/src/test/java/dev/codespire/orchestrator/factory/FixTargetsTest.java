package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a fix run is allowed to push, resolved from the review's own row.
 *
 * <p><b>This is the ORCHESTRATOR's half of ADR-040.</b> The publisher refuses trunks and the pull
 * request's destination branch, but it cannot tell whether a branch really is an open pull request's
 * source branch — it holds a write credential and by ADR-039 makes no API call. So the proof lives
 * here, against {@code review_status}, and the publisher's checks are the floor that survives a bug
 * in it rather than the identification.
 */
@QuarkusTest
class FixTargetsTest {

    private static final String REVIEW = "review::TEST-WS/TEST-REPO#8001";

    @Inject
    FixTargets targets;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM review_status WHERE review_id LIKE 'review::TEST-%'");
    }

    @Test
    void resolvesAnOpenPullRequestsSourceBranch() {
        insert("OPEN", "feature/login", "develop", "TESTSHA1");

        FixTargets.PushTarget target = targets.forReview(REVIEW).orElseThrow();
        assertEquals("feature/login", target.sourceBranch());
        assertEquals("develop", target.destBranch());
        assertEquals("TESTSHA1", target.commit());
        assertEquals("github", target.providerType());
        assertEquals("TEST-WS", target.workspace());
        assertEquals("TEST-REPO", target.slug());
        assertEquals(8001L, target.prId());
    }

    @Test
    void answersEmptyForAReviewItHasNeverSeen() {
        assertTrue(targets.forReview("review::TEST-WS/TEST-REPO#404").isEmpty());
    }

    /**
     * A merged or closed pull request has no branch worth pushing to — the fix would land on a ref
     * nobody is reviewing, and reconciliation would never run because no further review round will.
     */
    @Test
    void refusesAPullRequestThatIsNoLongerOpen() {
        for (String state : java.util.List.of("MERGED", "CLOSED")) {
            exec("DELETE FROM review_status WHERE review_id LIKE 'review::TEST-%'");
            insert(state, "feature/login", "develop", "TESTSHA1");

            Optional<FixTargets.PushTarget> target = targets.forReview(REVIEW);
            assertTrue(target.isPresent(), state + " still resolves — the caller decides");
            assertFalse(target.get().isPushable(), state + " must not be pushable");
        }
    }

    /**
     * A row whose source branch was never recorded cannot be pushed to.
     *
     * <p>{@code source_branch} defaults to the empty string, not null, so the obvious null check
     * would pass it straight through and hand the publisher a blank ref. That blank then fails
     * {@code Repository.isValidRefName} deep inside a container, after the agent has been paid.
     */
    @Test
    void refusesARowWhoseSourceBranchWasNeverRecorded() {
        insert("OPEN", "", "develop", "TESTSHA1");

        assertFalse(targets.forReview(REVIEW).orElseThrow().isPushable());
    }

    /**
     * <b>NOT asserted, because the system cannot answer it.</b> ADR-040 puts fork pull requests out
     * of scope for {@code existing} mode, and nothing in this deployment records whether a pull
     * request came from a fork — not {@code review_status}, not {@code PullRequestEventReceived}.
     * A fork's source branch name would resolve here and be pushed against the BASE repository,
     * creating a stray branch or, worse, landing on an unrelated branch of the same name.
     *
     * <p>Tracked as a blocking gap rather than faked with a column that is always false, which would
     * read as a check and be none. See
     * {@code techdebt/spire-orchestrator/2-3-a-fork-pull-request-is-indistinguishable-from-a-branch-one.md}.
     */
    @Test
    void aPushableTargetIsOpenAndNamesABranch() {
        insert("OPEN", "feature/login", "develop", "TESTSHA1");

        assertTrue(targets.forReview(REVIEW).orElseThrow().isPushable());
    }

    // --- fixtures ---------------------------------------------------------------------------

    private void insert(String prState, String sourceBranch, String destBranch, String commit) {
        exec("""
                INSERT INTO review_status (review_id, workspace, slug, pr_id, status, commit_sha,
                                           source_branch, dest_branch, pr_state, provider_type)
                VALUES (?, 'TEST-WS', 'TEST-REPO', 8001, 'completed', ?, ?, ?, ?, 'github')
                """, commit, sourceBranch, destBranch, prState);
    }

    private void exec(String sql, String... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (sql.startsWith("INSERT")) {
                ps.setString(i++, REVIEW);
            }
            for (String arg : args) {
                ps.setString(i++, arg);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
