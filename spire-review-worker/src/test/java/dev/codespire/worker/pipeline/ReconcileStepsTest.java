package dev.codespire.worker.pipeline;

import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadTranscript;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reconcile steps the API path and the harness path share (harness reviews, step 6). {@code ReviewWorkerTest}'s
 * reconcile cases pin the API path through {@code ReviewWorker}; these pin the shared steps directly.
 */
class ReconcileStepsTest {

    private static final RepoRef REPO = new RepoRef("TEST-work", "app");
    private static final String COMMIT = "a".repeat(40);
    private static final String PRIOR_HEAD = "b".repeat(40);
    private static final ReconcileSteps.ReviewAt AT = new ReconcileSteps.ReviewAt(REPO, 7, COMMIT);
    private static final String RENAME_DIFF = """
            diff --git a/src/Old.java b/src/New.java
            rename from src/Old.java
            rename to src/New.java
            """;
    private static final String COPY_DIFF = """
            diff --git a/src/Old.java b/src/Copy.java
            similarity index 100%
            copy from src/Old.java
            copy to src/Copy.java
            """;
    private static final String TOUCHES_A3 = "diff --git a/src/A.java b/src/A.java\n"
            + "--- a/src/A.java\n+++ b/src/A.java\n@@ -2,3 +2,3 @@\n line2\n-old3\n+new3\n line4\n";

    private static DiffSource comparing(String compare, RuntimeException failure) {
        return new ComparingDiff(compare, failure);
    }

    /** Answers only the compare, from the prior head to the head under review; every other read is a defect. */
    private record ComparingDiff(String compare, RuntimeException failure) implements DiffSource {
        @Override
        public ScmType type() {
            return ScmType.GITHUB;
        }

        @Override
        public String apiHost() {
            return "TEST-reconcile.example.invalid";
        }

        @Override
        public PullRequest fetchPullRequest(RepoRef repo, long prId) {
            throw new AssertionError("a reconcile step never reads the pull request");
        }

        @Override
        public Diff fetchDiff(RepoRef repo, long prId, String commit) {
            throw new AssertionError("a reconcile step never reads the full diff");
        }

        @Override
        public String fetchCompareDiff(RepoRef repo, String base, String head) {
            assertEquals(PRIOR_HEAD, base, "the compare starts at the prior head");
            assertEquals(COMMIT, head, "and ends at the head under review");
            if (failure != null) {
                throw failure;
            }
            return compare;
        }
    }

    private static PriorFinding at(String path, int line, String threadRef) {
        return new PriorFinding(path, line, Severity.MAJOR, "TEST-finding", threadRef);
    }

    private static PriorRun prior(PriorFinding... findings) {
        return new PriorRun(PRIOR_HEAD, "TEST-summary", List.of(findings));
    }

    @Test
    void aBlankCompareIsTheFullDiffFallback() {
        PriorRun prior = prior(at("src/A.java", 3, "TEST-thread-1"));

        ReconcileSteps.Basis basis = ReconcileSteps.basis(comparing("  \n", null), AT, prior);

        assertNull(basis.incrementalDiff(), "a blank compare is no diff, not an empty one");
        assertEquals(prior.findings(), basis.remapped());
        assertNull(ReconcileSteps.compareOrNull(comparing("", null), AT, PRIOR_HEAD));
        assertNull(ReconcileSteps.compareOrNull(comparing(null, null), AT, PRIOR_HEAD));
    }

    @Test
    void aCompareThatThrowsIsTheFullDiffFallback() {
        PriorRun prior = prior(at("src/A.java", 3, "TEST-thread-1"));
        DiffSource gone = comparing(null, new IllegalStateException("TEST-compare-404"));

        ReconcileSteps.Basis basis = ReconcileSteps.basis(gone, AT, prior);

        assertNull(basis.incrementalDiff());
        assertEquals(prior.findings(), basis.remapped());
        assertNull(ReconcileSteps.compareOrNull(gone, AT, PRIOR_HEAD));
    }

    @Test
    void anIncrementalCompareIsKept() {
        assertEquals(TOUCHES_A3, ReconcileSteps.compareOrNull(comparing(TOUCHES_A3, null), AT, PRIOR_HEAD));
    }

    @Test
    void aRenameRemapsAPriorFindingKeepingItsPlace() {
        PriorRun prior = prior(at("src/Keep.java", 1, "TEST-thread-1"), at("src/Old.java", 5, "TEST-thread-2"),
                at("src/Other.java", 8, "TEST-thread-3"));

        ReconcileSteps.Basis basis = ReconcileSteps.basis(comparing(RENAME_DIFF, null), AT, prior);

        assertEquals(RENAME_DIFF, basis.incrementalDiff());
        assertEquals(3, basis.remapped().size(), "a verdict id is a position, so nothing moves");
        PriorFinding renamed = basis.remapped().get(1);
        assertEquals("src/New.java", renamed.path());
        assertEquals(5, renamed.line());
        assertEquals("TEST-thread-2", renamed.threadRef());
        assertEquals(prior.findings().get(0), basis.remapped().get(0));
        assertEquals(prior.findings().get(2), basis.remapped().get(2));
    }

    @Test
    void aCopyIsNeverFollowed() {
        PriorRun prior = prior(at("src/Old.java", 5, "TEST-thread-1"));

        ReconcileSteps.Basis basis = ReconcileSteps.basis(comparing(COPY_DIFF, null), AT, prior);

        assertEquals("src/Old.java", basis.remapped().getFirst().path(),
                "a copy's original is still there, unmoved, so the finding stays on it");
    }

    @Test
    void judgeDropsAnOutOfRangeIdAndDowngradesAnUntouchedStillOpen() {
        List<PriorFinding> numbered = List.of(at("src/A.java", 3, "TEST-thread-1"), at("src/B.java", 9, "TEST-thread-2"));
        String answer = "{\"summary\":\"TEST\",\"findings\":[],\"verdicts\":["
                + "{\"id\":0,\"status\":\"resolved\"},{\"id\":1,\"status\":\"still-open\"},"
                + "{\"id\":2,\"status\":\"still-open\"},{\"id\":3,\"status\":\"resolved\"}]}";

        List<FindingVerdict> verdicts = ReconcileSteps.judge(answer, numbered, TOUCHES_A3);

        assertEquals(2, verdicts.size(), "ids 0 and 3 name no finding: " + verdicts);
        assertEquals("TEST-thread-1", verdicts.get(0).threadRef());
        assertEquals(FindingVerdict.Status.STILL_OPEN, verdicts.get(0).status(), "its line was touched");
        assertEquals("TEST-thread-2", verdicts.get(1).threadRef());
        assertEquals(FindingVerdict.Status.UNCHANGED, verdicts.get(1).status(), "its line was not");
    }

    @Test
    void judgeWithoutAnIncrementalDiffKeepsTheAgentsVerdicts() {
        List<PriorFinding> numbered = List.of(at("src/B.java", 9, "TEST-thread-2"));
        String answer = "{\"verdicts\":[{\"id\":1,\"status\":\"still-open\"}]}";

        List<FindingVerdict> verdicts = ReconcileSteps.judge(answer, numbered, null);

        assertEquals(FindingVerdict.Status.STILL_OPEN, verdicts.getFirst().status());
    }

    @Test
    void transcriptsAreFetchedForFindingsWithAThread() {
        ReviewWorkerTest.RecordingSink sink = new ReviewWorkerTest.RecordingSink();
        List<PriorFinding> findings = List.of(at("src/A.java", 3, "TEST-thread-1"), at("src/B.java", 9, null));

        Map<String, ThreadTranscript> transcripts = ReconcileSteps.transcripts(sink, AT, findings);

        assertEquals(1, transcripts.size(), "a finding with no thread has no transcript");
        assertTrue(transcripts.containsKey("TEST-thread-1"));
    }
}
