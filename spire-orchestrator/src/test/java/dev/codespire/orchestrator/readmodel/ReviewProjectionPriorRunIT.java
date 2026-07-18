package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReviewProjectionPriorRunIT {

    @Inject ReviewProjection projection;
    @Inject ReviewThreadView threads;

    @Test
    void priorRunJoinsPostedFindingsWithTheirThreads() {
        String reviewId = "review::ws/prior-run-it#1";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 1L,
                "t", "a", "aid", "src", "dst", "aaa111", "http://x", "github", "reviewing", 0);
        projection.recordOutcome(reviewId, new ReviewResult(
                List.of(new Finding("src/A.java", new LineRange(7, 7), Severity.MAJOR, "leak", null)),
                "summary", new ModelUsage("m", 1, 1, 1)), 4);
        threads.markFindingThread(reviewId, new ThreadRef("thread-9"), "src/A.java", 7);
        projection.recordPosted(reviewId, "aaa111", "sum-1");

        Optional<PriorRun> prior = projection.priorRunFor(reviewId);
        assertTrue(prior.isPresent());
        assertEquals("aaa111", prior.get().headCommit());
        assertEquals("sum-1", prior.get().summaryCommentId());
        assertEquals(1, prior.get().findings().size());
        assertEquals("thread-9", prior.get().findings().getFirst().threadRef());
        assertEquals(7, prior.get().findings().getFirst().line());
    }

    @Test
    void recordPostedIgnoresAStaleCommit() {
        String reviewId = "review::ws/prior-run-it#4";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 4L,
                "t", "a", "aid", "src", "dst", "new111", "http://x", "github", "reviewing", 0);
        projection.recordOutcome(reviewId, new ReviewResult(
                List.of(new Finding("src/A.java", new LineRange(7, 7), Severity.MAJOR, "leak", null)),
                "summary", new ModelUsage("m", 1, 1, 1)), 4);

        projection.recordPosted(reviewId, "stale00", "sum-X");
        assertTrue(projection.priorRunFor(reviewId).isEmpty());

        projection.recordPosted(reviewId, "new111", "sum-1");
        Optional<PriorRun> prior = projection.priorRunFor(reviewId);
        assertTrue(prior.isPresent());
        assertEquals("new111", prior.get().headCommit());
    }

    @Test
    void neverPostedReviewHasNoPriorRun() {
        String reviewId = "review::ws/prior-run-it#2";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 2L,
                "t", "a", "aid", "src", "dst", "bbb222", "http://x", "github", "reviewing", 0);
        assertTrue(projection.priorRunFor(reviewId).isEmpty());
    }

    @Test
    void markResolvedFlagsTheThread() {
        String reviewId = "review::ws/prior-run-it#3";
        threads.markFindingThread(reviewId, new ThreadRef("t-r"), "a.java", 1);
        threads.markResolved(reviewId, new ThreadRef("t-r"));
        // loadThreadRows exposure is asserted via Task 10's detail test; here the write must not throw.
    }
}
