package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.port.ScmType;
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
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a funded review call becomes one queued row and one command on the review lane, and a refusal costs nothing.
 * A plain unit test: every collaborator is faked, and a fake method this path must not reach throws.
 */
class ReviewRunDispatchTest {

    private static final UUID REPOSITORY = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final ReviewRunBinding CALL =
            new ReviewRunBinding("review::TEST-WS/TEST-REPO#7", ReviewRunBinding.Kind.REVIEW, 7, HEAD, "TEST-slot", 1);
    private static final ReviewRunRequest REQUEST = new ReviewRunRequest(REPOSITORY, CALL,
            new RunFunding.Choice("TEST-harness", "TEST-model", null, false), "TEST-prompt");

    private String fundingRefusal;
    private boolean rowAccepted = true;
    private RunLaunch.Outcome launchOutcome = new RunLaunch.Dispatched();

    private final List<FactoryRunProjection.QueuedRun> rows = new ArrayList<>();
    private final List<RunCommand> reviewLane = new ArrayList<>();
    private final List<RunCommand> buildLane = new ArrayList<>();
    private final List<ProviderRole> accountsAsked = new ArrayList<>();

    private ReviewRunDispatch dispatch() {
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

    @Test
    void aFundedReviewRunIsQueuedAndSentOnTheReviewLane() {
        ReviewRunDispatch.Outcome outcome = dispatch().dispatch(REQUEST);

        String runId = ReviewRunIds.of(REPO, ScmType.GITHUB, CALL);
        assertEquals(new ReviewRunDispatch.Dispatched(runId), outcome);
        assertEquals(1, rows.size());
        assertEquals("REVIEW", rows.getFirst().kind());
        assertEquals(CALL.reviewId(), rows.getFirst().reviewId());
        assertEquals(HEAD, rows.getFirst().baseCommit(), "the row's base commit is the head the review reads");
        assertTrue(buildLane.isEmpty(), "a review never rides the build lane");
        RunCommand.ExecuteReviewRun command = assertInstanceOf(RunCommand.ExecuteReviewRun.class, reviewLane.getFirst());
        assertEquals(CALL, command.review());
        assertEquals(HEAD, command.execution().baseCommit());
        assertEquals("TEST-source", command.execution().branch());
        assertEquals(600, command.execution().maxWallClockSeconds());
        assertEquals("TEST-packed-scm:" + runId + ":TEST-reviewer", command.execution().scmCredential(),
                "the clone credential is the reviewer account's");
        assertEquals(List.of(ProviderRole.REVIEWER), accountsAsked, "the factory account is never used");
    }

    @Test
    void aSpentPoolIsRefusedWithItsReason() {
        fundingRefusal = "subscription_unavailable";

        ReviewRunDispatch.Outcome outcome = dispatch().dispatch(REQUEST);

        assertEquals(new ReviewRunDispatch.Refused("subscription_unavailable"), outcome);
        assertTrue(rows.isEmpty(), "a refusal writes no row");
        assertTrue(reviewLane.isEmpty() && buildLane.isEmpty(), "and sends nothing");
    }

    /** The id is deterministic, so a redelivered dispatch matches the row the first one wrote and sends nothing. */
    @Test
    void aCallAlreadyQueuedIsNotSentAgain() {
        rowAccepted = false;

        ReviewRunDispatch.Outcome outcome = dispatch().dispatch(REQUEST);

        assertEquals(new ReviewRunDispatch.AlreadyQueued(ReviewRunIds.of(REPO, ScmType.GITHUB, CALL)), outcome);
        assertTrue(reviewLane.isEmpty());
    }

    @Test
    void anUnacknowledgedLaunchIsUncertain() {
        launchOutcome = new RunLaunch.Uncertain(new IllegalStateException("TEST-no-ack"));

        ReviewRunDispatch.Outcome outcome = dispatch().dispatch(REQUEST);

        assertEquals(new ReviewRunDispatch.Uncertain(ReviewRunIds.of(REPO, ScmType.GITHUB, CALL)), outcome);
    }

    private RunFunding funding() {
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
    private ReviewRunAdmission admission() {
        return new ReviewRunAdmission() {
            @Override
            public Admission admit(FactoryRunProjection.QueuedRun row, UUID repositoryId) {
                rows.add(row);
                return rowAccepted ? Admission.ADMITTED : Admission.ALREADY_QUEUED;
            }
        };
    }

    private RunLaunch launch() {
        return new RunLaunch() {
            @Override public Outcome launch(RunCommand.ExecuteReviewRun command) { reviewLane.add(command); return launchOutcome; }
            @Override public Outcome launch(RunCommand.ExecuteRun command) { buildLane.add(command); return launchOutcome; }
            @Override public Outcome launch(RunCommand.ExecuteWorkRun command) { buildLane.add(command); return launchOutcome; }
            @Override public Outcome launch(RunCommand.ExecuteWorkDraftRun command) { buildLane.add(command); return launchOutcome; }
        };
    }

    private RepositoryAccounts accounts() {
        return new RepositoryAccounts() {
            @Override
            public Optional<ScmProvider> resolve(UUID repositoryId, ProviderRole role) {
                accountsAsked.add(role);
                return Optional.of(new ScmProvider(UUID.randomUUID(), "TEST-reviewer", "github", "https://forge.example.test",
                        "bearer", null, "TEST-reviewer-secret", "TEST-reviewer-id", true, List.of(), "TEST-reviewer", null, role));
            }
        };
    }

    private FixTargets targets() {
        return new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String reviewId) {
                return Optional.of(new PushTarget("github", "TEST-WS", "TEST-REPO", 7, "TEST-source", "main", HEAD, "OPEN", false));
            }
        };
    }

    private RunCredentials credentials() {
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

    private static FactoryConfig config() {
        return new FactoryConfig() {
            @Override public Map<String, String> agentImage() { return Map.of(); }
            @Override public long wallClockSeconds() { return 1800; }
            @Override public long reviewWallClockSeconds() { return 600; }
            @Override public Fix fix() { throw new AssertionError("a review run is not a fix"); }
        };
    }
}
