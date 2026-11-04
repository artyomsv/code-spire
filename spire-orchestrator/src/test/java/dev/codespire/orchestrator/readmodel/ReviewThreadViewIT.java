package dev.codespire.orchestrator.readmodel;

import io.quarkus.test.security.TestSecurity;
import dev.codespire.contract.scm.ThreadRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReviewThreadViewIT {

    @Inject
    ReviewThreadView threads;

    @Inject
    DataSource dataSource;

    @Test
    void turnCountStartsAtZeroAndBumps() {
        String reviewId = "review::artyomsv/spire-test#5";
        ThreadRef t = new ThreadRef("100");
        assertEquals(0, threads.turnCount(reviewId, t));
        threads.bumpTurn(reviewId, t, "201");
        threads.bumpTurn(reviewId, t, "203");
        assertEquals(2, threads.turnCount(reviewId, t));
    }

    /** A resent FollowUpPosted names the same answer: it is one turn, not two (review of PR #371, M-1). */
    @Test
    void theSameAnswerCountedTwiceIsOneTurn() {
        String reviewId = "review::artyomsv/spire-test#371";
        ThreadRef t = new ThreadRef("TEST-371");
        threads.bumpTurn(reviewId, t, "TEST-answer-1");
        threads.bumpTurn(reviewId, t, "TEST-answer-1");
        assertEquals(1, threads.turnCount(reviewId, t));
        threads.bumpTurn(reviewId, t, "TEST-answer-2");
        assertEquals(2, threads.turnCount(reviewId, t), "the control: a new answer is a new turn");
    }

    /** A dead letter of answer A replayed after answer B still counts nothing: two answers are two turns. */
    @Test
    void anOlderAnswerReplayedAfterANewerOneIsNotCountedAgain() {
        String reviewId = "review::artyomsv/spire-test#3710";
        ThreadRef t = new ThreadRef("TEST-3710");
        threads.bumpTurn(reviewId, t, "TEST-answer-A");
        threads.bumpTurn(reviewId, t, "TEST-answer-B");
        threads.bumpTurn(reviewId, t, "TEST-answer-A");
        assertEquals(2, threads.turnCount(reviewId, t));
    }

    /**
     * An answer from before the receipts (a FollowUpPosted without countsTurn) is filed, never counted and never the
     * last answer; its receipt also stops a later event that does claim to count it. A new answer still counts.
     */
    @Test
    void aFiledAnswerNeverCountsAndNeverMovesTheLastAnswer() {
        String reviewId = "review::TEST-WS/TEST-REPO#3712";
        ThreadRef t = new ThreadRef("TEST-3712");
        threads.bumpTurn(reviewId, t, "TEST-answer-A");

        threads.fileAnswer(reviewId, t, "TEST-answer-old");

        assertEquals(1, threads.turnCount(reviewId, t));
        assertEquals("TEST-answer-A", lastAnswer(reviewId, t), "a filed answer is not the last one");
        threads.bumpTurn(reviewId, t, "TEST-answer-old");
        assertEquals(1, threads.turnCount(reviewId, t), "once filed, the answer counts nothing when it claims to");
        threads.bumpTurn(reviewId, t, "TEST-answer-B");
        assertEquals(2, threads.turnCount(reviewId, t), "the control: a new answer is a new turn");
    }

    @Test
    void filingAnAnswerOpensNoThread() {
        String reviewId = "review::TEST-WS/TEST-REPO#3713";
        ThreadRef t = new ThreadRef("TEST-3713");

        threads.fileAnswer(reviewId, t, "TEST-answer-old");

        assertEquals(0, threads.turnCount(reviewId, t));
        assertNull(lastAnswer(reviewId, t), "no review_thread row is written for a filed answer");
    }

    private String lastAnswer(String reviewId, ThreadRef thread) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT last_comment_id FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, thread.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the thread's last answer", e);
        }
    }

    @Test
    void markingAThreadOwnedMakesItOurs() {
        String reviewId = "review::artyomsv/spire-test#9";
        ThreadRef t = new ThreadRef("777");
        assertFalse(threads.isOurThread(reviewId, t));
        threads.markOurThread(reviewId, t);
        assertTrue(threads.isOurThread(reviewId, t));
    }

    @Test
    void anUnlinkedThreadIsItsOwnRoot() {
        String reviewId = "review::artyomsv/spire-test#20";
        ThreadRef finding = new ThreadRef("500");
        // Unknown ref (no row at all), and a plain owned root: both resolve to themselves, so GitHub —
        // which already sends the thread root — is unaffected.
        assertEquals(finding, threads.rootOf(reviewId, finding));
        threads.markOurThread(reviewId, finding);
        assertEquals(finding, threads.rootOf(reviewId, finding));
    }

    @Test
    void aBotAnswerResolvesBackToItsConversationRoot() {
        String reviewId = "review::artyomsv/spire-test#21";
        ThreadRef finding = new ThreadRef("600");
        ThreadRef answer = new ThreadRef("601");
        threads.markOurThread(reviewId, finding);
        threads.markAnswerThread(reviewId, answer, finding);

        assertEquals(finding, threads.rootOf(reviewId, answer), "a reply on the bot's answer is the same conversation");
        assertTrue(threads.isOurThread(reviewId, answer), "the answer stays owned so the reply engages");
    }

    @Test
    void turnsAccumulateOnTheRootAcrossAChainOfAnswers() {
        // Bitbucket threads by immediate parent: each turn's reply carries the PREVIOUS answer's id.
        // Counting on the resolved root is what lets the turn cap see one conversation (it previously
        // saw a fresh 0-count row per answer, so the cap could never fire).
        String reviewId = "review::artyomsv/spire-test#22";
        ThreadRef finding = new ThreadRef("700");
        threads.markOurThread(reviewId, finding);

        ThreadRef repliedTo = finding;
        for (String answerId : new String[] {"701", "702", "703"}) {
            ThreadRef root = threads.rootOf(reviewId, repliedTo);
            threads.bumpTurn(reviewId, root, answerId);
            threads.markAnswerThread(reviewId, new ThreadRef(answerId), root);
            repliedTo = new ThreadRef(answerId); // next human reply lands on this answer
        }

        assertEquals(3, threads.turnCount(reviewId, finding), "all three turns counted on the root");
        assertEquals(0, threads.turnCount(reviewId, new ThreadRef("703")),
                "the answer row itself carries no separate count");
        assertEquals(finding, threads.rootOf(reviewId, new ThreadRef("703")),
                "even the deepest answer resolves to the original finding");
    }
}
