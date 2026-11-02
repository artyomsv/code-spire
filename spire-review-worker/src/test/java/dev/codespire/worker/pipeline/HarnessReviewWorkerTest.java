package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.FinishHarnessReview;
import dev.codespire.contract.command.ActionCommand.PrepareHarnessReview;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewGenerated;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewPrepared;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.AssembledContext;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Harness reviews, step 6: the review worker prepares a harness review's input files and finishes its answer with the
 * API path's own parsers and backstops. The fakes are {@link ReviewWorkerTest}'s, so both paths are judged against one
 * SCM, one blob store and one idempotency store.
 */
class HarnessReviewWorkerTest {

    private static final RepoRef REPO = new RepoRef("TEST-work", "app");
    private static final String REVIEW_ID = "review::TEST-work/app#7";
    private static final String COMMIT = "a".repeat(40);
    private static final String PRIOR_HEAD = "b".repeat(40);
    private static final int ROUND = 2;
    /** Changes src/A.java around line 3 only: a finding there is touched, one in src/B.java is not. */
    private static final String TOUCHES_A3 = "diff --git a/src/A.java b/src/A.java\n"
            + "--- a/src/A.java\n+++ b/src/A.java\n@@ -2,3 +2,3 @@\n line2\n-old3\n+new3\n line4\n";
    private static final String RENAME_DIFF = """
            diff --git a/src/Old.java b/src/New.java
            rename from src/Old.java
            rename to src/New.java
            """;
    private static final String OVER_CAP_SUMMARY = "The harness answer was larger than 256 KiB and was not read.";

    private HarnessReviewWorker worker;
    private List<IntegrationEvent> emitted;
    private ReviewWorkerTest.InMemoryIdempotency idempotency;
    private ReviewWorkerTest.InMemoryBlobStore blobs;
    private String head;
    private RuntimeException prFailure;
    private String compareDiff;
    private RuntimeException compareFailure;
    private int compareFetches;

    @BeforeEach
    void setUp() {
        emitted = new ArrayList<>();
        idempotency = new ReviewWorkerTest.InMemoryIdempotency();
        blobs = new ReviewWorkerTest.InMemoryBlobStore();
        head = COMMIT;
        prFailure = null;
        compareDiff = TOUCHES_A3;
        compareFailure = null;
        compareFetches = 0;

        worker = new HarnessReviewWorker();
        worker.mapper = new ObjectMapper();
        worker.idempotency = idempotency;
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        worker.contextReader = new AssembledContextReader();
        worker.contextReader.blobStore = blobs;
        worker.contextReader.mapper = new ObjectMapper();
        wireScm(fakeDiff("TEST-" + UUID.randomUUID() + ".example.invalid"));
    }

    private void wireScm(DiffSource diff) {
        ReviewWorkerTest.RecordingSink sink = new ReviewWorkerTest.RecordingSink();
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(diff, sink);
            }
        };
    }

    /** A fresh host per test, so a failing read never trips the shared breaker for another test. */
    private DiffSource fakeDiff(String host) {
        return new DiffSource() {
            @Override
            public ScmType type() {
                return ScmType.GITHUB;
            }

            @Override
            public String apiHost() {
                return host;
            }

            @Override
            public PullRequest fetchPullRequest(RepoRef repo, long prId) {
                if (prFailure != null) {
                    throw prFailure;
                }
                return new PullRequest(repo, prId, "TEST-TITLE", "TEST-DESCRIPTION", "TEST-source", "main", head,
                        Author.of("TEST-id", "TEST-user", "TEST-user"), "https://forge.example.test/TEST-work/app/pull/7");
            }

            @Override
            public Diff fetchDiff(RepoRef repo, long prId, String commit) {
                throw new AssertionError("a harness review never fetches the full diff: the agent reads its checkout");
            }

            @Override
            public String fetchCompareDiff(RepoRef repo, String base, String headCommit) {
                compareFetches++;
                if (compareFailure != null) {
                    throw compareFailure;
                }
                return compareDiff;
            }
        };
    }

    // --- fixtures ---

    private static PriorFinding priorA() {
        return new PriorFinding("src/A.java", 3, Severity.MAJOR, "TEST-FIRST-FINDING", "TEST-thread-1");
    }

    private static PriorFinding priorB() {
        return new PriorFinding("src/B.java", 9, Severity.MINOR, "TEST-SECOND-FINDING", "TEST-thread-2");
    }

    private static PriorRun prior(PriorFinding... findings) {
        return new PriorRun(PRIOR_HEAD, "TEST-summary", List.of(findings));
    }

    private static PrepareHarnessReview prepareReview(String contextRef) {
        return new PrepareHarnessReview(REVIEW_ID, REPO, 7, COMMIT, contextRef, null, null, ROUND,
                ReviewRunBinding.Kind.REVIEW);
    }

    private static PrepareHarnessReview prepareReReview(PriorRun prior) {
        return new PrepareHarnessReview(REVIEW_ID, REPO, 7, COMMIT, null, null, prior, ROUND,
                ReviewRunBinding.Kind.RECONCILE);
    }

    private static FinishHarnessReview finish(ReviewRunBinding.Kind kind, String answer) {
        return new FinishHarnessReview(REVIEW_ID, REPO, 7, COMMIT, ROUND, kind, answer,
                ModelUsage.of("TEST-model", 1, 1), null);
    }

    private static String finding(String path, int line) {
        return "{\"path\":\"" + path + "\",\"line\":" + line + ",\"endLine\":" + line
                + ",\"severity\":\"MAJOR\",\"message\":\"TEST-NEW-FINDING\",\"suggestion\":null}";
    }

    private static String answer(String findingsJson, String verdictsJson) {
        return "{\"summary\":\"TEST-summary\",\"findings\":[" + findingsJson + "],\"verdicts\":[" + verdictsJson + "]}";
    }

    private static String verdict(int id, String status) {
        return "{\"id\":" + id + ",\"status\":\"" + status + "\",\"note\":\"TEST-note\"}";
    }

    /** What a prepare persists: seeded directly, so a finish test does not depend on the prepare. */
    private void seedPreparedPrior(boolean incremental, PriorFinding... numbered) throws Exception {
        HarnessReviewWorker.PreparedPrior prepared =
                new HarnessReviewWorker.PreparedPrior(PRIOR_HEAD, incremental, List.of(numbered));
        idempotency.claim(REVIEW_ID, COMMIT, "harness:prepare#" + ROUND);
        idempotency.markPosted(REVIEW_ID, COMMIT, "harness:prepare#" + ROUND,
                new ObjectMapper().writeValueAsString(prepared));
    }

    private String contextRef(ContextItem item) throws Exception {
        AssembledContext assembled = new AssembledContext("TEST-ctx", List.of(item), Set.of("GITHUB_ISSUES"), Set.of());
        return blobs.put(BlobStore.Kind.CONTEXT, REVIEW_ID, new ObjectMapper().writeValueAsBytes(assembled)).key();
    }

    private <T extends IntegrationEvent> T last(Class<T> type) {
        for (int i = emitted.size() - 1; i >= 0; i--) {
            if (type.isInstance(emitted.get(i))) {
                return type.cast(emitted.get(i));
            }
        }
        return fail("no " + type.getSimpleName() + " was emitted: " + emitted);
    }

    private static String file(HarnessReviewInputs inputs, String name) {
        for (HarnessReviewInputs.InputFile file : inputs.files()) {
            if (file.name().equals(name)) {
                return file.text();
            }
        }
        return null;
    }

    private static FindingVerdict verdictFor(HarnessReviewGenerated generated, String threadRef) {
        return generated.verdicts().stream().filter(v -> threadRef.equals(v.threadRef())).findFirst()
                .orElseGet(() -> fail("no verdict for " + threadRef + ": " + generated.verdicts()));
    }

    // --- prepare ---

    @Test
    void aFirstReviewIsPreparedWithItsContext() throws Exception {
        String ref = contextRef(new ContextItem("ISSUE", "TEST-ISSUE-TITLE", "TEST-ISSUE-BODY",
                "https://forge.example.test/TEST-work/app/issues/1"));

        worker.prepare(prepareReview(ref));

        HarnessReviewPrepared prepared = last(HarnessReviewPrepared.class);
        assertEquals(ReviewRunBinding.Kind.REVIEW, prepared.kind());
        assertEquals(ROUND, prepared.round(), "the round is echoed, so the launch can check it");
        assertEquals(COMMIT, prepared.commit());
        String context = file(prepared.inputs(), HarnessReviewInputs.CONTEXT);
        assertNotNull(context);
        assertTrue(context.contains("TEST-ISSUE-BODY"), context);
        assertTrue(context.contains("TEST-TITLE"), context);
        assertNull(file(prepared.inputs(), HarnessReviewInputs.PRIOR_FINDINGS));
        assertEquals(0, compareFetches, "a first review compares nothing");
    }

    @Test
    void aReviewWithNoContextRefStillGetsAContextFile() {
        worker.prepare(prepareReview(null));

        String context = file(last(HarnessReviewPrepared.class).inputs(), HarnessReviewInputs.CONTEXT);
        assertNotNull(context, "the agent is told to read it, so it is always there");
        assertTrue(context.contains("TEST-TITLE"), context);
    }

    @Test
    void aReReviewNumbersItsPriorFindingsFromOne() {
        PriorFinding untracked = new PriorFinding("src/B.java", 9, Severity.MINOR, "TEST-SECOND-FINDING", null);

        worker.prepare(prepareReReview(prior(priorA(), untracked)));

        HarnessReviewPrepared prepared = last(HarnessReviewPrepared.class);
        assertEquals(ReviewRunBinding.Kind.RECONCILE, prepared.kind());
        String priorFile = file(prepared.inputs(), HarnessReviewInputs.PRIOR_FINDINGS);
        assertNotNull(priorFile);
        assertTrue(priorFile.contains("1. [MAJOR] src/A.java:3"), priorFile);
        assertTrue(priorFile.contains("2. [MINOR] src/B.java:9"), priorFile);
        assertTrue(priorFile.contains("[reviewer] please fix this"), "the one thread's transcript: " + priorFile);
        assertEquals(priorFile.indexOf("please fix this"), priorFile.lastIndexOf("please fix this"),
                "only the finding with a thread has a transcript");
        assertTrue(priorFile.contains("git diff " + PRIOR_HEAD + " HEAD"), priorFile);
        assertNotNull(file(prepared.inputs(), HarnessReviewInputs.CONTEXT), "a re-review gets its context too");
    }

    @Test
    void aReReviewWhoseCompareFailsSaysTheHeadIsGone() {
        compareFailure = new IllegalStateException("TEST-compare-404");

        worker.prepare(prepareReReview(prior(priorA(), priorB())));

        String priorFile = file(last(HarnessReviewPrepared.class).inputs(), HarnessReviewInputs.PRIOR_FINDINGS);
        assertTrue(priorFile.contains("no longer in the pull request's history"), priorFile);
        assertFalse(priorFile.contains("git diff"), priorFile);
    }

    @Test
    void aRenamedFileIsJudgedAtItsNewPath() {
        compareDiff = RENAME_DIFF;
        PriorFinding old = new PriorFinding("src/Old.java", 5, Severity.MAJOR, "TEST-RENAMED", "TEST-thread-1");

        worker.prepare(prepareReReview(prior(old)));
        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer("", verdict(1, "resolved"))));

        String priorFile = file(last(HarnessReviewPrepared.class).inputs(), HarnessReviewInputs.PRIOR_FINDINGS);
        assertTrue(priorFile.contains("src/New.java:5"), priorFile);
        assertFalse(priorFile.contains("src/Old.java:5"), priorFile);
        FindingVerdict judged = verdictFor(last(HarnessReviewGenerated.class), "TEST-thread-1");
        assertEquals("src/New.java", judged.path(), "the verdict carries the path the agent judged");
    }

    @Test
    void aPreparedReviewWhoseHeadMovedIsAbandoned() {
        head = "c".repeat(40);

        worker.prepare(prepareReview(null));
        worker.prepare(prepareReReview(prior(priorA())));

        assertTrue(emitted.isEmpty(), "a moved head is abandoned quietly: " + emitted);
    }

    @Test
    void aFailedPrepareIsARetryableReviewFailure() {
        prFailure = new UncheckedIOException(new IOException("TEST-transient"));

        worker.prepare(prepareReview(null));

        ReviewFailed failed = last(ReviewFailed.class);
        assertEquals("harness-prepare", failed.phase());
        assertTrue(failed.retryable(), "a transient read is retried on the same round");
        assertEquals(COMMIT, failed.commit());
        assertTrue(emitted.stream().noneMatch(HarnessReviewPrepared.class::isInstance));
    }

    @Test
    void aRedeliveredPrepareKeepsItsNumbering() {
        worker.prepare(prepareReReview(prior(priorA(), priorB())));
        worker.prepare(prepareReReview(prior(priorB(), priorA()))); // a redelivery that reads the list in another order

        List<HarnessReviewPrepared> prepared = emitted.stream().filter(HarnessReviewPrepared.class::isInstance)
                .map(HarnessReviewPrepared.class::cast).toList();
        assertEquals(2, prepared.size());
        String second = file(prepared.get(1).inputs(), HarnessReviewInputs.PRIOR_FINDINGS);
        assertTrue(second.contains("1. [MAJOR] src/A.java:3"), second);
        assertTrue(second.contains("2. [MINOR] src/B.java:9"), second);
        assertEquals(prepared.get(0).inputs(), prepared.get(1).inputs());
        assertEquals(1, compareFetches, "the numbering is read back, never recomputed");
    }

    // --- finish ---

    @Test
    void aReviewAnswerBecomesFindings() {
        worker.finish(finish(ReviewRunBinding.Kind.REVIEW, answer(finding("src/C.java", 20), "")));

        HarnessReviewGenerated generated = last(HarnessReviewGenerated.class);
        assertEquals(ROUND, generated.round());
        assertEquals(COMMIT, generated.commit());
        assertEquals(1, generated.result().findings().size());
        assertEquals("src/C.java", generated.result().findings().getFirst().path());
        assertEquals(List.of(), generated.verdicts(), "a first review judges nothing");
        assertFalse(generated.result().degraded());
    }

    @Test
    void aReReviewAnswerBecomesFindingsAndVerdicts() throws Exception {
        seedPreparedPrior(true, priorA(), priorB());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer(finding("src/C.java", 20),
                verdict(1, "resolved") + "," + verdict(2, "still-open"))));

        HarnessReviewGenerated generated = last(HarnessReviewGenerated.class);
        assertEquals(FindingVerdict.Status.RESOLVED, verdictFor(generated, "TEST-thread-1").status());
        assertEquals(FindingVerdict.Status.UNCHANGED, verdictFor(generated, "TEST-thread-2").status(),
                "the change never touched finding 2's line, so the backstop downgrades it");
        assertEquals(1, generated.result().findings().size(), "the new finding is kept");
        assertEquals("src/C.java", generated.result().findings().getFirst().path());
    }

    @Test
    void aReReviewWhoseCompareFailedDowngradesNothing() throws Exception {
        seedPreparedPrior(false, priorA(), priorB());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer("", verdict(2, "still-open"))));

        HarnessReviewGenerated generated = last(HarnessReviewGenerated.class);
        assertEquals(FindingVerdict.Status.STILL_OPEN, verdictFor(generated, "TEST-thread-2").status(),
                "no incremental diff, so the agent's verdict stands, as on the API path");
        assertEquals(0, compareFetches, "a prepare that had no compare is not compared again");
    }

    @Test
    void theVerdictNumbersAreThoseThePrepareRendered() {
        worker.prepare(prepareReReview(prior(priorA(), priorB())));

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer("", verdict(2, "resolved"))));

        HarnessReviewGenerated generated = last(HarnessReviewGenerated.class);
        assertEquals(1, generated.verdicts().size());
        FindingVerdict second = generated.verdicts().getFirst();
        assertEquals("TEST-thread-2", second.threadRef(), "id 2 is the second finding the agent read");
        assertEquals("src/B.java", second.path());
        assertEquals(FindingVerdict.Status.RESOLVED, second.status());
    }

    @Test
    void aVerdictOutsideTheNumbersIsDropped() throws Exception {
        seedPreparedPrior(false, priorA(), priorB());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE,
                answer("", verdict(0, "resolved") + "," + verdict(3, "resolved") + "," + verdict(1, "resolved"))));

        HarnessReviewGenerated generated = last(HarnessReviewGenerated.class);
        assertEquals(1, generated.verdicts().size(), "ids 0 and 3 name no finding: " + generated.verdicts());
        assertEquals("TEST-thread-1", generated.verdicts().getFirst().threadRef());
    }

    @Test
    void aNewFindingOnAStillOpenAnchorIsDropped() throws Exception {
        seedPreparedPrior(true, priorA());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE,
                answer(finding("src/A.java", 3) + "," + finding("src/C.java", 20), verdict(1, "still-open"))));

        HarnessReviewGenerated generated = last(HarnessReviewGenerated.class);
        assertEquals(FindingVerdict.Status.STILL_OPEN, verdictFor(generated, "TEST-thread-1").status());
        assertEquals(1, generated.result().findings().size(), "the re-report at the open anchor is dropped");
        assertEquals("src/C.java", generated.result().findings().getFirst().path());
    }

    @Test
    void anOverCapAnswerIsADegradedReviewWithNoVerdicts() throws Exception {
        seedPreparedPrior(true, priorA());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, null));

        HarnessReviewGenerated generated = last(HarnessReviewGenerated.class);
        assertTrue(generated.result().degraded());
        assertEquals(OVER_CAP_SUMMARY, generated.result().summary());
        assertEquals(List.of(), generated.verdicts());
    }

    @Test
    void aProseAnswerIsADegradedReview() {
        worker.finish(finish(ReviewRunBinding.Kind.REVIEW, "TEST prose with no JSON in it"));

        HarnessReviewGenerated generated = last(HarnessReviewGenerated.class);
        assertTrue(generated.result().degraded(), "a prose answer never reads as a clean review");
        assertEquals(List.of(), generated.result().findings());
    }

    @Test
    void aRedeliveredFinishReEmitsTheSameOutcome() throws Exception {
        seedPreparedPrior(true, priorA(), priorB());

        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer(finding("src/C.java", 20), verdict(1, "resolved"))));
        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer("", verdict(1, "still-open"))));

        List<HarnessReviewGenerated> generated = emitted.stream().filter(HarnessReviewGenerated.class::isInstance)
                .map(HarnessReviewGenerated.class::cast).toList();
        assertEquals(2, generated.size(), "a redelivery re-emits, so a lost emit still converges");
        assertEquals(generated.get(0), generated.get(1), "the first outcome is persisted and re-emitted as is");
        assertEquals(1, compareFetches, "the redelivery reads the persisted outcome and compares nothing");
    }

    @Test
    void aFinishWithoutItsPreparedPriorJudgesNothing() {
        worker.finish(finish(ReviewRunBinding.Kind.RECONCILE, answer(finding("src/C.java", 20), verdict(1, "resolved"))));

        HarnessReviewGenerated generated = last(HarnessReviewGenerated.class);
        assertEquals(List.of(), generated.verdicts(), "with no numbering to read, no id can be trusted");
        assertEquals(1, generated.result().findings().size(), "the new findings still count");
    }

    @Test
    void aTransientFaultWhileFinishingPropagatesForReplay() {
        worker.idempotency = failingStore(new IllegalStateException("TEST-db", new SQLException("TEST-connection")));

        assertThrows(RuntimeException.class, () -> worker.finish(finish(ReviewRunBinding.Kind.REVIEW,
                answer(finding("src/C.java", 20), ""))), "the record dead-letters with its paid answer, for a replay");
        assertTrue(emitted.isEmpty(), "a database hiccup never fails the review: " + emitted);
    }

    @Test
    void aNonRetryableFinishFailureFailsTheReview() {
        worker.idempotency = failingStore(new IllegalArgumentException("TEST-permanent"));

        worker.finish(finish(ReviewRunBinding.Kind.REVIEW, answer(finding("src/C.java", 20), "")));

        ReviewFailed failed = last(ReviewFailed.class);
        assertEquals("harness-finish", failed.phase());
        assertFalse(failed.retryable(), "a retry would meet the same run id and never answer");
        assertEquals(1, failed.attempt());
        assertFalse(failed.credentialRejected());
    }

    /** Every store method a finish reaches throws the given fault. */
    private static CommentIdempotencyStore failingStore(RuntimeException fault) {
        return new CommentIdempotencyStore() {
            @Override
            public Claim claim(String reviewId, String commit, String anchorKey) {
                throw fault;
            }

            @Override
            public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
                throw fault;
            }

            @Override
            public Map<String, String> postedFor(String reviewId, String commit) {
                throw fault;
            }
        };
    }
}
