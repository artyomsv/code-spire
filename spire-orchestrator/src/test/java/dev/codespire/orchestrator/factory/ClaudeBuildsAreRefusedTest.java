package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.TokenType;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.PayWith;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.LlmModelView;
import dev.codespire.orchestrator.readmodel.FindingProjection;
import dev.codespire.orchestrator.work.WorkPreparationResource;
import dev.codespire.orchestrator.work.WorkTestDoors;
import dev.codespire.worksource.WorkIssueLocation;
import dev.codespire.worksource.WorkIssueRef;
import dev.codespire.worksource.WorkSourceType;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claude Code reviews only: a build setup, a hand preparation, a work item's build or draft run, a fix and a manual
 * dispatch on it are each refused with {@code harness_reviews_only} (harness reviews 3b).
 *
 * <p>A plain unit test. Each door is built by hand, every collaborator behind it fails the test if reached, and both
 * harnesses are configured, so a refusal for a missing image cannot stand in for the guard. Each door's Codex twin is
 * the regression guard: it is not refused for this reason.
 */
class ClaudeBuildsAreRefusedTest {

    private static final String CLAUDE = "claude_code";
    private static final String CODEX = "codex";
    private static final String REVIEWS_ONLY = "harness_reviews_only";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private final List<String> reached = new ArrayList<>();

    private static FactoryConfig configured(String fixHarness, Map<String, String> images) {
        return new FactoryConfig() {
            @Override public Map<String, String> agentImage() { return images; }
            @Override public long wallClockSeconds() { return 1800; }
            @Override public long reviewWallClockSeconds() { return 900; }
            @Override public Fix fix() {
                return new Fix() {
                    @Override public Optional<String> harness() { return Optional.ofNullable(fixHarness); }
                    @Override public Optional<String> model() { return Optional.of("TEST-model"); }
                };
            }
        };
    }

    private static FactoryConfig bothHarnesses() {
        return configured(null, Map.of(CODEX, "TEST-codex-image", CLAUDE, "TEST-claude-image"));
    }

    // --- build defaults ------------------------------------------------------------------------------

    private BuildDefaults buildDefaults() {
        BuildDefaults defaults = new BuildDefaults();
        defaults.config = bothHarnesses();
        defaults.catalogues = new HarnessCatalogues() {
            @Override public Optional<String> refusal(String harness, String model, String effort) {
                reached.add("catalogue");
                return Optional.empty();
            }
        };
        defaults.models = new LlmModelRegistry() {
            @Override public List<LlmModelView> list() {
                reached.add("models");
                return List.of();
            }
        };
        defaults.pricer = new LlmModelPricer() {
            @Override public List<TokenType> unpricedTypes(String model, String harness) {
                throw new AssertionError("refused before the price list");
            }
        };
        defaults.pool = new HarnessCredentialPool() {
            @Override public boolean hasSubscription(String harness) { throw new AssertionError("an API key setup asks for no seat"); }
        };
        return defaults;
    }

    private String buildDefaultsRefusal(String harness) {
        BuildDefaults.Input input = new BuildDefaults.Input(1, "main", harness, "TEST-model", null, PayWith.API_KEY);
        return assertThrows(BuildDefaults.Refused.class,
                () -> buildDefaults().save(UUID.randomUUID(), input, "TEST-operator")).reason();
    }

    @Test
    void buildDefaultsOnClaudeCodeAreRefused() {
        assertEquals(REVIEWS_ONLY, buildDefaultsRefusal(CLAUDE));
        assertEquals(List.of(), reached, "refused before the harness's catalogue and the model list");
    }

    @Test
    void buildDefaultsOnCodexAreNotRefusedForIt() {
        assertEquals("model_unknown", buildDefaultsRefusal(CODEX));
    }

    // --- a hand preparation ---------------------------------------------------------------------------

    private static WorkPreparationResource.Input preparationOn(String harness) {
        return new WorkPreparationResource.Input(1, null, null, "main", COMMIT, harness, "TEST-model");
    }

    @Test
    void itemPreparationOnClaudeCodeIsRefused() {
        WebApplicationException refused = assertThrows(WebApplicationException.class,
                () -> WorkTestDoors.registerAs("TEST-operator", "TEST-item", preparationOn(CLAUDE)));

        assertEquals(400, refused.getResponse().getStatus());
        assertEquals(Map.of("reason", REVIEWS_ONLY), refused.getResponse().getEntity());
    }

    /** Who is asking is checked first: an unidentified caller learns nothing about the harness rules. */
    @Test
    void anUnidentifiedPreparationOnClaudeCodeIsForbiddenFirst() {
        assertThrows(ForbiddenException.class, () -> WorkTestDoors.register("TEST-item", preparationOn(CLAUDE)));
    }

    /**
     * A verified operator, so the identity check passes and the harness guard is what answers: Codex passes it and
     * reaches the item store, which this hand-built resource has none of.
     */
    @Test
    void itemPreparationOnCodexIsNotRefusedForIt() {
        RuntimeException past = assertThrows(RuntimeException.class,
                () -> WorkTestDoors.registerAs("TEST-operator", "TEST-item", preparationOn(CODEX)));

        assertInstanceOf(NullPointerException.class, past, "not the " + REVIEWS_ONLY + " 400: " + past);
    }

    // --- a work item's build and draft runs -----------------------------------------------------------

    private static WorkPreparation.Artifact artifact(String issue) {
        return new WorkPreparation.Artifact(new WorkIssueLocation(
                new WorkIssueRef(WorkSourceType.GITHUB, "https://TEST.invalid", "TEST-project", issue), "TEST-" + issue, null),
                "a".repeat(64));
    }

    /** A prepared item whose approved preparation names the harness: one stored before this change, say. */
    private static WorkItemEvent preparedOn(String harness) {
        WorkPreparation preparation = new WorkPreparation(artifact("1"), artifact("2"), "main", COMMIT, harness,
                "TEST-model", "TEST-operator");
        return new WorkItemEvent("TEST-item", null, null, null, 0L, 0L, null, Map.of(), null, null, null, null, null,
                null, null, null, null, preparation);
    }

    @Test
    void aWorkItemShowsHarnessReviewsOnly() {
        // No connection, source or evidence and no collaborators: a guard after the first of them is not this one.
        IllegalStateException build = assertThrows(IllegalStateException.class,
                () -> new WorkRunAssembly().assemble(null, null, preparedOn(CLAUDE), null));
        assertEquals(REVIEWS_ONLY, WorkTestDoors.reasonOf(build), "the item names the rule, not build_configuration_unavailable");

        BuildDefaults.Defaults setup = new BuildDefaults.Defaults(1, "main", CLAUDE, "TEST-model", null, PayWith.API_KEY,
                List.of(), BuildDefaults.DEFAULT_VERIFY_SECONDS, "TEST-operator", null, BuildDefaults.GENERATED);
        IllegalStateException draft = assertThrows(IllegalStateException.class,
                () -> new WorkDraftAssembly().assemble(null, new WorkDraftAssembly.Request(null, null, setup, "TEST prompt", COMMIT, null)));
        assertEquals(REVIEWS_ONLY, WorkTestDoors.reasonOf(draft));
    }

    @Test
    void aWorkItemOnCodexIsNotRefusedForIt() {
        RuntimeException build = assertThrows(RuntimeException.class,
                () -> new WorkRunAssembly().assemble(null, null, preparedOn(CODEX), null));

        assertNotEquals(REVIEWS_ONLY, build.getMessage());
    }

    // --- a fix -----------------------------------------------------------------------------------------

    private FixRunDispatcher fixDispatcher(String fixHarness, Map<String, String> images) {
        FixRunDispatcher dispatcher = new FixRunDispatcher();
        dispatcher.config = configured(fixHarness, images);
        dispatcher.runs = unwrittenRuns();
        dispatcher.spendGate = new SpendGate() {
            @Override public Decision decide() { return Decision.of(CapRefusal.allow()); }
        };
        dispatcher.plans = new FixDispatch() {
            @Override public Plan plan(String reviewId, String threadRef, RepoRef repo) {
                return new Planned("TEST-run", "TEST-branch", "TEST-branch", "TEST-commit", "main", ScmType.GITHUB,
                        "TEST-ws", "TEST-repo");
            }
        };
        refuseBeyondTheGuard(dispatcher);
        return dispatcher;
    }

    private static FactoryRunProjection unwrittenRuns() {
        return new FactoryRunProjection() {
            @Override protected void push(String runId) { throw new AssertionError("no row is written"); }
            @Override public Optional<String> fixRunFor(String reviewId, String commentId) { return Optional.empty(); }
            @Override public boolean queued(QueuedRun row, String taskSummary, UUID repositoryId) {
                throw new AssertionError("no row is written");
            }
        };
    }

    /** Every collaborator behind the guard fails the test if reached. */
    private static void refuseBeyondTheGuard(FixRunDispatcher dispatcher) {
        dispatcher.models = new LlmModelRegistry() {
            @Override public boolean isDisabled(String model) { throw new AssertionError("refused before the catalogue"); }
        };
        dispatcher.pricer = new LlmModelPricer() {
            @Override public List<TokenType> unpricedTypes(String model, String harness) { throw new AssertionError("refused before the price list"); }
        };
        dispatcher.pool = new HarnessCredentialPool() {
            @Override public Selection select(Optional<String> keyKind) { throw new AssertionError("refused before a key is chosen"); }
        };
        dispatcher.launch = new RunLaunch() {
            @Override public Outcome launch(RunCommand.ExecuteRun command) { throw new AssertionError("nothing is launched"); }
        };
    }

    private static FixRunDispatcher.Result fix(FixRunDispatcher dispatcher) {
        return dispatcher.dispatch("TEST-review", new RepoRef("TEST-ws", "TEST-repo"), "TEST-thread", "TEST-comment",
                new FindingProjection.TargetFinding(1L, 1, "TEST.java", 1, 1, "HIGH", null, "review"));
    }

    @Test
    void aFixOnClaudeCodeIsRefused() {
        FixRunDispatcher.Refused refused = assertInstanceOf(FixRunDispatcher.Refused.class,
                fix(fixDispatcher(CLAUDE, Map.of(CODEX, "TEST-codex-image", CLAUDE, "TEST-claude-image"))));

        assertTrue(refused.why().contains(REVIEWS_ONLY), refused.why());
    }

    /** Past the guard, a Codex fix with no Codex image meets the image check: a different refusal. */
    @Test
    void aFixOnCodexIsNotRefusedForIt() {
        FixRunDispatcher.Refused refused = assertInstanceOf(FixRunDispatcher.Refused.class,
                fix(fixDispatcher(CODEX, Map.of(CLAUDE, "TEST-claude-image"))));

        assertTrue(refused.why().startsWith("no agent image is configured"), refused.why());
    }

    // --- a manual dispatch -----------------------------------------------------------------------------

    private static RunResource.DispatchRequest manualOn(String harness) {
        return new RunResource.DispatchRequest("TEST-ws", "TEST-repo", "github", "main", COMMIT, "TEST prompt", harness,
                "TEST-model", null, null, null);
    }

    /** {@code POST /api/runs} parses the request before it writes a row or chooses a key; the parse refuses it. */
    @Test
    void aManualDispatchOnClaudeCodeIsRefused() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> DispatchRequestParser.parse(manualOn(CLAUDE), bothHarnesses()));

        assertEquals(400, refused.getResponse().getStatus());
        assertTrue(String.valueOf(refused.getResponse().getEntity()).contains(REVIEWS_ONLY),
                String.valueOf(refused.getResponse().getEntity()));
    }

    @Test
    void aManualDispatchOnCodexIsParsed() {
        assertEquals(CODEX, DispatchRequestParser.parse(manualOn(CODEX), bothHarnesses()).harness());
    }
}
