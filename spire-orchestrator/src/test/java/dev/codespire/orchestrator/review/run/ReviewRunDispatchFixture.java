package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FactoryConfig;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.factory.HarnessCredentialPool;
import dev.codespire.orchestrator.factory.RunCredentials;
import dev.codespire.orchestrator.factory.RunFunding;
import dev.codespire.orchestrator.factory.RunLaunch;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.repository.RepositoryAccounts;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The shared fixture of the review run dispatch tests: every collaborator is faked, and a fake method the path must not
 * reach throws.
 */
abstract class ReviewRunDispatchFixture {

    static final UUID REPOSITORY = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    static final UUID MEMBER = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    static final ReviewRunBinding CALL =
            new ReviewRunBinding("review::TEST-WS/TEST-REPO#7", ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot", 1);
    static final ReviewRunRequest REQUEST = new ReviewRunRequest(REPOSITORY, CALL,
            new RunFunding.Choice("TEST-harness", "TEST-model", null, false), "TEST-prompt", HarnessReviewInputs.NONE);

    String fundingRefusal;
    boolean rowAccepted = true;
    RunLaunch.Outcome launchOutcome = new RunLaunch.Dispatched();
    String destBranch = "TEST-main";
    String sourceBranch = "TEST-source";
    String providerType = "github";
    Boolean fromFork = false;

    final List<FactoryRunProjection.QueuedRun> rows = new ArrayList<>();
    final List<RunCommand> reviewLane = new ArrayList<>();
    final List<RunCommand> buildLane = new ArrayList<>();
    final List<ProviderRole> accountsAsked = new ArrayList<>();

    ReviewRunDispatch dispatch() {
        ReviewRunDispatch dispatch = new ReviewRunDispatch();
        dispatch.funding = funding();
        dispatch.admission = admission();
        dispatch.launch = launch();
        dispatch.accounts = accounts();
        dispatch.targets = targets();
        dispatch.credentials = credentials();
        dispatch.config = config();
        return dispatch;
    }


    RunFunding funding() {
        return new RunFunding() {
            @Override
            public Funded fund(Choice choice) {
                if (fundingRefusal != null) throw new IllegalStateException(fundingRefusal);
                return new Funded(new HarnessCredentialPool.PoolMember(MEMBER, "TEST-pool", "TEST-type",
                        "https://example.invalid", "TEST-harness-key"), "TEST-harness-key", "TEST-image");
            }

            @Override
            public ScmProvider account(Connection c, UUID repository) {
                throw new AssertionError("a review clones with the reviewer account, never the factory's");
            }
        };
    }

    /** The real one admits under the review's lock in a transaction; ReviewRunDispatchRaceTest drives that one. */
    ReviewRunAdmission admission() {
        return new ReviewRunAdmission() {
            @Override
            public Admission admit(FactoryRunProjection.QueuedRun row, UUID repositoryId) {
                rows.add(row);
                return rowAccepted ? Admission.ADMITTED : Admission.ALREADY_QUEUED;
            }
        };
    }

    RunLaunch launch() {
        return new RunLaunch() {
            @Override public Outcome launch(RunCommand.ExecuteReviewRun command) { reviewLane.add(command); return launchOutcome; }
            @Override public Outcome launch(RunCommand.ExecuteUntrustedReviewRun command) { reviewLane.add(command); return launchOutcome; }
            @Override public Outcome launch(RunCommand.ExecuteRun command) { buildLane.add(command); return launchOutcome; }
            @Override public Outcome launch(RunCommand.ExecuteWorkRun command) { buildLane.add(command); return launchOutcome; }
            @Override public Outcome launch(RunCommand.ExecuteWorkDraftRun command) { buildLane.add(command); return launchOutcome; }
        };
    }

    RepositoryAccounts accounts() {
        return new RepositoryAccounts() {
            @Override
            public Optional<ScmProvider> resolve(UUID repositoryId, ProviderRole role) {
                accountsAsked.add(role);
                return Optional.of(new ScmProvider(UUID.randomUUID(), "TEST-reviewer", "github", "https://forge.example.test",
                        "bearer", null, "TEST-reviewer-secret", "TEST-reviewer-id", true, List.of(), "TEST-reviewer", null, role));
            }
        };
    }

    FixTargets targets() {
        return new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String reviewId) {
                return Optional.of(new PushTarget(providerType, "TEST-WS", "TEST-REPO", 7, sourceBranch, destBranch, HEAD, "OPEN", fromFork));
            }
        };
    }

    RunCredentials credentials() {
        return new RunCredentials() {
            @Override
            public String packScm(String runId, String username, String secret) {
                return "TEST-packed-scm:" + runId + ":" + username;
            }

            @Override
            public String packHarness(String runId, String apiKey) {
                return "TEST-packed-harness:" + runId;
            }
        };
    }

    static FactoryConfig config() {
        return new FactoryConfig() {
            @Override public Map<String, String> agentImage() { return Map.of(); }
            @Override public long wallClockSeconds() { return 1800; }
            @Override public long reviewWallClockSeconds() { return 600; }
            @Override public Fix fix() { throw new AssertionError("a review run is not a fix"); }
        };
    }
}
