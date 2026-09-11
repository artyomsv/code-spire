package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.port.PullRequestSink;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.port.ScmType;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The step M2 shipped without: a pushed branch becomes a proposal.
 *
 * <p>Every test here asserts something the first live run got wrong or could not answer — that a
 * build run proposes, that a fix run does not, that a redelivered result does not ask twice, and
 * that a forge refusing the proposal does not unmake a push that already happened.
 */
class FactoryPullRequestsTest {

    private static final String RUN = "run::github:acme/web:issue-7:1";
    private static final RepoRef REPO = new RepoRef("acme", "web");

    // --- what the fakes are told to answer ---------------------------------------------------

    private Optional<FactoryRunProjection.PullRequestPlan> plan = Optional.of(
            new FactoryRunProjection.PullRequestPlan("BUILD", "main", "spire/issue-7",
                    "Fix the overflow in Pricer", null));
    private Optional<ScmProvider> account = Optional.of(machineAccount());
    private Optional<PullRequestRef> existing = Optional.empty();
    private RuntimeException openThrows;

    // --- what the fakes recorded -------------------------------------------------------------

    private final List<PullRequestSink.NewPullRequest> opened = new ArrayList<>();
    private final List<String> foundFor = new ArrayList<>();
    private final List<String> recorded = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    @Test
    void aBuildRunThatPushedIsProposedToItsForge() {
        propose(finished("refs/heads/spire/issue-7", List.of("src/main/java/com/pricing/Pricer.java")));

        assertEquals(1, opened.size());
        PullRequestSink.NewPullRequest request = opened.get(0);
        assertEquals("spire/issue-7", request.headBranch());
        assertEquals("main", request.baseBranch());
        assertTrue(request.title().contains("Fix the overflow in Pricer"), request.title());
        // The machine-readable mark and the run id: one for the reviewer's own gate, one so a human
        // reading the pull request can reach the transcript that explains it.
        assertTrue(request.bodyMd().contains(FactoryPullRequestBody.MARK));
        assertTrue(request.bodyMd().contains(RUN));
        assertTrue(request.bodyMd().contains("Pricer.java"));
        assertEquals(List.of(RUN + "|41|https://github.invalid/acme/web/pull/41"), recorded);
        assertTrue(failures.isEmpty());
    }

    /**
     * A fix run pushes onto the pull request's OWN source branch (ADR-040). Its change is already
     * proposed and already under review; a second proposal would put the same commits in front of
     * the same reviewer twice.
     */
    @Test
    void aFixRunProposesNothingBecauseItsChangeIsAlreadyProposed() {
        plan = Optional.of(new FactoryRunProjection.PullRequestPlan("FIX", "main", "feature/login",
                "take the locks in the declared order", null));

        propose(finished("refs/heads/feature/login", List.of("src/Foo.java")));

        assertTrue(opened.isEmpty(), "a fix run must not open a second pull request");
        assertTrue(foundFor.isEmpty(), "and must not even ask the forge");
        assertTrue(recorded.isEmpty());
    }

    @Test
    void aRunThatPushedNothingProposesNothing() {
        propose(new RunResult.RunFinished(RUN, null, List.of(),
                List.of(new RunResult.BlockedChange(".github/workflows/ci.yml", "PROTECTED")),
                Map.of("input", 10L), false));

        assertTrue(opened.isEmpty());
        assertTrue(recorded.isEmpty());
    }

    /**
     * A result record is redelivered on every consumer restart, and by then the proposal exists.
     * The row's own number is the first guard; {@link #anExistingProposalIsAdoptedRatherThanRepeated}
     * is the second.
     */
    @Test
    void aRunThatAlreadyProposedIsNotProposedTwice() {
        plan = Optional.of(new FactoryRunProjection.PullRequestPlan("BUILD", "main", "spire/issue-7",
                "Fix the overflow in Pricer", 41L));

        propose(finished("refs/heads/spire/issue-7", List.of("src/Foo.java")));

        assertTrue(opened.isEmpty());
        assertTrue(foundFor.isEmpty());
    }

    @Test
    void anExistingProposalIsAdoptedRatherThanRepeated() {
        existing = Optional.of(new PullRequestRef(9L, "https://github.invalid/acme/web/pull/9"));

        propose(finished("refs/heads/spire/issue-7", List.of("src/Foo.java")));

        assertEquals(List.of("spire/issue-7 -> main"), foundFor);
        assertTrue(opened.isEmpty(), "the forge already has one for this head");
        assertEquals(List.of(RUN + "|9|https://github.invalid/acme/web/pull/9"), recorded);
    }

    /**
     * The push already happened. Whatever the forge says about the proposal, the work is on the
     * remote and the run is a success — so the reason is recorded beside the run and nothing is
     * thrown back at the consumer, which would nack the record and re-run every consumer before it.
     */
    @Test
    void aForgeThatRefusesTheProposalDoesNotUnmakeThePush() {
        openThrows = new IllegalStateException("403 Forbidden: pull requests are disabled");

        propose(finished("refs/heads/spire/issue-7", List.of("src/Foo.java")));

        assertTrue(recorded.isEmpty());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("403 Forbidden"), failures.get(0));
    }

    /** The port's honest empty answer, which reads as a fault if it arrives as a class name. */
    @Test
    void nothingToProposeIsRecordedInWordsAnOperatorCanRead() {
        openThrows = new PullRequestSink.NothingToPropose("no commits between main and spire/issue-7", null);

        propose(finished("refs/heads/spire/issue-7", List.of("src/Foo.java")));

        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("nothing to propose"), failures.get(0));
        assertFalse(failures.get(0).contains("NothingToPropose"), "a class name is not an explanation");
    }

    /** The account can be deleted or disabled between the push and the proposal. */
    @Test
    void aMissingFactoryAccountIsRecordedRatherThanThrown() {
        account = Optional.empty();

        propose(finished("refs/heads/spire/issue-7", List.of("src/Foo.java")));

        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("FACTORY account"), failures.get(0));
    }

    // --- the harness -------------------------------------------------------------------------

    private void propose(RunResult result) {
        subject().propose(result);
    }

    private static RunResult.RunFinished finished(String pushedRef, List<String> changedPaths) {
        return new RunResult.RunFinished(RUN, pushedRef, changedPaths, List.of(),
                Map.of("input", 100L), false);
    }

    private FactoryPullRequests subject() {
        FactoryPullRequests pullRequests = new FactoryPullRequests();
        pullRequests.projection = new FactoryRunProjection() {
            @Override
            protected void push(String runId) {
                // All writes are recorded below; reaching the real broadcaster is a fake defect.
                throw new AssertionError("unexpected live broadcast");
            }

            @Override
            public Optional<PullRequestPlan> pullRequestPlanOf(String runId) {
                return plan;
            }

            @Override
            public void pullRequestOpened(String runId, long number, String url) {
                recorded.add(runId + "|" + number + "|" + url);
            }

            @Override
            public void pullRequestFailed(String runId, String detail) {
                failures.add(detail);
            }
        };
        pullRequests.machineAccounts = new MachineAccounts() {
            @Override
            public Optional<ScmProvider> resolve(ScmType scmType, String workspace) {
                return account;
            }
        };
        pullRequests.clients = new ProviderClients() {
            @Override
            public PullRequestSink pullRequestSink(ScmProvider provider) {
                return sink();
            }
        };
        return pullRequests;
    }

    private PullRequestSink sink() {
        return new PullRequestSink() {
            @Override
            public ScmType type() {
                return ScmType.GITHUB;
            }

            @Override
            public PullRequestRef open(RepoRef repo, NewPullRequest request) {
                if (openThrows != null) {
                    throw openThrows;
                }
                opened.add(request);
                return new PullRequestRef(41L, "https://github.invalid/acme/web/pull/41");
            }

            @Override
            public Optional<PullRequestRef> findByHead(RepoRef repo, String headBranch, String baseBranch) {
                assertEquals(REPO, repo);
                foundFor.add(headBranch + " -> " + baseBranch);
                return existing;
            }
        };
    }

    private static ScmProvider machineAccount() {
        return new ScmProvider(UUID.randomUUID(), "factory", "github", "https://api.github.com",
                "acme", "bearer", null, "TEST-machine-secret", "spire-machine", true, List.of(),
                null, null, ProviderRole.FACTORY);
    }
}
