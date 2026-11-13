package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.readmodel.FindingProjection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The fakes and the happy path every {@code FixRunDispatcher} unit test starts from, shared so a suite split by
 * subject does not copy them.
 *
 * <p>Every collaborator is faked, and every method a fake carries that this path does not reach throws — an
 * un-overridden one opens a real {@code DataSource} from a unit test, and this project has hit that ten times.
 */
abstract class FixRunDispatcherFixture {

    static final String REVIEW = "review::acme/web#412";
    static final String THREAD = "thread-aaa";
    static final String COMMENT = "comment-777";
    static final RepoRef REPO = new RepoRef("acme", "web");
    static final UUID CREDENTIAL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    // --- what the fakes are told to answer -------------------------------------------------

    FixDispatch.Plan plan = new FixDispatch.Planned("run::github:acme/web:" + THREAD + ":1",
            "feature/login", "feature/login", "cafe1234", "develop", ScmType.GITHUB, "acme", "web");
    Optional<String> existingClaim = Optional.empty();
    SpendGate.Decision cap = SpendGate.Decision.of(CapRefusal.allow());
    boolean rowAccepted = true;
    RunLaunch.Outcome launchOutcome = new RunLaunch.Dispatched();
    Optional<ScmProvider> account = Optional.of(machineAccount());
    HarnessCredentialPool.Selection selection =
            new HarnessCredentialPool.Selection.Chosen(new HarnessCredentialPool.PoolMember(
                    CREDENTIAL_ID, "pool-1", "openai", "https://example.invalid",
                    "TEST-harness-key-do-not-print"));
    String configuredHarness = "codex";
    String configuredModel = "TEST-model";
    boolean priceable = true;
    Optional<FindingProjection.FixSpec> spec = Optional.of(new FindingProjection.FixSpec(
            77L, "src/Foo.java", 44, 48, "HIGH", "correctness",
            "the lock is taken in the opposite order here", "take them in the declared order"));

    // --- what the fakes recorded -----------------------------------------------------------

    final List<RunCommand.ExecuteRun> launched = new ArrayList<>();
    final List<FactoryRunProjection.QueuedRun> rows = new ArrayList<>();
    final List<String> order = new ArrayList<>();
    final List<String> claimsChecked = new ArrayList<>();
    final List<String> plannedFor = new ArrayList<>();
    final List<Optional<String>> keyKindsAsked = new ArrayList<>();

    /** What the run worker last read for the harness; null means nothing was, so the tag is used. */
    String pinned;

    FixRunDispatcher dispatcher() {
        FixRunDispatcher dispatcher = new FixRunDispatcher();
        dispatcher.catalogues = new HarnessCatalogues() {
            // The real one reads the database; this plain unit test answers what the worker last read.
            @Override
            public String imageFor(String harness) {
                return pinned != null ? pinned : "spire-agent-codex:1";
            }
        };
        dispatcher.plans = new FixDispatch() {
            @Override
            public Plan plan(String reviewId, String threadRef, RepoRef repo) {
                plannedFor.add(reviewId + "|" + threadRef + "|" + repo.workspace() + "/" + repo.slug());
                return plan;
            }
        };
        dispatcher.reviews = new dev.codespire.orchestrator.TestRepositoryProjection();
        dispatcher.runs = new FactoryRunProjection() {
            @Override
            protected void push(String runId) {
                // This plain unit-test fake must never reach the live feed or its database read.
                throw new AssertionError("unexpected live broadcast");
            }

            @Override
            public Optional<String> fixRunFor(String reviewId, String commentId) {
                // The review is recorded too: the claim is scoped to it, and a fake that dropped
                // the scope would let the unscoped key pass this suite.
                claimsChecked.add(reviewId + "|" + commentId);
                return existingClaim;
            }

            @Override
            public boolean queued(QueuedRun row, String taskSummary, java.util.UUID repositoryId) {
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
            public Optional<ScmProvider> resolve(java.util.UUID repositoryId) {
                return account;
            }
        };
        dispatcher.pool = pool();
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
            // Overridden as well, because the dispatcher now asks THIS one. Left to the real method it
            // would open a database from a plain unit test — the seventh instance of that trap.
            @Override
            public java.util.List<dev.codespire.contract.review.TokenType> unpricedTypes(String model, String harness) {
                return priceable ? java.util.List.of() : java.util.List.of(dev.codespire.contract.review.TokenType.OUTPUT);
            }
        };
        // The dispatcher asks the catalogue whether the model is still offered. Left unset, the real
        // registry would open a database from a plain unit test — the trap CLAUDE.md names.
        dispatcher.models = new dev.codespire.orchestrator.llm.LlmModelRegistry() {
            @Override public boolean isDisabled(String model) { return false; }
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

    private HarnessCredentialPool pool() {
        return new HarnessCredentialPool() {
            // The only method this path reaches; left to the real one it would open a database.
            @Override
            public Selection select(Optional<String> keyKind) {
                order.add("credential");
                keyKindsAsked.add(keyKind);
                return selection;
            }
        };
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
            public long reviewWallClockSeconds() {
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
        return new ScmProvider(UUID.randomUUID(), "factory", "github", "https://api.github.com", "bearer", null, "TEST-machine-secret", "spire-machine", true, List.of(),
                null, null, ProviderRole.FACTORY);
    }

    static FindingProjection.TargetFinding finding() {
        return new FindingProjection.TargetFinding(77L, 2, "src/Foo.java", 44, 48, "HIGH", null,
                "review");
    }

    FixRunDispatcher.Result dispatch() {
        return dispatcher().dispatch(REVIEW, REPO, THREAD, COMMENT, finding());
    }

    void resetToTheHappyPath() {
        plan = new FixDispatch.Planned("run::github:acme/web:" + THREAD + ":1", "feature/login",
                "feature/login", "cafe1234", "develop", ScmType.GITHUB, "acme", "web");
        existingClaim = Optional.empty();
        cap = SpendGate.Decision.of(CapRefusal.allow());
        rowAccepted = true;
        launchOutcome = new RunLaunch.Dispatched();
        account = Optional.of(machineAccount());
        selection = new HarnessCredentialPool.Selection.Chosen(new HarnessCredentialPool.PoolMember(
                CREDENTIAL_ID, "pool-1", "openai", "https://example.invalid",
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
