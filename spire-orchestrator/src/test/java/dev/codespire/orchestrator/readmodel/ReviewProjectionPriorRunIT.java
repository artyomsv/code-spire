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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReviewProjectionPriorRunIT {

    @Inject ReviewProjection projection;
    @Inject ReviewThreadView threads;

    /**
     * Conversation activity (a reply becoming visible, a follow-up's cost landing) writes
     * appendEvent/recordLlmCall/bumpTurn but none of those touch review_status — without a
     * dedicated bump, the dashboard's live feed (keyed off updated_at) never learns about it.
     */
    @Test
    void touchBumpsUpdatedAtAndBroadcasts() throws InterruptedException {
        String reviewId = "review::ws/prior-run-it#15";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 15L,
                "t", "a", "aid", "src", "dst", "c14", "http://x", "github", "reviewing", 0);
        Instant before = projection.loadDetail("ws", "prior-run-it", 15L).orElseThrow().updatedAt();

        Thread.sleep(5);
        projection.touch(reviewId);

        Instant after = projection.loadDetail("ws", "prior-run-it", 15L).orElseThrow().updatedAt();
        assertTrue(after.isAfter(before), "touch must bump updated_at so the live feed picks up the change");
    }

    /**
     * The transient "responding" hint (fix #5): set true when a follow-up reply is dispatched,
     * cleared when it posts — surfaced on both the list row and the detail payload so the
     * dashboard can show an indicator while the bot works on an answer.
     */
    @Test
    void setAnsweringTogglesTheFlagAndSurfacesOnSummaryAndDetail() {
        String reviewId = "review::ws/prior-run-it#16";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 16L,
                "t", "a", "aid", "src", "dst", "c15", "http://x", "github", "reviewing", 0);

        projection.setAnswering(reviewId, true);
        ReviewSummary onSummary = projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
        assertTrue(onSummary.answering(), "answering must surface on the list row once set");
        assertTrue(projection.loadDetail("ws", "prior-run-it", 16L).orElseThrow().answering(),
                "answering must surface on the detail payload once set");

        projection.setAnswering(reviewId, false);
        ReviewSummary offSummary = projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
        assertFalse(offSummary.answering(), "answering must clear on the list row");
        assertFalse(projection.loadDetail("ws", "prior-run-it", 16L).orElseThrow().answering(),
                "answering must clear on the detail payload");
    }

    /**
     * Fix #2: reviewId is stable per PR, so a fresh push re-enters registerHeader's
     * ON CONFLICT DO UPDATE for the SAME review — that upsert must reset a stuck "answering"
     * flag (e.g. left set by a follow-up that terminally DLQs without ever posting
     * FollowUpPosted), so it never bleeds into an unrelated new run.
     */
    @Test
    void registerHeaderResetsAnsweringOnANewRun() {
        String reviewId = "review::ws/prior-run-it#17";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 17L,
                "t", "a", "aid", "src", "dst", "c16", "http://x", "github", "reviewing", 0);
        projection.setAnswering(reviewId, true);

        // A fresh push re-enters registerHeader for the same reviewId.
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 17L,
                "t", "a", "aid", "src", "dst", "c17", "http://x", "github", "reviewing", 0);

        ReviewSummary summary = projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
        assertFalse(summary.answering(), "a new review run must reset the stale answering flag");
        assertFalse(projection.loadDetail("ws", "prior-run-it", 17L).orElseThrow().answering(),
                "detail must also show the reset answering flag");
    }

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

    @Test
    void carryForwardKeepsStillOpenPriorFindings() {
        String reviewId = "review::ws/prior-run-it#6";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 6L,
                "t", "a", "aid", "src", "dst", "c2", "http://x", "github", "reviewing", 0);
        ReviewResult result = new ReviewResult(
                List.of(new Finding("src/New.java", new LineRange(3, 3), Severity.MINOR, "new issue", null)),
                "summary", new ModelUsage("m", 1, 1, 1));
        projection.recordOutcome(reviewId, result, 4);

        List<FindingVerdict> verdicts = List.of(
                new FindingVerdict("t-old", "src/Old.java", 7, FindingVerdict.Status.STILL_OPEN, "still there"));
        List<PriorFinding> priorFindings = List.of(
                new PriorFinding("src/Old.java", 7, Severity.MAJOR, "old issue", "t-old"));
        projection.recordOpenFindings(reviewId, result, verdicts, priorFindings);
        projection.recordPosted(reviewId, "c2", "sum");

        Optional<PriorRun> prior = projection.priorRunFor(reviewId);
        assertTrue(prior.isPresent());
        assertEquals(2, prior.get().findings().size());
        PriorFinding old = prior.get().findings().stream()
                .filter(f -> f.path().equals("src/Old.java")).findFirst().orElseThrow();
        assertEquals("t-old", old.threadRef(), "carried finding keeps its stored threadRef, no thread row needed");
        assertEquals(7, old.line());
        PriorFinding fresh = prior.get().findings().stream()
                .filter(f -> f.path().equals("src/New.java")).findFirst().orElseThrow();
        assertEquals(3, fresh.line());
    }

    @Test
    void carryForwardUsesTheVerdictsFresherRenamedLocation() {
        String reviewId = "review::ws/prior-run-it#10";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 10L,
                "t", "a", "aid", "src", "dst", "c9", "http://x", "github", "reviewing", 0);
        ReviewResult result = new ReviewResult(List.of(), "summary", new ModelUsage("m", 1, 1, 1));
        projection.recordOutcome(reviewId, result, 4);

        // Old.java was renamed to New.java by the follow-up commit; the reconcile call's verdict
        // carries the NEW (remapped) path/line, matched to the prior finding by threadRef.
        List<FindingVerdict> verdicts = List.of(
                new FindingVerdict("t-old", "src/New.java", 5, FindingVerdict.Status.STILL_OPEN, "still there"));
        List<PriorFinding> priorFindings = List.of(
                new PriorFinding("src/Old.java", 5, Severity.MAJOR, "old issue", "t-old"));
        projection.recordOpenFindings(reviewId, result, verdicts, priorFindings);
        projection.recordPosted(reviewId, "c9", "sum");

        Optional<PriorRun> prior = projection.priorRunFor(reviewId);
        assertTrue(prior.isPresent());
        assertEquals(1, prior.get().findings().size());
        PriorFinding carried = prior.get().findings().getFirst();
        assertEquals("src/New.java", carried.path(), "carried entry takes the verdict's fresher (renamed) path");
        assertEquals(5, carried.line());
        assertEquals("t-old", carried.threadRef(), "threadRef is preserved from the prior finding");
        assertEquals("old issue", carried.message(), "message is preserved from the prior finding");
    }

    @Test
    void resolvedPriorFindingsExitTheBaseline() {
        String reviewId = "review::ws/prior-run-it#7";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 7L,
                "t", "a", "aid", "src", "dst", "c3", "http://x", "github", "reviewing", 0);
        ReviewResult result = new ReviewResult(
                List.of(new Finding("src/New.java", new LineRange(3, 3), Severity.MINOR, "new issue", null)),
                "summary", new ModelUsage("m", 1, 1, 1));
        projection.recordOutcome(reviewId, result, 4);

        List<FindingVerdict> verdicts = List.of(
                new FindingVerdict("t-old", "src/Old.java", 7, FindingVerdict.Status.RESOLVED, "fixed"));
        List<PriorFinding> priorFindings = List.of(
                new PriorFinding("src/Old.java", 7, Severity.MAJOR, "old issue", "t-old"));
        projection.recordOpenFindings(reviewId, result, verdicts, priorFindings);
        projection.recordPosted(reviewId, "c3", "sum");

        Optional<PriorRun> prior = projection.priorRunFor(reviewId);
        assertTrue(prior.isPresent());
        assertEquals(1, prior.get().findings().size(), "resolved prior finding drops out of the baseline");
        assertEquals("src/New.java", prior.get().findings().getFirst().path());
    }

    @Test
    void reconciliationMergeRetainsEarlierRounds() {
        String reviewId = "review::ws/prior-run-it#8";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 8L,
                "t", "a", "aid", "src", "dst", "c4", "http://x", "github", "completed", 6);

        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-1", "src/A.java", 1, FindingVerdict.Status.RESOLVED, "fixed")),
                List.of(new PriorFinding("src/A.java", 1, Severity.MAJOR, "leak", "t-1")));
        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-2", "src/B.java", 2, FindingVerdict.Status.STILL_OPEN, "still there")),
                List.of(new PriorFinding("src/B.java", 2, Severity.MINOR, "issue", "t-2")));

        ReviewDetail detail = projection.loadDetail("ws", "prior-run-it", 8L).orElseThrow();
        assertEquals(2, detail.reconciliation().size(), "round 1's entry must survive round 2's merge");
        ReviewDetail.ReconciliationView t1 = detail.reconciliation().stream()
                .filter(v -> "src/A.java:1".equals(v.loc())).findFirst().orElseThrow();
        assertEquals("resolved", t1.status());
        ReviewDetail.ReconciliationView t2 = detail.reconciliation().stream()
                .filter(v -> "src/B.java:2".equals(v.loc())).findFirst().orElseThrow();
        assertEquals("still open", t2.status());
    }

    @Test
    void reconciliationMergeReplacesReverdictedEntries() {
        String reviewId = "review::ws/prior-run-it#9";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 9L,
                "t", "a", "aid", "src", "dst", "c5", "http://x", "github", "completed", 6);

        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-3", "src/C.java", 3, FindingVerdict.Status.STILL_OPEN, "still there")),
                List.of(new PriorFinding("src/C.java", 3, Severity.MAJOR, "issue", "t-3")));
        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-3", "src/C.java", 3, FindingVerdict.Status.RESOLVED, "fixed")),
                List.of(new PriorFinding("src/C.java", 3, Severity.MAJOR, "issue", "t-3")));

        ReviewDetail detail = projection.loadDetail("ws", "prior-run-it", 9L).orElseThrow();
        assertEquals(1, detail.reconciliation().size(), "the re-verdicted entry replaces, not duplicates");
        assertEquals("resolved", detail.reconciliation().getFirst().status());
    }

    @Test
    void sameAnchorFindingsMergeIntoOneTrackedEntry() {
        String reviewId = "review::ws/prior-run-it#12";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 12L,
                "t", "a", "aid", "src", "dst", "c11", "http://x", "github", "reviewing", 0);
        ReviewResult result = new ReviewResult(
                List.of(
                        new Finding("src/Dup.java", new LineRange(4, 4), Severity.MAJOR, "first issue", null),
                        new Finding("src/Dup.java", new LineRange(4, 4), Severity.MINOR, "second issue", null)),
                "summary", new ModelUsage("m", 1, 1, 1));
        projection.recordOutcome(reviewId, result, 4);
        projection.recordOpenFindings(reviewId, result, List.of(), List.of());
        projection.recordPosted(reviewId, "c11", "sum");

        Optional<PriorRun> prior = projection.priorRunFor(reviewId);
        assertTrue(prior.isPresent());
        List<PriorFinding> dupFindings = prior.get().findings().stream()
                .filter(f -> f.path().equals("src/Dup.java") && f.line() == 4).toList();
        assertEquals(1, dupFindings.size(), "same-anchor findings must merge into one tracked entry");
        String msg = dupFindings.getFirst().message();
        assertTrue(msg.contains("first issue"), "merged message keeps the first entry's text: " + msg);
        assertTrue(msg.contains("second issue"), "merged message appends the second entry's text: " + msg);
    }

    @Test
    void openGhostEntriesAreDroppedFromTheMergedView() {
        String reviewId = "review::ws/prior-run-it#11";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 11L,
                "t", "a", "aid", "src", "dst", "c10", "http://x", "github", "completed", 6);

        // Round 1: an UNCHANGED (open-status) verdict for t-g gets tracked.
        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-g", "src/G.java", 1, FindingVerdict.Status.UNCHANGED, "still there")),
                List.of(new PriorFinding("src/G.java", 1, Severity.MAJOR, "ghost issue", "t-g")));

        // Round 2: t-g is not re-verdicted at all (merged elsewhere / dropped from tracking) — it
        // must not linger as a stale open row.
        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-h", "src/H.java", 2, FindingVerdict.Status.STILL_OPEN, "new")),
                List.of(new PriorFinding("src/H.java", 2, Severity.MINOR, "other issue", "t-h")));

        ReviewDetail detail = projection.loadDetail("ws", "prior-run-it", 11L).orElseThrow();
        assertTrue(detail.reconciliation().stream().noneMatch(v -> "src/G.java:1".equals(v.loc())),
                "an OPEN-status entry unmatched this round is a ghost and must be dropped");
    }

    @Test
    void legacyDuplicateAnchorsAreDedupedOnRead() {
        // Simulates a row written BEFORE the anchor-merge fix: recordOutcome (the raw LLM findings,
        // never deduped) followed by recordPosted with no open_findings_json, so posted_findings_json
        // falls back verbatim to findings_json — exactly the shape a legacy duplicate-anchor row has.
        String reviewId = "review::ws/prior-run-it#13";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 13L,
                "t", "a", "aid", "src", "dst", "c12", "http://x", "github", "reviewing", 0);
        ReviewResult result = new ReviewResult(
                List.of(
                        new Finding("src/Legacy.java", new LineRange(9, 9), Severity.MAJOR, "legacy first", null),
                        new Finding("src/Legacy.java", new LineRange(9, 9), Severity.MINOR, "legacy second", null)),
                "summary", new ModelUsage("m", 1, 1, 1));
        projection.recordOutcome(reviewId, result, 4);
        projection.recordPosted(reviewId, "c12", "sum");

        Optional<PriorRun> prior = projection.priorRunFor(reviewId);
        assertTrue(prior.isPresent());
        List<PriorFinding> dupFindings = prior.get().findings().stream()
                .filter(f -> f.path().equals("src/Legacy.java") && f.line() == 9).toList();
        assertEquals(1, dupFindings.size(), "legacy duplicate-anchor rows must dedupe on read");
        String msg = dupFindings.getFirst().message();
        assertTrue(msg.contains("legacy first") && msg.contains("legacy second"),
                "merged message keeps both texts: " + msg);
    }

    @Test
    void anchorMergeIsIdempotentAcrossRounds() {
        // Round 1: two findings at the same anchor merge into one.
        String reviewId = "review::ws/prior-run-it#14";
        projection.registerHeader(reviewId, new RepoRef("ws", "prior-run-it"), 14L,
                "t", "a", "aid", "src", "dst", "c13", "http://x", "github", "reviewing", 0);
        ReviewResult result1 = new ReviewResult(
                List.of(
                        new Finding("src/Idempotent.java", new LineRange(5, 5), Severity.MAJOR, "issue A", null),
                        new Finding("src/Idempotent.java", new LineRange(5, 5), Severity.MINOR, "issue B", null)),
                "summary1", new ModelUsage("m", 1, 1, 1));
        projection.recordOutcome(reviewId, result1, 4);
        projection.recordOpenFindings(reviewId, result1, List.of(), List.of());
        projection.recordPosted(reviewId, "c13", "sum1");

        // Verify round 1 produced the expected merged message.
        Optional<PriorRun> priorRound1 = projection.priorRunFor(reviewId);
        assertTrue(priorRound1.isPresent());
        List<PriorFinding> findingsRound1 = priorRound1.get().findings().stream()
                .filter(f -> f.path().equals("src/Idempotent.java") && f.line() == 5).toList();
        assertEquals(1, findingsRound1.size(), "round 1: two findings at same anchor must merge");
        String mergedMsg = findingsRound1.getFirst().message();
        assertTrue(mergedMsg.contains("issue A") && mergedMsg.contains("issue B"),
                "round 1: merged message contains both: " + mergedMsg);

        // Round 2: re-review with the same findings AND the prior carry-forward.
        // The prior finding ("issue A; also: issue B") has a STILL_OPEN verdict.
        // Fresh LLM result also flags "issue A" and "issue B" at the same anchor.
        ReviewResult result2 = new ReviewResult(
                List.of(
                        new Finding("src/Idempotent.java", new LineRange(5, 5), Severity.MAJOR, "issue A", null),
                        new Finding("src/Idempotent.java", new LineRange(5, 5), Severity.MINOR, "issue B", null)),
                "summary2", new ModelUsage("m", 1, 1, 1));
        projection.recordOutcome(reviewId, result2, 4);

        List<FindingVerdict> verdicts = List.of(
                new FindingVerdict(null, "src/Idempotent.java", 5, FindingVerdict.Status.STILL_OPEN, "still there"));
        List<PriorFinding> priorFindings = List.of(
                new PriorFinding("src/Idempotent.java", 5, Severity.MAJOR, mergedMsg, null));
        projection.recordOpenFindings(reviewId, result2, verdicts, priorFindings);
        projection.recordPosted(reviewId, "c13", "sum2");

        // Verify round 2: the merged message must be idempotent (no duplicated segments).
        Optional<PriorRun> priorRound2 = projection.priorRunFor(reviewId);
        assertTrue(priorRound2.isPresent());
        List<PriorFinding> findingsRound2 = priorRound2.get().findings().stream()
                .filter(f -> f.path().equals("src/Idempotent.java") && f.line() == 5).toList();
        assertEquals(1, findingsRound2.size(), "round 2: merged findings must remain one entry");
        String idempotentMsg = findingsRound2.getFirst().message();
        assertEquals(mergedMsg, idempotentMsg,
                "round 2: re-merging the same constituents must yield the identical message (idempotent)");
    }

    @Test
    void listSummaryShowsCumulativeCostAndOpenReconciledFindings() {
        String reviewId = "review::ws/list-recon-it#1";
        projection.registerHeader(reviewId, new RepoRef("ws", "list-recon-it"), 1L,
                "t", "a", "aid", "src", "dst", "c2", "http://x", "github", "completed", 6);
        // last run: 0 new findings, its own cost overwrites cost_millicents
        projection.recordOutcome(reviewId, new ReviewResult(List.of(), "sum",
                new ModelUsage("m", 1, 1, 738)), 6);
        projection.recordLlmCall(reviewId, "review", new ModelUsage("m", 1, 1, 2189));
        projection.recordLlmCall(reviewId, "review", new ModelUsage("m", 1, 1, 738));
        projection.recordLlmCall(reviewId, "reconcile", new ModelUsage("m", 1, 1, 965));
        // reconciliation carries one STILL_OPEN critical
        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-1", "src/A.java", 12,
                        FindingVerdict.Status.STILL_OPEN, "missing")),
                List.of(new PriorFinding("src/A.java", 12, Severity.BLOCKER, "npe", "t-1")));

        ReviewSummary summary = projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
        assertEquals(2189 + 738 + 965, summary.costMillicents(), "cumulative across all LLM calls");
        assertEquals(1, summary.findings(), "one still-open reconciliation finding");
        assertEquals(1, summary.blockerCount(), "the still-open critical");
    }

    @Test
    void listSummaryPassesOnlyWhenNoOpenFindingsRemain() {
        String reviewId = "review::ws/list-recon-it#2";
        projection.registerHeader(reviewId, new RepoRef("ws", "list-recon-it"), 2L,
                "t", "a", "aid", "src", "dst", "c3", "http://x", "github", "completed", 6);
        projection.recordOutcome(reviewId, new ReviewResult(List.of(), "sum",
                new ModelUsage("m", 1, 1, 100)), 6);
        // all prior findings resolved
        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-9", "src/B.java", 3,
                        FindingVerdict.Status.RESOLVED, "fixed")),
                List.of(new PriorFinding("src/B.java", 3, Severity.MAJOR, "x", "t-9")));
        ReviewSummary summary = projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
        assertEquals(0, summary.findings(), "resolved findings are not open");
    }

    @Test
    void firstReviewOpenCountIsItsNewFindings() {
        String reviewId = "review::ws/list-recon-it#3";
        projection.registerHeader(reviewId, new RepoRef("ws", "list-recon-it"), 3L,
                "t", "a", "aid", "src", "dst", "c1", "http://x", "github", "completed", 6);
        projection.recordOutcome(reviewId, new ReviewResult(
                List.of(new Finding("src/C.java", new LineRange(4, 4), Severity.BLOCKER, "leak", null)),
                "sum", new ModelUsage("m", 1, 1, 500)), 6);
        ReviewSummary summary = projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
        assertEquals(1, summary.findings());
        assertEquals(1, summary.blockerCount());
        assertEquals(500, summary.costMillicents(), "no llm_call rows -> falls back to cost_millicents");
    }

    /**
     * Fix #1: the detail header badge must show the same reconciled OPEN counts as the list row,
     * not this run's raw findings_count/blocker count — otherwise a carried-forward open critical
     * shows "Changes" in the list but "Passed" in the detail header.
     */
    @Test
    void detailHeaderOpenCountsMatchTheListRow() {
        String reviewId = "review::ws/list-recon-it#4";
        projection.registerHeader(reviewId, new RepoRef("ws", "list-recon-it"), 4L,
                "t", "a", "aid", "src", "dst", "c4b", "http://x", "github", "completed", 6);
        // last run found no new findings...
        projection.recordOutcome(reviewId, new ReviewResult(List.of(), "sum",
                new ModelUsage("m", 1, 1, 100)), 6);
        // ...but reconciliation carries one STILL_OPEN critical from a prior run.
        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-cf", "src/A.java", 5,
                        FindingVerdict.Status.STILL_OPEN, "still there")),
                List.of(new PriorFinding("src/A.java", 5, Severity.BLOCKER, "npe", "t-cf")));

        ReviewSummary listRow = projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
        ReviewDetail detail = projection.loadDetail("ws", "list-recon-it", 4L).orElseThrow();

        assertEquals(1, listRow.blockerCount(), "list row shows the carried-forward open critical");
        assertEquals(listRow.blockerCount(), detail.openBlockers(),
                "detail header's open blocker count must agree with the list row");
        assertEquals(listRow.findings(), detail.openFindings(),
                "detail header's open findings count must agree with the list row");
        assertEquals(0, detail.findings(),
                "findings stays the raw per-run new-findings count (findings card math depends on it)");
    }
}
