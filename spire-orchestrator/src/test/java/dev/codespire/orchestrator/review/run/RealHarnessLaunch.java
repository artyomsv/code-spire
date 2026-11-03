package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FactoryConfig;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.factory.HarnessCredentialPool;
import dev.codespire.orchestrator.factory.RunCredentials;
import dev.codespire.orchestrator.factory.RunFunding;
import dev.codespire.orchestrator.factory.RunLaunch;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The real {@link HarnessReviewLaunch} over the real {@link ReviewRunDispatch}, launching through the {@link RunLaunch}
 * it is given (harness reviews, step 6: the dispatch moved from the start to the launch). Only the reads and writes that
 * need a database are fakes: the repository's harness, the pull request target, the round, the prompt override,
 * funding, admission, the reviewer account, credential packing and the config. A same-repository review on an API-key
 * harness in round 1, so the launch reaches the dispatch.
 *
 * <p>Public for {@code HarnessDispatchFailureTest} in the pipeline package; the beans' fields are package-private.
 * Every fake overrides each method this path reaches (CLAUDE.md, saga test fakes).
 */
public final class RealHarnessLaunch {

    private static final ReviewHarness HARNESS = new ReviewHarness("TEST-harness", "TEST-model", null, "API_KEY");
    private static final UUID POOL_MEMBER = UUID.fromString("00000000-0000-4000-8000-0000000000d2");

    private RealHarnessLaunch() {
    }

    /**
     * @param projection the review read model; it answers the repository id
     * @param launch     the launch the dispatch hands its command to
     * @param head       the head the review reads
     */
    public static HarnessReviewLaunch launch(ReviewProjection projection, RunLaunch launch, String head) {
        FixTargets targets = targets(head);
        HarnessReviewLaunch harnessLaunch = new HarnessReviewLaunch();
        harnessLaunch.repositories = repositories();
        harnessLaunch.projection = projection;
        harnessLaunch.targets = targets;
        harnessLaunch.dispatch = dispatch(targets, launch);
        harnessLaunch.runs = firstRound();
        harnessLaunch.promptTemplates = noCustomPrompt();
        harnessLaunch.rounds = inlineLock();
        return harnessLaunch;
    }

    /** The real lock is a Postgres advisory lock (ReviewRoundLockTest drives it); here it runs the work inline. */
    private static ReviewRoundLock inlineLock() {
        return new ReviewRoundLock() {
            @Override
            public <T> T holding(String reviewId, Supplier<T> work) {
                return work.get();
            }
        };
    }

    private static ReviewRunDispatch dispatch(FixTargets targets, RunLaunch launch) {
        ReviewRunDispatch dispatch = new ReviewRunDispatch();
        dispatch.funding = funding();
        dispatch.admission = admitted();
        dispatch.launch = launch;
        dispatch.accounts = reviewer();
        dispatch.targets = targets;
        dispatch.credentials = credentials();
        dispatch.config = config();
        return dispatch;
    }

    private static RepositoryRegistry repositories() {
        return new RepositoryRegistry() {
            @Override
            public Optional<ReviewHarness> reviewHarness(UUID repositoryId) {
                return Optional.of(HARNESS);
            }
        };
    }

    /** The review's own pull request, same repository, with a target branch to diff against. */
    private static FixTargets targets(String head) {
        return new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String reviewId) {
                ReviewIds.Parsed parsed = ReviewIds.parse(reviewId);
                return Optional.of(new PushTarget("github", parsed.repo().workspace(), parsed.repo().slug(),
                        parsed.prId(), "TEST-source", "TEST-main", head, "OPEN", false));
            }
        };
    }

    private static ReviewRuns firstRound() {
        return new ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return ReviewRuns.FIRST_RUN;
            }

            @Override
            public int roundOrUnknown(String reviewId) {
                return ReviewRuns.FIRST_RUN;
            }
        };
    }

    /** Null, as the real resolver answers for a repository with no customized prompt. */
    private static WorkerPromptTemplates noCustomPrompt() {
        return new WorkerPromptTemplates() {
            @Override
            public PromptTemplate forKind(PromptKind kind, RepoRef repo) {
                return null;
            }
        };
    }

    private static RunFunding funding() {
        return new RunFunding() {
            @Override
            public Funded fund(Choice choice) {
                return new Funded(new HarnessCredentialPool.PoolMember(POOL_MEMBER, "TEST-pool", "TEST-type",
                        "https://example.invalid", "TEST-harness-key"), "TEST-harness-key", "TEST-image");
            }

            @Override
            public ScmProvider account(Connection c, UUID repository) {
                throw new AssertionError("a review clones with the reviewer account, never the factory's");
            }
        };
    }

    /** The real one admits under the review's lock in a transaction; ReviewRunDispatchRaceTest drives that one. */
    private static ReviewRunAdmission admitted() {
        return new ReviewRunAdmission() {
            @Override
            public Admission admit(FactoryRunProjection.QueuedRun row, UUID repositoryId) {
                return Admission.ADMITTED;
            }
        };
    }

    private static RepositoryAccounts reviewer() {
        return new RepositoryAccounts() {
            @Override
            public Optional<ScmProvider> resolve(UUID repositoryId, ProviderRole role) {
                return Optional.of(new ScmProvider(UUID.randomUUID(), "TEST-reviewer", "github",
                        "https://forge.example.test", "bearer", null, "TEST-reviewer-secret", "TEST-reviewer-id", true,
                        List.of(), "TEST-reviewer", null, role));
            }
        };
    }

    private static RunCredentials credentials() {
        return new RunCredentials() {
            @Override
            public String packScm(String runId, String username, String secret) {
                return "TEST-packed-scm:" + runId;
            }

            @Override
            public String packHarness(String runId, String apiKey) {
                return "TEST-packed-harness:" + runId;
            }
        };
    }

    private static FactoryConfig config() {
        return new FactoryConfig() {
            @Override public Map<String, String> agentImage() { return Map.of(); }
            @Override public long wallClockSeconds() { return 1800; }
            @Override public long reviewWallClockSeconds() { return 600; }
            @Override public Fix fix() { throw new AssertionError("a review run is not a fix"); }
        };
    }
}
