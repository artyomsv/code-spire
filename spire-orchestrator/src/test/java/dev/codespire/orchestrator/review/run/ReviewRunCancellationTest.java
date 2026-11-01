package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.factory.RunCommandEmitter;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A review's harness run is stopped when its head is superseded or its pull request closes, and nothing else is: not a
 * finished run, not the new head's run, and never a factory run of the same review. Rows are written as raw SQL, so
 * the selection is proven against the real schema rather than one careful writer.
 */
@QuarkusTest
class ReviewRunCancellationTest {

    private static final String OLD = "a".repeat(40);
    private static final String NEW = "b".repeat(40);

    @Inject
    ReviewRunCancellation cancellation;

    @Inject
    DataSource dataSource;

    private final List<RunCommand.CancelRun> cancelled = new ArrayList<>();
    private String reviewId;

    @BeforeEach
    void setUp() {
        reviewId = "review::TEST-V107C/TEST-REPO#" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000);
        QuarkusMock.installMockForType(new RunCommandEmitter() {
            @Override public void control(RunCommand command) { cancelled.add((RunCommand.CancelRun) command); }
            @Override public void dispatch(RunCommand command) { throw new AssertionError("a cancel is control"); }
            @Override public void dispatchReview(RunCommand command) { throw new AssertionError("a cancel is control"); }
        }, RunCommandEmitter.class);
    }

    @Test
    void aSupersededRoundCancelsItsLiveRun() {
        String older = reviewRun(new ReviewRunRow("REVIEW", "running", OLD, 1));

        cancellation.supersede(reviewId, 1);

        assertEquals(Set.of(older), runIds());
        assertTrue(cancelled.getFirst().reason().contains("supersed"), cancelled.getFirst().reason());
    }

    @Test
    void aSupersedeAfterTheRunFinishedSendsNothing() {
        reviewRun(new ReviewRunRow("REVIEW", "succeeded", OLD, 1));

        cancellation.supersede(reviewId, 1);

        assertEquals(List.of(), cancelled);
    }

    @Test
    void theNewHeadsOwnRunIsNotCancelled() {
        String older = reviewRun(new ReviewRunRow("REVIEW", "running", OLD, 1));
        reviewRun(new ReviewRunRow("RECONCILE", "queued", NEW, 2));

        cancellation.supersede(reviewId, 1);

        assertEquals(Set.of(older), runIds());
    }

    /**
     * A forced re-run supersedes round 1 and requests round 2 on the SAME head (ReviewLifecycle.decideRequestReview),
     * so the cancel is keyed by the round, never the commit: round 2's run on that head keeps running (review of PR #367).
     */
    @Test
    void aForcedRerunsRunOnTheSameHeadIsNotCancelled() {
        String older = reviewRun(new ReviewRunRow("REVIEW", "running", OLD, 1));
        reviewRun(new ReviewRunRow("REVIEW", "queued", OLD, 2));

        cancellation.supersede(reviewId, 1);

        assertEquals(Set.of(older), runIds());
    }

    @Test
    void aClosedPullRequestCancelsEveryLiveReviewRun() {
        String review = reviewRun(new ReviewRunRow("REVIEW", "running", OLD, 1));
        String reply = reviewRun(new ReviewRunRow("FOLLOWUP", "queued", NEW, 2));
        reviewRun(new ReviewRunRow("RECONCILE", "succeeded", NEW, 2));

        cancellation.close(reviewId);

        assertEquals(Set.of(review, reply), runIds());
        assertTrue(cancelled.stream().allMatch(cancel -> cancel.reason().contains("closed")), cancelled.toString());
    }

    @Test
    void aFactoryRunOfTheSameReviewIsNeverCancelledHere() {
        fixRun(OLD);

        cancellation.supersede(reviewId, 1);
        cancellation.close(reviewId);

        assertEquals(List.of(), cancelled);
    }

    private Set<String> runIds() {
        return cancelled.stream().map(RunCommand.CancelRun::runId).collect(Collectors.toSet());
    }

    /** One review run's row: what it is, where it stands, the head it reads and the review round it belongs to. */
    private record ReviewRunRow(String kind, String status, String commit, int round) {
    }

    /**
     * A review run's row, written as raw SQL. A finished one carries its end: V70's CHECK ties a null {@code ended_at}
     * to exactly the statuses still going, so a terminal row without one is refused.
     */
    private String reviewRun(ReviewRunRow row) {
        String runId = runId(row.kind());
        exec("""
                INSERT INTO factory_run (run_id, provider_type, workspace, slug, subject, attempt, status, harness, model,
                                         base_branch, base_commit, branch, kind, review_id, review_call_slot, review_pr_id,
                                         review_round, ended_at)
                VALUES (?, 'github', 'TEST-V107C', 'TEST-REPO', 'TEST-SUBJECT', 1, ?, 'TEST-harness', 'TEST-MODEL',
                        'TEST-source', ?, 'TEST-source', ?, ?, 'TEST-slot', 7, CAST(? AS INT),
                        CASE WHEN CAST(? AS TEXT) IN ('queued', 'running') THEN NULL ELSE now() END)
                """, runId, row.status(), row.commit(), row.kind(), reviewId, Integer.toString(row.round()), row.status());
        return runId;
    }

    private void fixRun(String commit) {
        String runId = runId("FIX");
        exec("""
                INSERT INTO factory_run (run_id, provider_type, workspace, slug, subject, attempt, status, harness, model,
                                         base_branch, base_commit, branch, kind, review_id, finding_ref, comment_id)
                VALUES (?, 'github', 'TEST-V107C', 'TEST-REPO', 'TEST-SUBJECT', 1, 'running', 'TEST-harness', 'TEST-MODEL',
                        'TEST-source', ?, 'TEST-source', 'FIX', ?, 'TEST-thread', ?)
                """, runId, commit, reviewId, "TEST-comment-" + UUID.randomUUID());
    }

    private static String runId(String kind) {
        return "run::github:TEST-V107C/TEST-REPO:TEST-" + kind + "-" + UUID.randomUUID() + ":1";
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
