package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.FinishHarnessReview;
import dev.codespire.contract.command.ActionCommand.PrepareHarnessReview;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewGenerated;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Harness reviews, step 6: the worker, fakes and fixtures {@link HarnessReviewWorkerPrepareTest} and
 * {@link HarnessReviewWorkerFinishTest} share. The fakes are {@link ReviewWorkerTest}'s, so both paths are judged
 * against one SCM, one blob store and one idempotency store. A test sets the knobs ({@link #head},
 * {@link #prFailure}, {@link #compareDiff}, {@link #compareFailure}) and reads {@link #compareFetches}.
 */
final class HarnessReviewWorkerFixture {

    static final RepoRef REPO = new RepoRef("TEST-work", "app");
    static final String REVIEW_ID = "review::TEST-work/app#7";
    static final String COMMIT = "a".repeat(40);
    static final String PRIOR_HEAD = "b".repeat(40);
    static final int ROUND = 2;
    /** Changes src/A.java around line 3 only: a finding there is touched, one in src/B.java is not. */
    static final String TOUCHES_A3 = "diff --git a/src/A.java b/src/A.java\n"
            + "--- a/src/A.java\n+++ b/src/A.java\n@@ -2,3 +2,3 @@\n line2\n-old3\n+new3\n line4\n";
    static final String RENAME_DIFF = """
            diff --git a/src/Old.java b/src/New.java
            rename from src/Old.java
            rename to src/New.java
            """;
    static final String OVER_CAP_SUMMARY = "The harness answer was larger than 256 KiB and was not read.";

    final HarnessReviewWorker worker = new HarnessReviewWorker();
    final List<IntegrationEvent> emitted = new ArrayList<>();
    final ReviewWorkerTest.InMemoryIdempotency idempotency = new ReviewWorkerTest.InMemoryIdempotency();
    final ReviewWorkerTest.InMemoryBlobStore blobs = new ReviewWorkerTest.InMemoryBlobStore();
    String head = COMMIT;
    RuntimeException prFailure;
    String compareDiff = TOUCHES_A3;
    RuntimeException compareFailure;
    int compareFetches;

    HarnessReviewWorkerFixture() {
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
        wireScm(new FakeDiff("TEST-" + UUID.randomUUID() + ".example.invalid"));
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

    /** A fresh host per fixture, so a failing read never trips the shared breaker for another test. */
    private final class FakeDiff implements DiffSource {
        private final String host;

        FakeDiff(String host) {
            this.host = host;
        }

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
    }

    // --- fixtures ---

    static PriorFinding priorA() {
        return new PriorFinding("src/A.java", 3, Severity.MAJOR, "TEST-FIRST-FINDING", "TEST-thread-1");
    }

    static PriorFinding priorB() {
        return new PriorFinding("src/B.java", 9, Severity.MINOR, "TEST-SECOND-FINDING", "TEST-thread-2");
    }

    static PriorRun prior(PriorFinding... findings) {
        return new PriorRun(PRIOR_HEAD, "TEST-summary", List.of(findings));
    }

    static PrepareHarnessReview prepareReview(String contextRef) {
        return new PrepareHarnessReview(REVIEW_ID, REPO, 7, COMMIT, contextRef, null, null, ROUND,
                ReviewRunBinding.Kind.REVIEW);
    }

    static PrepareHarnessReview prepareReReview(PriorRun prior) {
        return new PrepareHarnessReview(REVIEW_ID, REPO, 7, COMMIT, null, null, prior, ROUND,
                ReviewRunBinding.Kind.RECONCILE);
    }

    static FinishHarnessReview finish(ReviewRunBinding.Kind kind, String answer) {
        return new FinishHarnessReview(REVIEW_ID, REPO, 7, COMMIT, ROUND, kind, answer,
                ModelUsage.of("TEST-model", 1, 1), null);
    }

    static String finding(String path, int line) {
        return "{\"path\":\"" + path + "\",\"line\":" + line + ",\"endLine\":" + line
                + ",\"severity\":\"MAJOR\",\"message\":\"TEST-NEW-FINDING\",\"suggestion\":null}";
    }

    static String answer(String findingsJson, String verdictsJson) {
        return "{\"summary\":\"TEST-summary\",\"findings\":[" + findingsJson + "],\"verdicts\":[" + verdictsJson + "]}";
    }

    static String verdict(int id, String status) {
        return "{\"id\":" + id + ",\"status\":\"" + status + "\",\"note\":\"TEST-note\"}";
    }

    /** What a prepare persists: seeded directly, so a finish test does not depend on the prepare. */
    void seedPreparedPrior(boolean incremental, PriorFinding... numbered) throws Exception {
        HarnessReviewWorker.PreparedPrior prepared =
                new HarnessReviewWorker.PreparedPrior(PRIOR_HEAD, incremental, List.of(numbered));
        idempotency.claim(REVIEW_ID, COMMIT, "harness:prepare#" + ROUND);
        idempotency.markPosted(REVIEW_ID, COMMIT, "harness:prepare#" + ROUND,
                new ObjectMapper().writeValueAsString(prepared));
    }

    String contextRef(ContextItem item) throws Exception {
        AssembledContext assembled = new AssembledContext("TEST-ctx", List.of(item), Set.of("GITHUB_ISSUES"), Set.of());
        return blobs.put(BlobStore.Kind.CONTEXT, REVIEW_ID, new ObjectMapper().writeValueAsBytes(assembled)).key();
    }

    <T extends IntegrationEvent> T last(Class<T> type) {
        for (int i = emitted.size() - 1; i >= 0; i--) {
            if (type.isInstance(emitted.get(i))) {
                return type.cast(emitted.get(i));
            }
        }
        return fail("no " + type.getSimpleName() + " was emitted: " + emitted);
    }

    static String file(HarnessReviewInputs inputs, String name) {
        for (HarnessReviewInputs.InputFile file : inputs.files()) {
            if (file.name().equals(name)) {
                return file.text();
            }
        }
        return null;
    }

    static FindingVerdict verdictFor(HarnessReviewGenerated generated, String threadRef) {
        return generated.verdicts().stream().filter(v -> threadRef.equals(v.threadRef())).findFirst()
                .orElseGet(() -> fail("no verdict for " + threadRef + ": " + generated.verdicts()));
    }
}
