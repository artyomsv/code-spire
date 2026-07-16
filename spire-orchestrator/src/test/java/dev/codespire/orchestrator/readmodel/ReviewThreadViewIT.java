package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.scm.ThreadRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
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
}
