package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.pipeline.ResultSaga;
import dev.codespire.orchestrator.provider.WorkerCredentials;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static dev.codespire.orchestrator.llm.ReviewRuns.FIRST_RUN;
import static dev.codespire.orchestrator.llm.ReviewRuns.UNKNOWN_RUN;

/**
 * The real {@link HarnessReviewResults} over fakes, shared by {@code HarnessReviewResultsTest} and
 * {@code HarnessReviewOutcomesTest}: the saga, the round counter, the round lock, the run rows, the credentials and the
 * emitter each override every method the path reaches, because an un-overridden one opens a real database or a broker
 * from a unit test (CLAUDE.md, saga test fakes). Each test sets the state below, then calls {@link #results()}.
 */
final class HarnessResultsFixture {

    static final String RUN_ID = "run::github:TEST-WS/TEST-REPO:review-7-aaaaaaaaaaaa-0123abcd:1";
    static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    static final String REVIEW_ID = ReviewIds.reviewId(REPO, 7L);
    static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    static final ReviewRunBinding ROUND_ONE =
            new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot", 1);
    static final ReviewRunBinding RECONCILE_ONE =
            new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.RECONCILE, 7, HEAD, "TEST-slot", 1);
    static final ReviewRunBinding ROUND_TWO =
            new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot-2", 2);

    static final String ONE_FINDING = """
            {"summary": "TEST-summary", "findings": [{"path": "src/TEST.java", "line": 12, "endLine": 12,
             "severity": "MAJOR", "category": "CORRECTNESS", "message": "TEST-message", "suggestion": null}]}
            """;

    int currentRun = 1;

    /** A forced re-run that held the review's round lock first: it ends the round before the outcome reads it. */
    boolean rerunWhileWaiting;

    Optional<String> reviewerCredential = Optional.of("TEST-packed-scm");

    final List<String> steps = new ArrayList<>();

    final List<IntegrationEvent> outcomes = new ArrayList<>();

    /** Refusals the saga was handed, as {@code reviewId@commit: attention reason}. */
    final List<String> refusals = new ArrayList<>();

    final List<ActionCommand> finishes = new ArrayList<>();

    /** What reached the reply results (harness reviews, step 7). */
    final List<RunResult> replyResults = new ArrayList<>();

    static RunReviewArtifact answer(String text, ReviewRunBinding binding) {
        return new RunReviewArtifact(RUN_ID, binding, text, false, Map.of("INPUT", 1200L, "OUTPUT", 340L), 4, List.of());
    }

    static RunResult.RunFailed failed(String cause) {
        return new RunResult.RunFailed(RUN_ID, cause, "TEST-detail", false, null, List.of());
    }

    static RunResult.RunFailed failed(String cause, String detail) {
        return new RunResult.RunFailed(RUN_ID, cause, detail, false, null, List.of());
    }

    HarnessReviewResults results() {
        HarnessReviewResults results = new HarnessReviewResults();
        results.saga = saga();
        results.runs = runs();
        results.rounds = roundLock();
        results.runRows = new FactoryRunProjection() {
            @Override public Optional<String> modelOf(String runId) { return Optional.of("TEST-model"); }
            @Override public Optional<String> harnessOf(String runId) { return Optional.of("TEST-harness"); }
            @Override public Optional<String> paidByOf(String runId) { return Optional.of("API_KEY"); }
            @Override protected void push(String runId) { /* nothing: no live feed from a unit test */ }
        };
        results.maxReportedTokens = -1;
        results.credentials = credentials();
        results.commands = commands();
        results.replies = new HarnessReplyResults() {
            @Override
            public void accept(RunResult result, ReviewRunBinding binding) {
                steps.add("reply");
                replyResults.add(result);
            }
        };
        return results;
    }

    private ResultSaga saga() {
        return new ResultSaga() {
            @Override
            public void acceptHarnessOutcome(IntegrationEvent outcome) {
                steps.add("outcome");
                outcomes.add(outcome);
            }

            @Override
            public void acceptHarnessRefusal(String reviewId, String commit, HarnessReviewStart.Refused refused) {
                steps.add("refusal");
                refusals.add(reviewId + "@" + commit + ": " + refused.attentionReason());
            }
        };
    }

    /** The real one resolves the reviewer account from the database and packs it with Tink. */
    private WorkerCredentials credentials() {
        return new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return reviewerCredential;
            }
        };
    }

    /** The real one awaits a Kafka ack. */
    private CommandsEmitter commands() {
        return new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                steps.add("finish");
                finishes.add(command);
            }
        };
    }

    /** The real ones read the event log; the ledger's read answers run 1 when the log cannot be read. */
    private ReviewRuns runs() {
        return new ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return currentRun == UNKNOWN_RUN ? FIRST_RUN : currentRun;
            }

            @Override
            public int roundOrUnknown(String reviewId) {
                steps.add("round");
                return currentRun;
            }
        };
    }

    /** The real lock is a Postgres advisory lock (ReviewRoundLockTest); this one records what ran inside it. */
    private ReviewRoundLock roundLock() {
        return new ReviewRoundLock() {
            @Override
            public <T> T holding(String reviewId, Supplier<T> work) {
                steps.add("lock " + reviewId);
                if (rerunWhileWaiting) {
                    currentRun++;
                }
                T done = work.get();
                steps.add("unlock");
                return done;
            }
        };
    }
}
