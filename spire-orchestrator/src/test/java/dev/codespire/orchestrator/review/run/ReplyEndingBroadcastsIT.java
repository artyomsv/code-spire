package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.event.IntegrationEvent.HarnessReplyPrepared;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.pipeline.FollowUpEndingsWiring;
import dev.codespire.orchestrator.readmodel.AnsweringMarks;
import dev.codespire.orchestrator.readmodel.CountedReviewFeed;
import dev.codespire.orchestrator.readmodel.EventLine;
import dev.codespire.orchestrator.readmodel.ReplyKey;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each way a reply ends reaches an open dashboard exactly once (qa/M-2, review of PR #457). The posted answer
 * ({@code FollowUpEndings.posted}), the launch's refusal and the result's refusal and failure run as in production, on
 * a real {@code AnsweringMarks} and a real {@code ReviewProjection} writing Postgres; only the dashboard's socket is a
 * stand-in ({@link CountedReviewFeed}), so the count is of the frames pushed after the real {@code touch}. Each case
 * also reads the committed reply rows, the "answering" flag and the ending's line.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReplyEndingBroadcastsIT {

    private static final RepoRef REPO = new RepoRef("ws", "reply-ending-broadcasts-it");
    private static final ThreadRef THREAD = new ThreadRef("TEST-thread");
    private static final String A = "TEST-comment-A";
    private static final String B = "TEST-comment-B";
    private static final String RUN_ID = "TEST-run";
    private static final HarnessReviewInputs INPUTS = new HarnessReviewInputs(List.of(
            new HarnessReviewInputs.InputFile(HarnessReviewInputs.THREAD, "TEST-THREAD")));

    @Inject
    CountedReviewFeed feed;

    /** Writes the headers and the close, whose own frames go to the application's sockets, not to {@link #frames}. */
    @Inject
    ReviewProjection injected;

    @Inject
    DataSource dataSource;

    /** The application's own timeline; its entries are not counted. */
    @Inject
    TimelineBroadcaster timeline;

    private final List<String> frames = new ArrayList<>();
    private ReviewProjection projection;
    private AnsweringMarks marks;

    @BeforeEach
    void wire() {
        projection = feed.projection(frames);
        marks = feed.marks(projection);
    }

    @Test
    void aPostedAnswerToTheOnlyReplyClearsTheMarkAndIsPushedOnce() {
        String reviewId = outstanding(1, A);

        FollowUpEndingsWiring.over(projection, marks).posted(
                new FollowUpPosted(reviewId, THREAD, "TEST-answer-A", true, A));

        assertEnded(reviewId, A);
        assertFalse(answering(reviewId), "no reply is left");
        assertEquals(1, frames.size(), frames.toString());
    }

    @Test
    void aRefusedLaunchWhileAnotherReplyIsOutstandingKeepsTheMarkAndIsPushedOnce() {
        String reviewId = outstanding(2, A, B);

        launch().launch(new HarnessReplyPrepared(reviewId, 2, "TEST-head-2", THREAD, A, 1, INPUTS));

        assertEnded(reviewId, A);
        assertEquals(1, rows(reviewId, B), "B is still outstanding");
        assertTrue(answering(reviewId), "B keeps the mark");
        assertEquals(1, lines(reviewId, "FollowUpRefused"));
        assertEquals(1, frames.size(), frames.toString());
    }

    @Test
    void aRefusedResultClearsTheMarkRecordsTheRefusalAndIsPushedOnce() {
        String reviewId = outstanding(3, A);

        results().accept(new RunResult.RunFailed(RUN_ID, "BAD_COMMAND",
                "IllegalStateException: fork_requires_credential_proxy", false, null, List.of()), binding(reviewId));

        assertEnded(reviewId, A);
        assertFalse(answering(reviewId));
        assertEquals(1, lines(reviewId, "FollowUpRefused"));
        assertEquals(1, frames.size(), frames.toString());
    }

    @Test
    void aFailedResultClearsTheMarkRecordsTheFailureAndIsPushedOnce() {
        String reviewId = outstanding(4, A);

        results().accept(failed(), binding(reviewId));

        assertEnded(reviewId, A);
        assertFalse(answering(reviewId));
        assertEquals(1, lines(reviewId, "FollowUpFailed"));
        assertEquals(1, frames.size(), frames.toString());
    }

    /** A close removed the reply's row first: the ending writes no row or flag, and its new line is pushed once. */
    @Test
    void aFailureAfterACloseRemovedTheReplyIsStillPushedOnce() {
        String reviewId = closed(5, A);

        results().accept(failed(), binding(reviewId));

        assertFalse(answering(reviewId));
        assertEquals(1, lines(reviewId, "FollowUpFailed"));
        assertEquals(1, frames.size(), frames.toString());
    }

    @Test
    void aRefusedResultAfterACloseRemovedTheReplyIsStillPushedOnce() {
        String reviewId = closed(8, A);

        results().accept(new RunResult.RunFailed(RUN_ID, "BAD_COMMAND",
                "IllegalStateException: fork_requires_credential_proxy", false, null, List.of()), binding(reviewId));

        assertFalse(answering(reviewId));
        assertEquals(1, lines(reviewId, "FollowUpRefused"));
        assertEquals(1, frames.size(), frames.toString());
    }

    @Test
    void aRefusedLaunchAfterACloseRemovedBothRepliesIsStillPushedOnce() {
        String reviewId = closed(9, A, B);

        launch().launch(new HarnessReplyPrepared(reviewId, 9, "TEST-head-9", THREAD, A, 1, INPUTS));

        assertEquals(0, rows(reviewId, B), "the close removed B too");
        assertFalse(answering(reviewId));
        assertEquals(1, lines(reviewId, "FollowUpRefused"));
        assertEquals(1, frames.size(), frames.toString());
    }

    @Test
    void aPostedAnswerAfterACloseRemovedTheReplyIsStillPushedOnce() {
        String reviewId = closed(10, A);

        FollowUpEndingsWiring.over(projection, marks).posted(
                new FollowUpPosted(reviewId, THREAD, "TEST-answer-A", true, A));

        assertFalse(answering(reviewId));
        assertEquals(1, frames.size(), frames.toString());
    }

    /** No row ever existed (a redelivered answer): nothing to remove, and the answer is still pushed once. */
    @Test
    void aPostedAnswerWithNoOutstandingReplyIsStillPushedOnce() {
        String reviewId = outstanding(6);

        FollowUpEndingsWiring.over(projection, marks).posted(
                new FollowUpPosted(reviewId, THREAD, "TEST-answer-A", true, A));

        assertFalse(answering(reviewId));
        assertEquals(1, frames.size(), frames.toString());
    }

    /** A line the history refuses rolls the removal back: the reply stays outstanding and nothing is pushed. */
    @Test
    void anEndingThatRollsBackPushesNothing() {
        String reviewId = outstanding(7, A);
        EventLine refused = new EventLine(reviewId, "result", null, "TEST-refused", THREAD.value(), A);

        assertThrows(IllegalStateException.class, () -> marks.finish(new ReplyKey(reviewId, A), refused));

        assertEquals(1, rows(reviewId, A), "the removal rolled back");
        assertTrue(answering(reviewId));
        assertEquals(List.of(), frames);
    }

    /** A registered review with these replies outstanding; their own frames are cleared, so a case counts its ending. */
    private String outstanding(long prId, String... comments) {
        String reviewId = "review::ws/reply-ending-broadcasts-it#" + prId;
        injected.registerHeader(reviewId, REPO, prId, "t", "a", "aid", "src", "dst", "TEST-head-" + prId,
                "http://x", "github", "reviewing", 0);
        for (String comment : comments) {
            marks.start(new ReplyKey(reviewId, comment));
        }
        frames.clear();
        return reviewId;
    }

    /** As {@link #outstanding}, then closed by the real clear: every reply's row is gone before the ending runs. */
    private String closed(long prId, String... comments) {
        String reviewId = outstanding(prId, comments);
        injected.setAnswering(reviewId, false);
        for (String comment : comments) {
            assertEnded(reviewId, comment);
        }
        assertFalse(answering(reviewId), "the close took the mark down");
        frames.clear();
        return reviewId;
    }

    /** The launch's refusal path: the registered review names no repository, so it has no harness. */
    private HarnessReplyLaunch launch() {
        HarnessReplyLaunch launch = new HarnessReplyLaunch();
        launch.projection = projection;
        launch.answering = marks;
        launch.timeline = timeline;
        return launch;
    }

    private HarnessReplyResults results() {
        HarnessReplyResults results = new HarnessReplyResults();
        results.projection = projection;
        results.answering = marks;
        results.timeline = timeline;
        results.runRows = new FactoryRunProjection() {
            @Override
            public Optional<String> harnessOf(String runId) {
                return Optional.of("TEST-harness");
            }
        };
        return results;
    }

    private static RunResult.RunFailed failed() {
        return new RunResult.RunFailed(RUN_ID, "AGENT_FAILED", "TEST-detail", false, null, List.of());
    }

    private static ReviewRunBinding binding(String reviewId) {
        return new ReviewRunBinding(reviewId, ReviewRunBinding.Kind.FOLLOWUP, 1, "TEST-head",
                CallRefs.followUpSlot(THREAD.value(), A), 1);
    }

    private void assertEnded(String reviewId, String commentId) {
        assertEquals(0, rows(reviewId, commentId), "the reply's row is gone");
    }

    private int rows(String reviewId, String commentId) {
        return count("SELECT count(*) FROM review_answering WHERE review_id = ? AND comment_id = ?",
                reviewId, commentId);
    }

    private int lines(String reviewId, String type) {
        return count("SELECT count(*) FROM review_event WHERE review_id = ? AND type = ? AND thread_ref = ?",
                reviewId, type, THREAD.value());
    }

    private boolean answering(String reviewId) {
        return count("SELECT CASE WHEN answering THEN 1 ELSE 0 END FROM review_status WHERE review_id = ?",
                reviewId) == 1;
    }

    /** The first column of the one row {@code sql} reads; the first parameter is always the review id. */
    private int count(String sql, String... parameters) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setString(i + 1, parameters[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the reply state of " + parameters[0], e);
        }
    }
}
