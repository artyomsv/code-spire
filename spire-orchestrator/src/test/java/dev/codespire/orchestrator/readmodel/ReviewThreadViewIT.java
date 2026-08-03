package dev.codespire.orchestrator.readmodel;

import io.quarkus.test.security.TestSecurity;
import dev.codespire.contract.scm.ThreadRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReviewThreadViewIT {

    @Inject
    ReviewThreadView threads;

    @Test
    void turnCountStartsAtZeroAndBumps() {
        String reviewId = "review::artyomsv/spire-test#5";
        ThreadRef t = new ThreadRef("100");
        assertEquals(0, threads.turnCount(reviewId, t));
        threads.bumpTurn(reviewId, t, "201");
        threads.bumpTurn(reviewId, t, "203");
        assertEquals(2, threads.turnCount(reviewId, t));
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
