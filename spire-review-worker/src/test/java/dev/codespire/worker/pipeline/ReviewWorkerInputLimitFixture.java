package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.diff.UnifiedDiffParser;
import dev.codespire.llm.DiffBudget;
import dev.codespire.worker.adapters.WorkerLlmProvider;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Shared fixtures for the review input limit tests (design 2026-11-26, PR 456): a {@link ReviewWorker} with its own
 * fakes, so {@code ReviewWorkerTest} does not grow. The test classes extend it.
 */
abstract class ReviewWorkerInputLimitFixture {

    static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    static final String REVIEW_ID = "review::sandbox/demo-repo#9";
    static final String COMMIT = "abc123";
    static final String SMALL_DIFF = fileDiff("src/A.java", 3);
    /** A window large enough that the diff budget is far above today's 24,000. */
    static final DiffBudget.Limits LARGE_WINDOW = new DiffBudget.Limits(983_616, null);
    static final String RESOLVED = "{\"verdicts\":[{\"id\":1,\"status\":\"resolved\",\"note\":\"fixed\"}]}";

    ReviewWorker worker;
    List<IntegrationEvent> emitted;
    ReviewWorkerTest.RecordingSink sink;
    ReviewWorkerTest.InMemoryIdempotency idempotency;
    List<Prompt> llmCalls;
    String diffText;
    String compareDiff;
    String reconcileResponse;

    @BeforeEach
    void setUp() {
        emitted = new ArrayList<>();
        sink = new ReviewWorkerTest.RecordingSink();
        idempotency = new ReviewWorkerTest.InMemoryIdempotency();
        llmCalls = new ArrayList<>();
        diffText = SMALL_DIFF;
        compareDiff = "diff --git a/inc b/inc";
        reconcileResponse = RESOLVED;

        worker = new ReviewWorker();
        worker.promptLog = new PromptLog();
        worker.mapper = new ObjectMapper();
        worker.inlinePostThrottleMs = 0;
        worker.rateLimitRetryCapSeconds = 0;
        worker.rateLimitBudgetSeconds = 180;
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
            }
        };
        worker.idempotency = idempotency;
        AssembledContextReader reader = new AssembledContextReader();
        reader.blobStore = new ReviewWorkerTest.InMemoryBlobStore();
        reader.mapper = new ObjectMapper();
        worker.contextReader = reader;
        DiffSource diffs = new HeadDiffs();
        worker.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(diffs, sink);
            }
        };
        worker.llm = llm(recordingLlm(), DiffBudget.Limits.UNKNOWN);
    }

    // --- fixtures ---

    static GenerateReview firstReview() {
        return new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null);
    }

    static GenerateReview followUp(PriorRun prior) {
        return new GenerateReview(REVIEW_ID, REPO, 9, COMMIT, null, 1, null, null, null, prior);
    }

    static PriorRun priorAtDemo5() {
        return new PriorRun("aaa111", "sum-1",
                List.of(new PriorFinding("src/Demo.java", 5, Severity.MAJOR, "leak", "t-1")));
    }

    static Finding finding(String path, int line) {
        return new Finding(path, new LineRange(line, line), Severity.INFO, "msg " + line, null);
    }

    ReviewGenerated lastReviewGenerated() {
        return assertInstanceOf(ReviewGenerated.class, emitted.getLast());
    }

    /** About 38,000 estimated tokens over two files: above today's 24,000. */
    static String largeDiff() {
        return fileDiff("src/TEST-a.java", 3_000) + fileDiff("src/TEST-b.java", 3_000);
    }

    /** The diff fixture spire-llm's tests use, repeated here: test classes of another module are not on this path. */
    static String fileDiff(String path, int lines) {
        StringBuilder diff = new StringBuilder("diff --git a/" + path + " b/" + path + "\n--- a/" + path
                + "\n+++ b/" + path + "\n@@ -1,1 +1," + lines + " @@\n");
        for (int i = 0; i < lines; i++) {
            diff.append("+TEST line ").append(i).append('\n');
        }
        return diff.toString();
    }

    static WorkerLlmProvider llm(LlmProvider provider, DiffBudget.Limits limits) {
        return new WorkerLlmProvider() {
            @Override
            public LlmClient forCommand(GenerateReview command) {
                return new LlmClient(provider, new ModelParams(provider.id(), 0.2, null), limits);
            }
        };
    }

    /** Answers the reconcile with {@link #reconcileResponse} and the review with no findings, recording each prompt. */
    LlmProvider recordingLlm() {
        return new LlmProvider() {
            @Override
            public String id() {
                return "test-llm";
            }

            @Override
            public CompletionStage<Completion> complete(Prompt prompt, ModelParams params) {
                llmCalls.add(prompt);
                String text = prompt.user().contains("Prior findings")
                        ? reconcileResponse : "{\"summary\":\"s\",\"findings\":[]}";
                return CompletableFuture.completedFuture(new Completion(text, ModelUsage.of("m", 1, 1)));
            }
        };
    }

    static LlmProvider failingLlm(RuntimeException failure) {
        return new LlmProvider() {
            @Override
            public String id() {
                return "test-llm";
            }

            @Override
            public CompletionStage<Completion> complete(Prompt prompt, ModelParams params) {
                return CompletableFuture.failedFuture(failure);
            }
        };
    }

    static RuntimeException langChain4jTimeout() throws Exception {
        return (RuntimeException) Class.forName("dev.langchain4j.exception.TimeoutException")
                .getConstructor(String.class).newInstance("TEST request timed out");
    }

    /** The pull request at {@link #COMMIT}, its diff {@link #diffText}, and its compare {@link #compareDiff}. */
    final class HeadDiffs implements DiffSource {
        @Override
        public ScmType type() {
            return ScmType.BITBUCKET_CLOUD;
        }

        @Override
        public String apiHost() {
            return "api.example.invalid";
        }

        @Override
        public PullRequest fetchPullRequest(RepoRef repo, long prId) {
            return new PullRequest(repo, prId, "TEST pr", "TEST", "feature/t", "main",
                    COMMIT, Author.of("TEST-id", "test", "Test"), "https://example.invalid");
        }

        @Override
        public Diff fetchDiff(RepoRef repo, long prId, String commit) {
            return new Diff(commit, UnifiedDiffParser.parse(diffText), false);
        }

        @Override
        public String fetchCompareDiff(RepoRef repo, String base, String head) {
            return compareDiff;
        }
    }
}
