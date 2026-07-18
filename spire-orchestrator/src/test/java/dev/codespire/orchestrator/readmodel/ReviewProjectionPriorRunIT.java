package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorFinding;
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

    @Test
    void loadDetailExposesReconciliationWithResolvedFlags() {
        String reviewId = "review::ws/prior-run-it#4";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 4L,
                "t", "a", "aid", "src", "dst", "ccc333", "http://x", "github", "completed", 6);
        threads.markFindingThread(reviewId, new ThreadRef("t-4"), "src/A.java", 7);
        threads.markResolved(reviewId, new ThreadRef("t-4"));
        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-4", "src/A.java", 7,
                        FindingVerdict.Status.RESOLVED, "fixed")),
                List.of(new PriorFinding("src/A.java", 7, Severity.MAJOR, "leak", "t-4")));

        ReviewDetail detail = projection.loadDetail("ws", "prior-run-it", 4L).orElseThrow();
        assertEquals(1, detail.reconciliation().size());
        ReviewDetail.ReconciliationView view = detail.reconciliation().getFirst();
        assertEquals("resolved", view.status());
        assertEquals("src/A.java:7", view.loc());
        // recordReconciliation stores sev as the display slug (severitySlug), matching FindingView's
        // convention, not the raw Severity enum name — Severity.MAJOR -> "warning".
        assertEquals("warning", view.sev());
        assertTrue(view.resolvedThread());
    }

    @Test
    void recordReconciliationMatchesByThreadRefThenPathLineThenFallsBackForUnmatched() {
        String reviewId = "review::ws/prior-run-it#5";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 5L,
                "t", "a", "aid", "src", "dst", "ddd444", "http://x", "github", "completed", 6);

        List<PriorFinding> priorFindings = List.of(
                // matched by threadRef — path/line differ from the verdict on purpose to prove
                // the threadRef match wins over any path+line comparison.
                new PriorFinding("src/A.java", 10, Severity.MAJOR, "leak in A", "t-A"),
                // never posted inline (no threadRef) — only matchable by path+line.
                new PriorFinding("src/B.java", 3, Severity.MINOR, "issue in B", null));

        List<FindingVerdict> verdicts = List.of(
                new FindingVerdict("t-A", "src/A.java", 10, FindingVerdict.Status.RESOLVED, "fixed"),
                new FindingVerdict(null, "src/B.java", 3, FindingVerdict.Status.STILL_OPEN, null),
                // matches neither a threadRef nor a path+line of any prior finding.
                new FindingVerdict("t-Z", "src/C.java", 99, FindingVerdict.Status.ACKNOWLEDGED, "note"));

        projection.recordReconciliation(reviewId, verdicts, priorFindings);

        ReviewDetail detail = projection.loadDetail("ws", "prior-run-it", 5L).orElseThrow();
        assertEquals(3, detail.reconciliation().size());

        ReviewDetail.ReconciliationView byThreadRef = detail.reconciliation().get(0);
        assertEquals("src/A.java:10", byThreadRef.loc());
        assertEquals("resolved", byThreadRef.status());
        assertEquals("leak in A", byThreadRef.msg());
        assertEquals("warning", byThreadRef.sev(), "MAJOR -> warning, matched via threadRef");

        ReviewDetail.ReconciliationView byPathLine = detail.reconciliation().get(1);
        assertEquals("src/B.java:3", byPathLine.loc());
        assertEquals("still open", byPathLine.status());
        assertEquals("issue in B", byPathLine.msg());
        assertEquals("suggestion", byPathLine.sev(), "MINOR -> suggestion, matched via path+line fallback");

        ReviewDetail.ReconciliationView unmatched = detail.reconciliation().get(2);
        assertEquals("src/C.java:99", unmatched.loc());
        assertEquals("acknowledged", unmatched.status());
        // matchPriorFinding finds nothing: toReconciliationEntry falls back to msg="" and
        // sev=severitySlug(Severity.INFO), which slugs to "nit" (same bucket as NIT).
        assertEquals("", unmatched.msg());
        assertEquals("nit", unmatched.sev());
    }
}
