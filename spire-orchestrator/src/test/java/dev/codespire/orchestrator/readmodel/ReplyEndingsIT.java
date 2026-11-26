package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.event.FollowUpDeclineReason;
import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined;
import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.event.IntegrationEvent.TurnCapNotified;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a reply's ending reaches the history and the live dashboard, against Postgres (review of PR #457). A decline is
 * recorded once, by the finish that consumed its outstanding reply (code-quality/M-1), and every ending that changed
 * the stored conversation bumps the review's {@code updated_at}, the live feed's signal, also while another reply keeps
 * the mark up (code-quality/M-3). Each case starts the clock at the epoch so a bump cannot hide inside one instant.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReplyEndingsIT {

    private static final RepoRef REPO = new RepoRef("ws", "reply-endings-it");
    private static final ThreadRef THREAD = new ThreadRef("TEST-thread");
    private static final String A = "TEST-comment-A";
    private static final String B = "TEST-comment-B";

    @Inject
    AnsweringMarks marks;

    @Inject
    FollowUpEndings endings;

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    /** The finding's case: B declined while A is still answered keeps the mark, records B once and is broadcast. */
    @Test
    void aDeclineWhileAnotherReplyIsAnsweredIsRecordedAndBroadcast() {
        String reviewId = registered(1);
        marks.start(new ReplyKey(reviewId, A));
        marks.start(new ReplyKey(reviewId, B));
        stopTheClock(reviewId);

        endings.declined(declined(reviewId, B));

        assertEquals(1, declinesOf(reviewId));
        assertTrue(answering(reviewId), "A's answer is still being written");
        assertTrue(bumped(reviewId), "the open detail must hear that B ended");
    }

    /** A redelivered decline finds no outstanding reply: no second line, and nothing to broadcast. */
    @Test
    void aRepeatedDeclineRecordsNothingAndStaysQuiet() {
        String reviewId = registered(2);
        marks.start(new ReplyKey(reviewId, A));
        endings.declined(declined(reviewId, A));
        stopTheClock(reviewId);

        endings.declined(declined(reviewId, A));

        assertEquals(1, declinesOf(reviewId));
        assertFalse(answering(reviewId));
        assertFalse(bumped(reviewId), "nothing changed");
    }

    @Test
    void aDeclineAfterTheReplyWasAnsweredRecordsNothing() {
        String reviewId = registered(3);
        marks.start(new ReplyKey(reviewId, A));
        endings.posted(new FollowUpPosted(reviewId, THREAD, "TEST-answer-A", true, A));

        endings.declined(declined(reviewId, A));

        assertEquals(0, declinesOf(reviewId));
        assertFalse(answering(reviewId));
    }

    /** A's late decline after B was asked: no line for A, and B's mark stays. */
    @Test
    void aLateDeclineAfterANewerAskRecordsNothingAndKeepsTheNewerMark() {
        String reviewId = registered(4);
        marks.start(new ReplyKey(reviewId, A));
        endings.declined(declined(reviewId, A));
        marks.start(new ReplyKey(reviewId, B));

        endings.declined(declined(reviewId, A));

        assertEquals(1, declinesOf(reviewId));
        assertTrue(answering(reviewId), "B is still being answered");
    }

    /** A close clears every mark and its replies; a decline arriving after it has nothing left to end. */
    @Test
    void aDeclineAfterTheCloseRecordsNothing() {
        String reviewId = registered(5);
        marks.start(new ReplyKey(reviewId, A));
        projection.setAnswering(reviewId, false);

        endings.declined(declined(reviewId, A));

        assertEquals(0, declinesOf(reviewId));
        assertFalse(answering(reviewId));
    }

    /** The redelivery of an answered comment ends its reply without a line, and the dashboard hears the end. */
    @Test
    void anAlreadyAnsweredDeclineEndsTheReplyWithoutALine() {
        String reviewId = registered(6);
        marks.start(new ReplyKey(reviewId, A));
        stopTheClock(reviewId);

        endings.declined(new FollowUpDeclined(reviewId, THREAD, A, FollowUpDeclineReason.ALREADY_ANSWERED));

        assertEquals(0, declinesOf(reviewId));
        assertFalse(answering(reviewId));
        assertTrue(bumped(reviewId));
    }

    /** A posted answer for A while B is outstanding is broadcast too, with the mark left up for B. */
    @Test
    void anAnswerWhileAnotherReplyIsAnsweredIsBroadcast() {
        String reviewId = registered(7);
        marks.start(new ReplyKey(reviewId, A));
        marks.start(new ReplyKey(reviewId, B));
        stopTheClock(reviewId);

        endings.posted(new FollowUpPosted(reviewId, THREAD, "TEST-answer-A", true, A));

        assertTrue(answering(reviewId));
        assertTrue(bumped(reviewId));
    }

    /** The cap notice sets no mark and clears none, but its line is new: the dashboard must hear it. */
    @Test
    void aCapNoticeIsBroadcastAndLeavesAnotherRepliesMark() {
        String reviewId = registered(8);
        marks.start(new ReplyKey(reviewId, A));
        stopTheClock(reviewId);

        endings.capped(new TurnCapNotified(reviewId, THREAD, "TEST-cap-notice"));

        assertTrue(answering(reviewId), "A is still being answered");
        assertTrue(bumped(reviewId));
    }

    private static FollowUpDeclined declined(String reviewId, String commentId) {
        return new FollowUpDeclined(reviewId, THREAD, commentId, FollowUpDeclineReason.MULTI_PARTY_NO_MENTION);
    }

    private String registered(long prId) {
        String reviewId = "review::ws/reply-endings-it#" + prId;
        projection.registerHeader(reviewId, REPO, prId, "t", "a", "aid", "src", "dst", "TEST-head-" + prId,
                "http://x", "github", "reviewing", 0);
        return reviewId;
    }

    private void stopTheClock(String reviewId) {
        execute("UPDATE review_status SET updated_at = TIMESTAMPTZ '1970-01-01 00:00:00Z' WHERE review_id = ?",
                reviewId);
    }

    private boolean bumped(String reviewId) {
        return queryBoolean("SELECT updated_at > TIMESTAMPTZ '1970-01-01 00:00:00Z' FROM review_status"
                + " WHERE review_id = ?", reviewId);
    }

    private boolean answering(String reviewId) {
        return queryBoolean("SELECT answering FROM review_status WHERE review_id = ?", reviewId);
    }

    private int declinesOf(String reviewId) {
        String sql = "SELECT count(*) FROM review_event WHERE review_id = ? AND type = 'FollowUpDeclined'";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count the declines of " + reviewId, e);
        }
    }

    private boolean queryBoolean(String sql, String reviewId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read review_status of " + reviewId, e);
        }
    }

    private void execute(String sql, String reviewId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not write review_status of " + reviewId, e);
        }
    }
}
