package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The "responding" mark with the comment it is for (V122, review of PR #457, code-quality/M-1), against Postgres: a
 * finish clears only its own comment's mark; an unconditional clear, a new run and a legacy NULL behave as before.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class AnsweringMarksIT {

    private static final RepoRef REPO = new RepoRef("ws", "answering-it");

    @Inject
    AnsweringMarks marks;

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void aDeclineOfAnotherCommentKeepsTheMark() {
        String reviewId = registered(1);
        marks.start(reviewId, "TEST-comment-B");

        marks.finish(reviewId, "TEST-comment-A");

        assertEquals("true|TEST-comment-B", markOf(reviewId), "reply B is still being answered");
    }

    @Test
    void aDeclineOfTheMarkedCommentClearsIt() {
        String reviewId = registered(2);
        marks.start(reviewId, "TEST-comment-A");

        marks.finish(reviewId, "TEST-comment-A");

        assertEquals("false|null", markOf(reviewId));
    }

    @Test
    void aLaterAskTakesTheMarkOver() {
        String reviewId = registered(3);
        marks.start(reviewId, "TEST-comment-A");
        marks.start(reviewId, "TEST-comment-B");

        marks.finish(reviewId, "TEST-comment-A");
        assertEquals("true|TEST-comment-B", markOf(reviewId), "A's late result leaves B's mark");

        marks.finish(reviewId, "TEST-comment-B");
        assertEquals("false|null", markOf(reviewId));
    }

    /** A row marked before V122 holds no comment: any result clears it, as before. */
    @Test
    void aMarkWithNoCommentIsClearedByAnyFinish() {
        String reviewId = registered(4);
        projection.setAnswering(reviewId, true);

        marks.finish(reviewId, "TEST-comment-A");

        assertEquals("false|null", markOf(reviewId));
    }

    /** A result with no triggering comment and a new run both clear both columns. */
    @Test
    void anUnconditionalClearAndANewRunClearTheComment() {
        String reviewId = registered(5);
        marks.start(reviewId, "TEST-comment-A");
        projection.setAnswering(reviewId, false);
        assertEquals("false|null", markOf(reviewId));

        marks.start(reviewId, "TEST-comment-B");
        registered(5);
        assertEquals("false|null", markOf(reviewId), "a new run resets a stuck mark and its comment");
    }

    private String registered(long prId) {
        String reviewId = "review::ws/answering-it#" + prId;
        projection.registerHeader(reviewId, REPO, prId, "t", "a", "aid", "src", "dst", "TEST-head-" + prId,
                "http://x", "github", "reviewing", 0);
        return reviewId;
    }

    /** {@code answering|answering_comment}, read straight from the row: the comment is never exposed to the UI. */
    private String markOf(String reviewId) {
        String sql = "SELECT answering, answering_comment FROM review_status WHERE review_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1) + "|" + rs.getString(2);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the answering mark of " + reviewId, e);
        }
    }
}
