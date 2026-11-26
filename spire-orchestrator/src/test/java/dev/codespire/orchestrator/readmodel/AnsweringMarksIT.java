package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.event.FollowUpDeclineReason;
import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined;
import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.pipeline.FollowUpEndings;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The replies the bot is answering (V122, review of PR #457, code-quality/M-1), against Postgres: each outstanding
 * reply is a row, the flag stays up while any is left, and a clear that names no comment drops them all.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class AnsweringMarksIT {

    private static final RepoRef REPO = new RepoRef("ws", "answering-it");
    private static final ThreadRef THREAD_B = new ThreadRef("TEST-thread-B");

    @Inject
    AnsweringMarks marks;

    @Inject
    FollowUpEndings endings;

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    /** The finding's interleaving: A is being answered when B is asked and declined. */
    @Test
    void aDeclineOfOneReplyKeepsTheMarkUntilTheOtherEnds() {
        String reviewId = registered(1);
        marks.start(new ReplyKey(reviewId, "TEST-comment-A"));
        marks.start(new ReplyKey(reviewId, "TEST-comment-B"));

        endings.declined(new FollowUpDeclined(reviewId, THREAD_B, "TEST-comment-B",
                FollowUpDeclineReason.MULTI_PARTY_NO_MENTION));
        assertEquals("true|TEST-comment-A", markOf(reviewId), "A's answer is still being written");

        endings.posted(new FollowUpPosted(reviewId, new ThreadRef("TEST-thread-A"), "TEST-answer-A", true,
                "TEST-comment-A"));
        assertEquals("false|", markOf(reviewId));
    }

    /** The other order: A's late result leaves B's mark up, B's own result takes it down. */
    @Test
    void aLateResultOfAnEarlierReplyKeepsALaterOnesMark() {
        String reviewId = registered(2);
        marks.start(new ReplyKey(reviewId, "TEST-comment-A"));
        marks.start(new ReplyKey(reviewId, "TEST-comment-B"));

        marks.finish(new ReplyKey(reviewId, "TEST-comment-A"));
        assertEquals("true|TEST-comment-B", markOf(reviewId));

        marks.finish(new ReplyKey(reviewId, "TEST-comment-B"));
        assertEquals("false|", markOf(reviewId));
    }

    /** A redelivered ask adds no second row, and a redelivered result finds nothing left to end. */
    @Test
    void aRepeatedAskOrResultChangesNothing() {
        String reviewId = registered(3);
        marks.start(new ReplyKey(reviewId, "TEST-comment-A"));
        marks.start(new ReplyKey(reviewId, "TEST-comment-A"));
        assertEquals("true|TEST-comment-A", markOf(reviewId));

        marks.finish(new ReplyKey(reviewId, "TEST-comment-A"));
        marks.finish(new ReplyKey(reviewId, "TEST-comment-A"));
        assertEquals("false|", markOf(reviewId));
    }

    /** A mark set with no reply row (before V122, or an answer that names none) is ended by any result. */
    @Test
    void aMarkWithNoReplyIsEndedByAnyResult() {
        String reviewId = registered(4);
        projection.setAnswering(reviewId, true);

        marks.finish(new ReplyKey(reviewId, "TEST-comment-A"));

        assertEquals("false|", markOf(reviewId));
    }

    /**
     * A clear that names no comment drops every outstanding reply in the same statement (V122's trigger), so a reply
     * left from before it cannot hold the mark up after a later reply ends.
     */
    @Test
    void aClearThatNamesNoCommentDropsEveryReply() {
        String reviewId = registered(5);
        marks.start(new ReplyKey(reviewId, "TEST-comment-A"));
        marks.start(new ReplyKey(reviewId, "TEST-comment-B"));
        projection.setAnswering(reviewId, false);
        assertEquals("false|", markOf(reviewId));

        marks.start(new ReplyKey(reviewId, "TEST-comment-C"));
        marks.finish(new ReplyKey(reviewId, "TEST-comment-C"));
        assertEquals("false|", markOf(reviewId), "A and B went with the clear");
    }

    @Test
    void aNewRunDropsEveryReply() {
        String reviewId = registered(6);
        marks.start(new ReplyKey(reviewId, "TEST-comment-A"));

        registered(6);

        assertEquals("false|", markOf(reviewId), "a new run resets a stuck mark and its replies");
    }

    /** A reply on a review the read model does not hold yet writes nothing (the row is the lock). */
    @Test
    void aReplyOnAnUnknownReviewWritesNothing() {
        marks.start(new ReplyKey("review::ws/answering-it#404", "TEST-comment-A"));

        assertEquals(List.of(), repliesOf("review::ws/answering-it#404"));
    }

    private String registered(long prId) {
        String reviewId = "review::ws/answering-it#" + prId;
        projection.registerHeader(reviewId, REPO, prId, "t", "a", "aid", "src", "dst", "TEST-head-" + prId,
                "http://x", "github", "reviewing", 0);
        return reviewId;
    }

    /** {@code answering|outstanding comments}, read straight from the tables: the comments never reach the UI. */
    private String markOf(String reviewId) {
        String sql = "SELECT answering FROM review_status WHERE review_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1) + "|" + String.join(",", repliesOf(reviewId));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the answering mark of " + reviewId, e);
        }
    }

    private List<String> repliesOf(String reviewId) {
        String sql = "SELECT comment_id FROM review_answering WHERE review_id = ? ORDER BY comment_id";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            List<String> replies = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    replies.add(rs.getString(1));
                }
            }
            return replies;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the outstanding replies of " + reviewId, e);
        }
    }
}
