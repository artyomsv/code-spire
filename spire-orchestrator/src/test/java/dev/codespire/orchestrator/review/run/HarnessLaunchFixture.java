package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent.HarnessReviewPrepared;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The real {@link HarnessReviewLaunch} over fakes, for {@code HarnessReviewLaunchTest}: the repositories, the projection,
 * the push targets, the dispatch, the round counter, the prompt templates and the round lock each override every method
 * the launch reaches, because an un-overridden one opens a real database from a unit test (CLAUDE.md, saga test fakes).
 * Each test sets the state below, then calls {@link #launch()}.
 */
final class HarnessLaunchFixture {

    static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    static final long PR = 7L;
    static final String REVIEW_ID = ReviewIds.reviewId(REPO, PR);
    static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    static final UUID REPOSITORY = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    static final int CURRENT_RUN = 2;
    static final HarnessReviewInputs INPUTS = new HarnessReviewInputs(List.of(
            new HarnessReviewInputs.InputFile(HarnessReviewInputs.CONTEXT, "TEST-CONTEXT")));
    static final HarnessReviewInputs RE_REVIEW_INPUTS = new HarnessReviewInputs(List.of(
            new HarnessReviewInputs.InputFile(HarnessReviewInputs.CONTEXT, "TEST-CONTEXT"),
            new HarnessReviewInputs.InputFile(HarnessReviewInputs.PRIOR_FINDINGS, "TEST-PRIOR")));

    Optional<ReviewHarness> harness =
            Optional.of(new ReviewHarness("TEST-harness", "TEST-model", "high", "SUBSCRIPTION"));
    ReviewRunDispatch.Outcome dispatchOutcome = new ReviewRunDispatch.Dispatched("TEST-run-1");
    int round = CURRENT_RUN;
    /** Null, as the real resolver answers for a repository with no customized prompt (the common case). */
    PromptTemplate customReviewPrompt;
    RuntimeException dispatchFailure;
    RuntimeException promptFailure;
    /** Answers as admission does: the deterministic run id of a call already queued is AlreadyQueued. */
    boolean deterministicIds;
    /** A forced re-run that held the review's round lock first: it ends the round before the launch reads it. */
    boolean rerunWhileWaiting;

    final List<ReviewRunRequest> dispatched = new ArrayList<>();
    /** What ran, in order: the lock, the round read, the dispatch, and what the caller applied inside the lock. */
    final List<String> steps = new ArrayList<>();
    private final Set<String> queuedRunIds = new HashSet<>();

    static HarnessReviewPrepared prepared(ReviewRunBinding.Kind kind, int preparedRound) {
        return new HarnessReviewPrepared(REVIEW_ID, PR, HEAD, preparedRound, kind, INPUTS);
    }

    HarnessReviewLaunch launch() {
        HarnessReviewLaunch launch = new HarnessReviewLaunch();
        launch.repositories = repositories();
        launch.projection = projection();
        launch.targets = targets();
        launch.dispatch = dispatch();
        launch.runs = runs();
        launch.promptTemplates = prompts();
        launch.rounds = roundLock();
        return launch;
    }

    /** The real lock is a Postgres advisory lock (ReviewRoundLockTest); this one records what ran inside it. */
    private ReviewRoundLock roundLock() {
        return new ReviewRoundLock() {
            @Override
            public <T> T holding(String reviewId, Supplier<T> work) {
                steps.add("lock " + reviewId);
                if (rerunWhileWaiting) {
                    round++;
                }
                T done = work.get();
                steps.add("unlock");
                return done;
            }
        };
    }

    private RepositoryRegistry repositories() {
        return new RepositoryRegistry() {
            @Override
            public Optional<ReviewHarness> reviewHarness(UUID repositoryId) {
                return REPOSITORY.equals(repositoryId) ? harness : Optional.empty();
            }
        };
    }

    /** The launch reads the repository id only; the prior run was read at start and numbered by the worker. */
    private ReviewProjection projection() {
        return new ReviewProjection() {
            @Override
            public Optional<UUID> repositoryIdOf(String reviewId) {
                return Optional.of(REPOSITORY);
            }
        };
    }

    private FixTargets targets() {
        return new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String reviewId) {
                return Optional.of(new PushTarget("github", REPO.workspace(), REPO.slug(), PR, "TEST-source",
                        "TEST-main", HEAD, "OPEN", false));
            }
        };
    }

    private ReviewRunDispatch dispatch() {
        return new ReviewRunDispatch() {
            @Override
            public Outcome dispatch(ReviewRunRequest request) {
                if (dispatchFailure != null) {
                    throw dispatchFailure;
                }
                steps.add("dispatch");
                dispatched.add(request);
                return deterministicIds ? admitted(request) : dispatchOutcome;
            }
        };
    }

    /** What the real dispatch answers: the run id is derived from the call, and a second admission finds its row. */
    private ReviewRunDispatch.Outcome admitted(ReviewRunRequest request) {
        String runId = ReviewRunIds.of(REPO, ScmType.GITHUB, request.binding());
        return queuedRunIds.add(runId) ? new ReviewRunDispatch.Dispatched(runId)
                : new ReviewRunDispatch.AlreadyQueued(runId);
    }

    /** The real ones read the event log; the ledger's read answers run 1 when the log cannot be read. */
    private ReviewRuns runs() {
        return new ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return round == ReviewRuns.UNKNOWN_RUN ? ReviewRuns.FIRST_RUN : round;
            }

            @Override
            public int roundOrUnknown(String reviewId) {
                steps.add("round");
                return round;
            }
        };
    }

    private WorkerPromptTemplates prompts() {
        return new WorkerPromptTemplates() {
            @Override
            public PromptTemplate forKind(PromptKind kind, RepoRef repo) {
                if (promptFailure != null) {
                    throw promptFailure;
                }
                return kind == PromptKind.REVIEW ? customReviewPrompt : null;
            }
        };
    }
}
