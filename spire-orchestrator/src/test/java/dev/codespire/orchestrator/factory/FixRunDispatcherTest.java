package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.readmodel.FindingProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether an accepted {@code /fix} becomes a dispatched run, and what that run is told.
 *
 * <p>A plain unit test. Every collaborator is faked, and every method a fake carries that this path
 * does not reach throws — an un-overridden one opens a real {@code DataSource} from a unit test, and
 * this project has hit that ten times.
 *
 * <p><b>The property this file exists for is that a refusal costs nothing.</b> No command on the
 * bus, no row in the table, no rotation slot consumed. Each of those is asserted for every refusal
 * together rather than one at a time — the observe-mode round in this same milestone found three
 * holes precisely because per-branch tests find them one at a time, and the second and third
 * survived the round that fixed the first.
 */
class FixRunDispatcherTest {

    private static final String REVIEW = "review::acme/web#412";
    private static final String THREAD = "thread-aaa";
    private static final String COMMENT = "comment-777";
    private static final RepoRef REPO = new RepoRef("acme", "web");
    private static final UUID CREDENTIAL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    // --- what the fakes are told to answer -------------------------------------------------

    private FixDispatch.Plan plan = new FixDispatch.Planned("run::github:acme/web:" + THREAD + ":1",
            "feature/login", "feature/login", "cafe1234", "develop", "github", "acme", "web");
    private Optional<String> existingClaim = Optional.empty();
    private SpendGate.Decision cap = SpendGate.Decision.of(CapRefusal.allow());
    private boolean rowAccepted = true;
    private RunLaunch.Outcome launchOutcome = new RunLaunch.Dispatched();
    private Optional<ScmProvider> account = Optional.of(machineAccount());
    private HarnessCredentialPool.Selection selection =
            new HarnessCredentialPool.Selection.Chosen(new HarnessCredentialPool.PoolMember(
                    CREDENTIAL_ID, "pool-1", "anthropic", "https://example.invalid",
                    "TEST-harness-key-do-not-print"));
    private String configuredHarness = "codex";
    private String configuredModel = "TEST-model";
    private boolean priceable = true;
    private Optional<FindingProjection.FixSpec> spec = Optional.of(new FindingProjection.FixSpec(
            77L, "src/Foo.java", 44, 48, "HIGH", "correctness",
            "the lock is taken in the opposite order here", "take them in the declared order"));

    // --- what the fakes recorded -----------------------------------------------------------

    private final List<RunCommand.ExecuteRun> launched = new ArrayList<>();
    private final List<FactoryRunProjection.QueuedRun> rows = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private final List<String> claimsChecked = new ArrayList<>();
    private final List<String> plannedFor = new ArrayList<>();

    private FixRunDispatcher dispatcher() {
        FixRunDispatcher dispatcher = new FixRunDispatcher();
        dispatcher.plans = new FixDispatch() {
            @Override
            public Plan plan(String reviewId, String threadRef, RepoRef repo) {
                plannedFor.add(reviewId + "|" + threadRef + "|" + repo.workspace() + "/" + repo.slug());
                return plan;
            }
        };
        dispatcher.runs = new FactoryRunProjection() {
            @Override
            public Optional<String> fixRunFor(String commentId) {
                claimsChecked.add(commentId);
                return existingClaim;
            }

            @Override
            public boolean queued(QueuedRun row) {
                order.add("row");
                rows.add(row);
                return rowAccepted;
            }

            // Neighbours this path must not reach. A dispatch that recorded an outcome here would be
            // writing a state the run itself has not reported yet.
            @Override
            public void dispatchFailed(String runId, String detail) {
                throw new AssertionError("the launch records failures, not the dispatcher");
            }

            @Override
            public void dispatchUncertain(String runId, String detail) {
                throw new AssertionError("the launch records failures, not the dispatcher");
            }
        };
        dispatcher.findings = new FindingProjection() {
            @Override
            public Optional<FixSpec> specFor(String reviewId, long findingId) {
                return spec;
            }

            @Override
            public Optional<TargetFinding> findByThread(String reviewId, String threadRef) {
                throw new AssertionError("the saga resolves the target; the dispatch is handed it");
            }
        };
        dispatcher.machineAccounts = new MachineAccounts() {
            @Override
            public Optional<ScmProvider> resolve(dev.codespire.contract.port.ScmType type, String workspace) {
                return account;
            }
        };
        dispatcher.pool = new HarnessCredentialPool() {
            @Override
            public Selection select() {
                order.add("credential");
                return selection;
            }
        };
        dispatcher.credentials = new RunCredentials() {
            @Override
            public String packScm(String runId, String username, String secret) {
                return "TEST-packed-scm:" + runId;
            }

            @Override
            public String packHarness(String runId, String apiKey) {
                return "TEST-packed-harness:" + runId;
            }
        };
        dispatcher.config = config();
        dispatcher.pricer = new LlmModelPricer() {
            @Override
            public boolean isPriceable(String model) {
                return priceable;
            }
        };
        dispatcher.spendGate = new SpendGate() {
            @Override
            public Decision decide() {
                return cap;
            }
        };
        dispatcher.launch = new RunLaunch() {
            @Override
            public Outcome launch(RunCommand.ExecuteRun command) {
                order.add("launch");
                launched.add(command);
                return launchOutcome;
            }
        };
        return dispatcher;
    }

    private FactoryConfig config() {
        return new FactoryConfig() {
            @Override
            public Map<String, String> agentImage() {
                return Map.of("codex", "spire-agent-codex:1");
            }

            @Override
            public long wallClockSeconds() {
                return 900;
            }

            @Override
            public Fix fix() {
                return new Fix() {
                    @Override
                    public Optional<String> harness() {
                        return Optional.ofNullable(configuredHarness);
                    }

                    @Override
                    public Optional<String> model() {
                        return Optional.ofNullable(configuredModel);
                    }
                };
            }
        };
    }

    private static ScmProvider machineAccount() {
        return new ScmProvider(UUID.randomUUID(), "factory", "github", "https://api.github.com",
                "acme", "bearer", null, "TEST-machine-secret", "spire-machine", true, List.of(),
                null, null);
    }

    private static FindingProjection.TargetFinding finding() {
        return new FindingProjection.TargetFinding(77L, 2, "src/Foo.java", 44, 48, "HIGH", null,
                "review");
    }

    private FixRunDispatcher.Result dispatch() {
        return dispatcher().dispatch(REVIEW, REPO, THREAD, COMMENT, finding());
    }

    // --- the happy path --------------------------------------------------------------------

    /**
     * The command carries ADR-040's existing mode, and base and branch are the same branch.
     *
     * <p>That sameness is the whole point of the mode: the fix is committed onto the branch the
     * review already watches, so the next round reconciles the original finding rather than opening
     * a second pull request nothing joins to it.
     */
    @Test
    void aDispatchedFixPushesToThePullRequestsOwnSourceBranch() {
        assertInstanceOf(FixRunDispatcher.Dispatched.class, dispatch());

        RunCommand.ExecuteRun command = launched.getFirst();
        assertEquals("feature/login", command.baseBranch());
        assertEquals("feature/login", command.branch());
        assertEquals("cafe1234", command.baseCommit());
        assertTrue(command.pushesToAnExistingBranch());
        assertEquals("develop", command.protectedBranch(),
                "the pull request's destination is the floor the publisher refuses");
    }

    /** The run id is the plan's, not a second derivation of one — two would be two encodings. */
    @Test
    void theRunIdIsThePlansAndTheResultNamesIt() {
        FixRunDispatcher.Dispatched dispatched =
                assertInstanceOf(FixRunDispatcher.Dispatched.class, dispatch());

        assertEquals("run::github:acme/web:" + THREAD + ":1", dispatched.runId());
        assertEquals(dispatched.runId(), launched.getFirst().runId());
        assertEquals(dispatched.runId(), rows.getFirst().runId());
    }

    /**
     * <b>The row is written BEFORE the command is launched.</b>
     *
     * <p>A crash between them must leave a row with no command — recoverable, and visible in the runs
     * view — rather than a command with no row. The latter is a paid run that neither cap counts,
     * which is FR-F32 failing open in exactly the direction V54 exists to prevent. It is also the
     * ordering {@code RunLaunch} assumes when it updates a row on failure.
     */
    @Test
    void theRowIsRecordedBeforeTheCommandIsLaunched() {
        dispatch();

        assertEquals(List.of("credential", "row", "launch"), order,
                "and the credential is selected last of the CHECKS, because selecting is a write");
    }

    /** The row names both cap axes and the claim, by value — transposing them is the mutation. */
    @Test
    void theRowNamesTheReviewTheFindingAndTheCommentThatAsked() {
        dispatch();

        FactoryRunProjection.QueuedRun row = rows.getFirst();
        assertEquals("FIX", row.kind());
        assertEquals(REVIEW, row.reviewId());
        assertEquals(THREAD, row.findingRef());
        assertEquals(COMMENT, row.commentId());
        assertEquals(CREDENTIAL_ID, row.harnessCredentialId());
    }

    /**
     * The prompt is the finding, and the run is configured by the deployment.
     *
     * <p>FR-F27's premise is that the finding is a complete task specification; a command carrying a
     * location and no description would be a paid run on a line number.
     */
    @Test
    void theRunIsToldTheFindingAndTheDeploymentsHarness() {
        dispatch();

        RunCommand.ExecuteRun command = launched.getFirst();
        assertTrue(command.prompt().contains("the lock is taken in the opposite order here"),
                command.prompt());
        assertTrue(command.prompt().contains("src/Foo.java:44-48"), command.prompt());
        assertEquals("codex", command.harness());
        assertEquals("TEST-model", command.model());
        assertEquals("spire-agent-codex:1", command.agentImage());
        assertEquals(900, command.maxWallClockSeconds());
    }

    /** Both credentials are packed against THIS run id, which is their AAD — never reused. */
    @Test
    void theCredentialsArePackedAgainstThisRunsOwnId() {
        dispatch();

        String runId = launched.getFirst().runId();
        assertEquals("TEST-packed-scm:" + runId, launched.getFirst().scmCredential());
        assertEquals("TEST-packed-harness:" + runId, launched.getFirst().harnessCredential());
    }

    /** The plan is asked about the thread the finding was found on, in the repo the comment came from. */
    @Test
    void thePlanIsAskedAboutTheRightThreadAndRepository() {
        dispatch();

        assertEquals(List.of(REVIEW + "|" + THREAD + "|acme/web"), plannedFor);
        assertEquals(1, plannedFor.size(), "the plan reads two tables; asking twice reads them twice");
    }

    // --- the refusals ----------------------------------------------------------------------

    /**
     * <b>No refusal costs anything.</b>
     *
     * <p>Driven over every reachable cause at once rather than one test each. A gate that refused
     * but wrote the row anyway would leave a claim held by a run that never ran, and the finding
     * could never be fixed again.
     */
    @Test
    void noRefusalWritesARowOrLaunchesACommand() {
        record Case(String name, Runnable arrange) { }
        List<Case> causes = List.of(
                new Case("already claimed", () -> existingClaim = Optional.of("run::github:acme/web:t:1")),
                new Case("spend cap", () -> cap = SpendGate.Decision.of(
                        CapRefusal.callCapReached(10, 10))),
                new Case("unpushable", () -> plan = new FixDispatch.Refused("that pull request is merged")),
                new Case("no harness configured", () -> configuredHarness = null),
                new Case("no model configured", () -> configuredModel = null),
                new Case("unpriceable model", () -> priceable = false),
                new Case("no machine account", () -> account = Optional.empty()),
                new Case("no finding text", () -> spec = Optional.empty()),
                new Case("empty pool", () -> selection = new HarnessCredentialPool.Selection.Empty()));

        for (Case cause : causes) {
            resetToTheHappyPath();
            cause.arrange().run();

            FixRunDispatcher.Result result = dispatch();

            assertInstanceOf(FixRunDispatcher.Refused.class, result, cause.name());
            assertFalse(((FixRunDispatcher.Refused) result).why().isBlank(),
                    cause.name() + " must say why — the author gets one message");
            assertTrue(rows.isEmpty(), cause.name() + " wrote a row: " + rows);
            assertTrue(launched.isEmpty(), cause.name() + " launched a command: " + launched);
        }
    }

    /**
     * A blank comment id is refused, because without it a redelivery cannot be recognised.
     *
     * <p>Fails closed. The run id is derived from the thread plus an attempt COUNTED from existing
     * rows, so a second delivery derives a different id and passes the {@code ON CONFLICT} guard
     * that catches every other duplicate.
     */
    @Test
    void aCommandWithNoCommentIdIsRefusedRatherThanDispatchedUnclaimed() {
        for (String nothing : new String[] {null, "", "   "}) {
            resetToTheHappyPath();

            FixRunDispatcher.Result result =
                    dispatcher().dispatch(REVIEW, REPO, THREAD, nothing, finding());

            assertInstanceOf(FixRunDispatcher.Refused.class, result, "commentId=" + nothing);
            assertTrue(claimsChecked.isEmpty(), "and it never asks the claim about a blank key");
            assertTrue(rows.isEmpty(), "commentId=" + nothing);
            assertTrue(launched.isEmpty(), "commentId=" + nothing);
        }
    }

    /**
     * The duplicate check comes FIRST, ahead of every other gate.
     *
     * <p>Every gate below it is a reason to refuse a NEW request, and a redelivery is not one. A
     * repeat delivery told about a spend cap would read as a lost request rather than a finished
     * one, and the author would say {@code /fix} again.
     */
    @Test
    void aRedeliveredCommentHearsAboutItsOwnRunRatherThanTheSpendCap() {
        existingClaim = Optional.of("run::github:acme/web:thread-aaa:1");
        cap = SpendGate.Decision.of(CapRefusal.callCapReached(10, 10));
        plan = new FixDispatch.Refused("that pull request is merged");

        FixRunDispatcher.Refused refused =
                assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());

        assertTrue(refused.why().contains("already started fix run"), refused.why());
        assertTrue(refused.why().contains("run::github:acme/web:thread-aaa:1"),
                "and it names the run, so the author can look at it: " + refused.why());
    }

    /** The cap's own words reach the author; re-wording them here would make two sources of truth. */
    @Test
    void theSpendCapsOwnReasonIsPassedThrough() {
        cap = SpendGate.Decision.of(CapRefusal.callCapReached(42, 42));

        FixRunDispatcher.Refused refused =
                assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());
        assertTrue(refused.why().contains("42"), refused.why());
    }

    /** And so do the plan's, which is where every ADR-040 refusal is worded. */
    @Test
    void theplansOwnReasonIsPassedThrough() {
        plan = new FixDispatch.Refused("that pull request comes from a fork");

        FixRunDispatcher.Refused refused =
                assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());
        assertEquals("that pull request comes from a fork", refused.why());
    }

    /**
     * An unconfigured deployment is told WHICH key to set.
     *
     * <p>A generic "not configured" makes an operator read source to find out which of the two is
     * missing, and this message is what a timeline shows them.
     */
    @Test
    void anUnconfiguredDeploymentIsToldWhichSettingIsMissing() {
        configuredHarness = null;
        assertTrue(((FixRunDispatcher.Refused) dispatch()).why().contains("SPIRE_FACTORY_FIX_HARNESS"));

        resetToTheHappyPath();
        configuredModel = null;
        assertTrue(((FixRunDispatcher.Refused) dispatch()).why().contains("SPIRE_FACTORY_FIX_MODEL"));

        resetToTheHappyPath();
        configuredHarness = "a-harness-with-no-image";
        assertTrue(((FixRunDispatcher.Refused) dispatch()).why().contains("no agent image"));
    }

    /**
     * <b>A row the projection refuses is a refusal, not a launch.</b>
     *
     * <p>{@code queued} answers false when {@code ON CONFLICT} matched a row its WHERE declined to
     * touch. Discarding that answer and dispatching anyway is a defect this projection has already
     * had once, and here it would put a second agent on a branch a run is already working on.
     */
    @Test
    void aRowTheProjectionRefusesStopsTheLaunch() {
        rowAccepted = false;

        assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());
        assertTrue(launched.isEmpty(), "nothing may go on the bus once the row was refused");
    }

    /**
     * An unacknowledged launch is reported as unknown, and does NOT invite an immediate retry.
     *
     * <p>The record may already be on the topic, so "ask again" is the expensive instruction here —
     * it is how a second agent ends up on the same branch with the model paid twice. The
     * definite-miss branch says the opposite, because there nothing was published.
     */
    @Test
    void anUnacknowledgedLaunchTellsTheAuthorNotToSimplyAskAgain() {
        launchOutcome = new RunLaunch.Uncertain(new IllegalStateException("no ack"));

        FixRunDispatcher.Refused refused =
                assertInstanceOf(FixRunDispatcher.Refused.class, dispatch());
        assertTrue(refused.why().contains("could start a second one"), refused.why());

        resetToTheHappyPath();
        launchOutcome = new RunLaunch.DefiniteMiss(new IllegalStateException("refused"));
        assertTrue(((FixRunDispatcher.Refused) dispatch()).why().contains("ask again"),
                "a record that never left IS safe to ask for again");
    }

    private void resetToTheHappyPath() {
        plan = new FixDispatch.Planned("run::github:acme/web:" + THREAD + ":1", "feature/login",
                "feature/login", "cafe1234", "develop", "github", "acme", "web");
        existingClaim = Optional.empty();
        cap = SpendGate.Decision.of(CapRefusal.allow());
        rowAccepted = true;
        launchOutcome = new RunLaunch.Dispatched();
        account = Optional.of(machineAccount());
        selection = new HarnessCredentialPool.Selection.Chosen(new HarnessCredentialPool.PoolMember(
                CREDENTIAL_ID, "pool-1", "anthropic", "https://example.invalid",
                "TEST-harness-key-do-not-print"));
        configuredHarness = "codex";
        configuredModel = "TEST-model";
        priceable = true;
        spec = Optional.of(new FindingProjection.FixSpec(77L, "src/Foo.java", 44, 48, "HIGH",
                "correctness", "the lock is taken in the opposite order here",
                "take them in the declared order"));
        launched.clear();
        rows.clear();
        order.clear();
        claimsChecked.clear();
        plannedFor.clear();
    }
}
