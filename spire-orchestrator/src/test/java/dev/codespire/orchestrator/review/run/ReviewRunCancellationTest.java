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
    void aSupersededHeadCancelsTheOlderLiveRun() {
        String older = reviewRun("REVIEW", "running", OLD);

        cancellation.supersede(reviewId, OLD);

        assertEquals(Set.of(older), runIds());
        assertTrue(cancelled.getFirst().reason().contains("supersed"), cancelled.getFirst().reason());
    }

    @Test
    void aSupersedeAfterTheRunFinishedSendsNothing() {
        reviewRun("REVIEW", "succeeded", OLD);

        cancellation.supersede(reviewId, OLD);

        assertEquals(List.of(), cancelled);
    }

    @Test
    void theNewHeadsOwnRunIsNotCancelled() {
        String older = reviewRun("REVIEW", "running", OLD);
        reviewRun("RECONCILE", "queued", NEW);

        cancellation.supersede(reviewId, OLD);

        assertEquals(Set.of(older), runIds());
    }

    @Test
    void aClosedPullRequestCancelsEveryLiveReviewRun() {
        String review = reviewRun("REVIEW", "running", OLD);
        String reply = reviewRun("FOLLOWUP", "queued", NEW);
        reviewRun("RECONCILE", "succeeded", NEW);

        cancellation.close(reviewId);

        assertEquals(Set.of(review, reply), runIds());
        assertTrue(cancelled.stream().allMatch(cancel -> cancel.reason().contains("closed")), cancelled.toString());
    }

    @Test
    void aFactoryRunOfTheSameReviewIsNeverCancelledHere() {
        fixRun(OLD);

        cancellation.supersede(reviewId, OLD);
        cancellation.close(reviewId);

        assertEquals(List.of(), cancelled);
    }

    private Set<String> runIds() {
        return cancelled.stream().map(RunCommand.CancelRun::runId).collect(Collectors.toSet());
    }

    private String reviewRun(String kind, String status, String commit) {
        String runId = runId(kind);
        exec("""
                INSERT INTO factory_run (run_id, provider_type, workspace, slug, subject, attempt, status, harness, model,
                                         base_branch, base_commit, branch, kind, review_id, review_call_slot, review_pr_id)
                VALUES (?, 'github', 'TEST-V107C', 'TEST-REPO', 'TEST-SUBJECT', 1, ?, 'TEST-harness', 'TEST-MODEL',
                        'TEST-source', ?, 'TEST-source', ?, ?, 'TEST-slot', 7)
                """, runId, status, commit, kind, reviewId);
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
