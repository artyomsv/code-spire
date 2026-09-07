package dev.codespire.orchestrator.factory;

import dev.codespire.contract.scm.RepoRef;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** Seeded first, or this cannot tell a working WHERE clause from an empty table. */
    @Test
    void answersEmptyForAReviewItHasNeverSeen() {
        insert("OPEN", "feature/login", "develop", "TESTSHA1");

        assertTrue(targets.forReview("review::TEST-WS/TEST-REPO#404").isEmpty());
    }

    /**
     * Whitespace is not empty, and the distinction is the whole reason this tests blank.
     * {@code isEmpty()} in place of {@code isBlank()} passed every other case here — nothing
     * seeded a whitespace ref, so the difference the javadoc argues for was asserted by nothing.
     */
    @Test
    void refusesARowWhoseSourceBranchIsOnlyWhitespace() {
        insert("OPEN", "   ", "develop", "TESTSHA1");

        assertFalse(targets.forReview(REVIEW).orElseThrow().isPushable());
    }

    /** Whitespace on the commit too — it was seeded for the branch only, and the asymmetry hid a bug. */
    @Test
    void refusesARowWhoseCommitIsOnlyWhitespace() {
        insert("OPEN", "feature/login", "develop", "   ");

        assertFalse(targets.forReview(REVIEW).orElseThrow().isPushable());
    }

    /** {@code commit_sha} carries the same blank default and the same in-container failure. */
    @Test
    void refusesARowWhoseCommitWasNeverRecorded() {
        insert("OPEN", "feature/login", "develop", "");

        assertFalse(targets.forReview(REVIEW).orElseThrow().isPushable(),
                "a blank commit fails Env.required inside the container, after the agent is paid");
    }

    /**
     * {@code dest_branch} is the THIRD column with {@code NOT NULL DEFAULT ''}, and it was the one
     * left unguarded while the two beside it were tested twice.
     *
     * <p>Not cosmetic: this value becomes the run command's protected branch, and
     * {@code ExecuteRun}'s compact constructor THROWS on a blank one in existing mode. So the
     * missing clause turned a refusal-with-a-reason into an exception — and an exception on a Kafka
     * consumer is a redelivery, i.e. the same review refusing forever with no message to the author.
     */
    @Test
    void refusesARowWhoseDestinationBranchWasNeverRecorded() {
        for (String blank : new String[] {"", "   "}) {
            exec("DELETE FROM review_status WHERE review_id LIKE 'review::TEST-%'");
            insert("OPEN", "feature/login", blank, "TESTSHA1");

            assertFalse(targets.forReview(REVIEW).orElseThrow().isPushable(), "dest='" + blank + "'");
        }
    }

    /**
     * A row written before V55 says NULL, and NULL is not false.
     *
     * <p>This is the case {@code rs.getBoolean} silently converts: it maps SQL NULL to false, so a
     * pre-V55 fork review would read as a branch pull request and be pushable. The whole reason V55
     * leaves the column nullable is to keep that state distinguishable, and this is the only test
     * that can tell the two readers apart.
     */
    @Test
    void refusesARowWrittenBeforeTheDeploymentCouldSeeForks() {
        insert("OPEN", "feature/login", "develop", "TESTSHA1");
        exec("UPDATE review_status SET from_fork = NULL WHERE review_id = ?", REVIEW);

        FixTargets.PushTarget target = targets.forReview(REVIEW).orElseThrow();
        assertNull(target.fromFork(), "NULL must survive the read, not arrive as false");
        assertFalse(target.isPushable());
        assertEquals(FixTargets.Unpushable.PROVENANCE_UNKNOWN,
                target.whyNotPushable().orElseThrow(),
                "an unrecorded provenance is not the same answer as 'this is a fork'");
    }

    /** No forge opens a pull request from a branch onto itself; the guard is against the ROW, not the forge. */
    @Test
    void refusesARowWhoseSourceAndDestinationAreTheSameBranch() {
        insert("OPEN", "feature/login", "feature/login", "TESTSHA1");

        assertEquals(FixTargets.Unpushable.SOURCE_IS_DESTINATION,
                targets.forReview(REVIEW).orElseThrow().whyNotPushable().orElseThrow());
    }

    /** ADR-040 §3 asks for a repository match, and a row that resolves is not a row that matches. */
    @Test
    void belongsOnlyToTheRepositoryItNames() {
        insert("OPEN", "feature/login", "develop", "TESTSHA1");
        FixTargets.PushTarget target = targets.forReview(REVIEW).orElseThrow();

        assertTrue(target.belongsTo(new RepoRef("TEST-WS", "TEST-REPO")));

        assertFalse(target.belongsTo(new RepoRef("OTHER-WS", "TEST-REPO")), "workspace must match");
        assertFalse(target.belongsTo(new RepoRef("TEST-WS", "OTHER-REPO")), "slug must match");
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
     * The one shape ADR-040 puts out of scope, and the deployment could not see it until now.
     *
     * <p>A fork's source branch lives in ANOTHER repository while the clone URL is built from this
     * row's workspace and slug, so pushing the name resolves against the wrong repository: either a
     * stray branch attached to no pull request, or a machine-authored commit from a different diff
     * landing on an unrelated branch of the same name.
     */
    @Test
    void refusesAPullRequestFromAFork() {
        insertFork("OPEN", "feature/login", "develop", "TESTSHA1");

        assertFalse(targets.forReview(REVIEW).orElseThrow().isPushable());
    }

    @Test
    void aPushableTargetIsOpenSameRepositoryAndNamesABranch() {
        insert("OPEN", "feature/login", "develop", "TESTSHA1");

        FixTargets.PushTarget target = targets.forReview(REVIEW).orElseThrow();
        assertFalse(target.fromFork(), "a same-repository pull request is not a fork");
        assertTrue(target.isPushable());
    }

    // --- fixtures ---------------------------------------------------------------------------

    private void insertFork(String prState, String sourceBranch, String destBranch, String commit) {
        insert(prState, sourceBranch, destBranch, commit, true);
    }

    private void insert(String prState, String sourceBranch, String destBranch, String commit) {
        insert(prState, sourceBranch, destBranch, commit, false);
    }

    private void insert(String prState, String sourceBranch, String destBranch, String commit,
                        boolean fromFork) {
        // Every parameter bound explicitly and in order. The helper used to prepend the review id
        // for anything starting "INSERT", which silently shifted the offset for any other
        // statement that took one — an UPDATE added later would bind wrong and fail confusingly.
        exec("""
                INSERT INTO review_status (review_id, workspace, slug, pr_id, status, commit_sha,
                                           source_branch, dest_branch, pr_state, provider_type,
                                           from_fork)
                VALUES (?, 'TEST-WS', 'TEST-REPO', 8001, 'completed', ?, ?, ?, ?, 'github', ?)
                """, REVIEW, commit, sourceBranch, destBranch, prState, String.valueOf(fromFork));
    }

    private void exec(String sql, String... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String arg : args) {
                if ("true".equals(arg) || "false".equals(arg)) {
                    ps.setBoolean(i++, Boolean.parseBoolean(arg));
                } else {
                    ps.setString(i++, arg);
                }
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
