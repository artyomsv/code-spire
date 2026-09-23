package dev.codespire.orchestrator.factory;

import dev.codespire.orchestrator.llm.LlmModelInput;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What a repository builds with, saved once instead of typed per work item (M3.5 part B).
 *
 * <p>Every refusal here is one an operator currently meets at dispatch — after a plan was approved and
 * an item waited. The value of saving these coordinates is only real if the save refuses what the
 * dispatch would refuse, so each check is tested against the case that actually reaches it: a harness
 * this deployment has no image for, and a model the catalog has but has switched OFF, which looks
 * exactly like a usable model to anything that only asks whether the name exists.
 */
@QuarkusTest
@TestSecurity(user = "TEST-build-admin", roles = "spire-admin")
class BuildDefaultsTest {

    @Inject BuildDefaults defaults;
    @Inject RepositoryRegistry repositories;
    @Inject ProviderRegistry providers;
    @Inject LlmModelRegistry models;
    @Inject javax.sql.DataSource dataSource;

    UUID repository;
    String model, disabled;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID account = UUID.fromString(providers.create(new ProviderInput("TEST-build-" + suffix, "github",
                "https://github.example.invalid", "bearer", null, "TEST-token", "TEST-bot", true,
                List.of(), "TEST-bot", null, "REVIEWER")).id());
        repository = repositories.create(new RepositoryInput("github", "https://github.example.invalid",
                "TEST-build", "TEST-repo-" + suffix, true, account, null)).id();
        model = "TEST-model-" + suffix;
        disabled = "TEST-disabled-" + suffix;
        // Complete for codex: priced where this fake vendor bills, asserted where it does not. Tests
        // about branches, revisions and harness names must not trip over the pricing rule instead.
        models.create(new LlmModelInput("openai", model, "TEST model", "METERED",
                Map.of("INPUT", 100L, "OUTPUT", 200L), "max_tokens", true, null, Map.of(), true,
                java.util.List.of("CACHED_INPUT", "CACHE_WRITE", "REASONING")));
        models.create(new LlmModelInput("openai", disabled, "TEST disabled model", "METERED",
                Map.of("INPUT", 100L, "OUTPUT", 200L), "max_tokens", true, null, Map.of(), false,
                java.util.List.of("CACHED_INPUT", "CACHE_WRITE", "REASONING")));
    }

    private BuildDefaults.Input input(String branch, String harness, String model, long revision) {
        return new BuildDefaults.Input(revision, branch, harness, model);
    }

    @Inject HarnessCatalogues catalogues;
    @Inject FactoryConfig config;

    /**
     * Tells the cache what the codex image declares, as the run worker would.
     *
     * <p>Two placeholder levels per model and different ones for each, because the rule under test is
     * that a level is checked against THAT model's own list, not against a list shared by all.
     */
    private void codexDeclares(String... slugs) {
        List<dev.codespire.contract.event.HarnessImageResult.Model> declared = new java.util.ArrayList<>();
        for (String slug : slugs) {
            declared.add(new dev.codespire.contract.event.HarnessImageResult.Model(slug, slug, "medium",
                    List.of("medium", slug.equals(model) ? "high" : "low"), true, 1));
        }
        catalogues.record(new dev.codespire.contract.event.HarnessImageResult.Described("TEST-request", "codex",
                config.agentImage().get("codex"), dev.codespire.contract.event.HarnessImageResult.Status.OK, declared));
    }

    @org.junit.jupiter.api.AfterEach
    void forgetTheCatalogue() throws java.sql.SQLException {
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("DELETE FROM harness_catalogue");
        }
    }

    /**
     * The defect the operator found: codex was offered Claude and Gemini models, and a save accepted one.
     * Once the image says what it runs, a model outside that list is refused HERE — not when the run
     * starts, after somebody approved it.
     */
    @Test
    void aModelTheHarnessCannotRunIsRefusedOnceItsListIsKnown() {
        codexDeclares("TEST-some-other-model");

        assertEquals("model_not_run_by_harness", refusal(input("main", "codex", model, 0)));
    }

    @Test
    void aModelTheHarnessCanRunIsAccepted() {
        codexDeclares(model);

        assertEquals(model, defaults.save(repository, input("main", "codex", model, 0), "TEST-operator").model());
    }

    /**
     * When the list is NOT known, the save is not blocked.
     *
     * <p>Deliberate. A development stack with no run worker never hears the answer, and refusing every
     * save would lock the operator out of the build setup over a background reply that has not come. The
     * wrong-model case this lets through is the one that existed before, and the screen says so.
     */
    @Test
    void anUnknownListDoesNotLockTheBuildSetup() {
        assertEquals(model, defaults.save(repository, input("main", "codex", model, 0), "TEST-operator").model());
    }

    /** A thinking level is the model's OWN list — this model allows "high", the other only "low". */
    @Test
    void aThinkingLevelIsCheckedAgainstThatModelsOwnList() {
        codexDeclares(model, "TEST-other");

        var saved = defaults.save(repository, new BuildDefaults.Input(0, "main", "codex", model, "high"), "TEST-operator");
        assertEquals("high", saved.effort());
        assertEquals("effort_not_offered",
                refusal(new BuildDefaults.Input(1, "main", "codex", model, "low")),
                "another model's level is not this model's");
    }

    /** With nothing to check it against, a level would reach the vendor unverified, so it is refused. */
    @Test
    void aThinkingLevelIsRefusedWhenTheListIsUnknown() {
        assertEquals("effort_unverifiable", refusal(new BuildDefaults.Input(0, "main", "codex", model, "high")));
    }

    /** No level chosen means the model's own default — a real choice, stored as such. */
    @Test
    void noThinkingLevelMeansTheModelsOwnDefault() {
        codexDeclares(model);

        assertNull(defaults.save(repository, input("main", "codex", model, 0), "TEST-operator").effort());
    }

    private String refusal(BuildDefaults.Input input) {
        return assertThrows(BuildDefaults.Refused.class, () -> defaults.save(repository, input, "TEST-operator")).reason();
    }

    @Test
    void aRepositoryWithoutDefaultsAnswersUnsetRatherThanAGuess() {
        BuildDefaults.Defaults none = defaults.get(repository);
        assertFalse(none.set());
        assertEquals(0, none.revision());
        assertNull(none.harness());
        assertNull(none.baseBranch());
    }

    @Test
    void savingTrimsTheCoordinatesAndAdvancesTheRevision() {
        BuildDefaults.Defaults saved = defaults.save(repository, input("  main  ", "codex", model, 0), "TEST-operator");
        assertEquals(1, saved.revision());
        assertEquals("main", saved.baseBranch());
        assertEquals("codex", saved.harness());
        assertEquals(model, saved.model());
        assertEquals("TEST-operator", saved.updatedBy());
        assertEquals(2, defaults.save(repository, input("develop", "codex", model, 1), "TEST-operator").revision());
        assertEquals("develop", defaults.get(repository).baseBranch());
    }

    @Test
    void aSaveAgainstAnOldRevisionIsRefusedRatherThanOverwritingTheOtherOperator() {
        defaults.save(repository, input("main", "codex", model, 0), "TEST-operator");
        assertEquals("build_defaults_changed", refusal(input("develop", "codex", model, 0)));
        assertEquals("main", defaults.get(repository).baseBranch());
    }

    @Test
    void aHarnessWithNoAgentImageIsRefusedWhereItCanBeFixed() {
        assertEquals("harness_unconfigured", refusal(input("main", "TEST-no-such-harness", model, 0)));
    }

    @Test
    void aModelTheCatalogDoesNotOfferIsRefused() {
        assertEquals("model_unknown", refusal(input("main", "codex", "TEST-model-nobody-registered", 0)));
    }

    /** The discriminating case: the name exists, so only the enabled check can refuse it. */
    @Test
    void aModelThatExistsButIsSwitchedOffIsRefused() {
        assertEquals("model_unknown", refusal(input("main", "codex", disabled, 0)));
    }

    @Test
    void aBlankBaseBranchIsRefused() {
        assertEquals("base_branch_blank", refusal(input("   ", "codex", model, 0)));
    }

    @Test
    void anUnregisteredRepositoryCannotHaveDefaults() {
        UUID unknown = UUID.randomUUID();
        assertEquals("repository_unknown", assertThrows(BuildDefaults.Refused.class,
                () -> defaults.save(unknown, input("main", "codex", model, 0), "TEST-operator")).reason());
    }

    @Test
    void anOperatorIdentityIsRequiredToSave() {
        assertEquals("operator_identity_required", assertThrows(BuildDefaults.Refused.class,
                () -> defaults.save(repository, input("main", "codex", model, 0), " ")).reason());
    }

    /** The screen reads and writes over HTTP, and each refusal has to arrive as its own status. */
    @Test
    void theEndpointAnswersWithTheRuleThatRefused() {
        given().get("/api/repositories/" + repository + "/factory/build").then().statusCode(200).body("revision", is(0));
        given().get("/api/repositories/" + repository + "/factory/build/options").then().statusCode(200)
                .body("harnesses", org.hamcrest.Matchers.hasItem("codex"));
        given().contentType(ContentType.JSON)
                .body(Map.of("expectedRevision", 0, "baseBranch", "main", "harness", "codex", "model", model))
                .put("/api/repositories/" + repository + "/factory/build").then().statusCode(200)
                .body("revision", is(1), "harness", is("codex"));
        given().contentType(ContentType.JSON)
                .body(Map.of("expectedRevision", 0, "baseBranch", "main", "harness", "codex", "model", model))
                .put("/api/repositories/" + repository + "/factory/build").then().statusCode(409)
                .body("reason", is("build_defaults_changed"));
        given().contentType(ContentType.JSON)
                .body(Map.of("expectedRevision", 1, "baseBranch", "main", "harness", "TEST-nothing", "model", model))
                .put("/api/repositories/" + repository + "/factory/build").then().statusCode(400)
                .body("reason", is("harness_unconfigured"));
        given().contentType(ContentType.JSON)
                .body(Map.of("expectedRevision", 0, "baseBranch", "main", "harness", "codex", "model", model))
                .put("/api/repositories/" + UUID.randomUUID() + "/factory/build").then().statusCode(404)
                .body("reason", is("repository_unknown"));
    }

    @Test
    void aBranchHeadNeedsABranchAndAnExistingRepository() {
        given().get("/api/repositories/" + repository + "/factory/branch-head").then().statusCode(400);
        given().get("/api/repositories/" + UUID.randomUUID() + "/factory/branch-head?branch=main").then().statusCode(404);
    }

    /**
     * The discriminating case for the account guard: an existing repository, a real branch name, and
     * no account bound at all. The two cases above reach the branch and repository checks instead, so
     * they pass with the account guard deleted.
     */
    @Test
    void aRepositoryWithNoAccountSaysSoInsteadOfFailing() {
        UUID bare = repositories.create(new RepositoryInput("github", "https://github.example.invalid",
                "TEST-build", "TEST-bare-" + UUID.randomUUID().toString().substring(0, 8), true, null, null)).id();
        given().get("/api/repositories/" + bare + "/factory/branch-head?branch=main").then().statusCode(409)
                .body("reason", is("repository_account_missing"));
    }

    /** A branch a build would refuse cannot be stored as the branch every build starts from. */
    @Test
    void aBranchNameADispatchWouldRefuseIsRefusedHere() {
        assertEquals("base_branch_invalid", refusal(input("feature..broken", "codex", model, 0)));
        assertEquals("base_branch_invalid", refusal(input("-main", "codex", model, 0)));
        assertEquals("base_branch_invalid", refusal(input("feature branch", "codex", model, 0)));
    }

    /**
     * The save asks exactly what the dispatch asks: every token type this harness can report needs a
     * price or an explicit not-billed mark. The fixture model is priced for INPUT and OUTPUT only, and
     * codex reports three more — so the refusal names those three.
     */
    @Test
    void aModelThatCannotPriceWhatTheHarnessReportsIsRefused() {
        String partial = "TEST-partial-" + UUID.randomUUID().toString().substring(0, 8);
        models.create(new LlmModelInput("openai", partial, "TEST partial model", "METERED",
                Map.of("INPUT", 100L, "OUTPUT", 200L), "MAX_TOKENS", true, null, Map.of(), true, java.util.List.of()));
        assertEquals("model_pricing_incomplete:CACHED_INPUT,CACHE_WRITE,REASONING",
                refusal(input("main", "codex", partial, 0)));
    }

    /**
     * And the legacy shape: a catalogue row from before the rule, whose OUTPUT rate was removed by hand.
     * Creating the model through the catalogue cannot produce this state.
     */
    @Test
    void aModelWhoseOutputRateIsMissingIsRefused() throws Exception {
        String unpriced = "TEST-unpriced-" + UUID.randomUUID().toString().substring(0, 8);
        String id = models.create(new LlmModelInput("openai", unpriced, "TEST unpriced model", "METERED",
                Map.of("INPUT", 100L, "OUTPUT", 200L), "MAX_TOKENS", true, null, Map.of(), true, java.util.List.of())).id();
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("DELETE FROM llm_model_rate WHERE model_id=? AND token_type='OUTPUT'")) {
            ps.setObject(1, UUID.fromString(id));
            assertEquals(1, ps.executeUpdate());
        }
        assertTrue(refusal(input("main", "codex", unpriced, 0)).startsWith("model_pricing_incomplete:"));
    }

    /**
     * Two operators saving at once. Without a transaction the two FOR UPDATE locks release immediately,
     * both reads see the same revision and the second write silently overwrites the first — the loss the
     * expected revision exists to prevent. Exactly one save must win, and the row must advance by one.
     */
    @Test
    void twoSavesAtOnceLeaveOneWinnerAndOneRefusal() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> attempt = () -> {
                start.await(5, TimeUnit.SECONDS);
                try { return "saved:" + defaults.save(repository, input("main", "codex", model, 0), "TEST-operator").revision(); }
                catch (BuildDefaults.Refused refused) { return refused.reason(); }
            };
            Future<String> first = pool.submit(attempt), second = pool.submit(attempt);
            start.countDown();
            List<String> answers = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, answers.stream().filter(answer -> answer.equals("saved:1")).count(), "answers: " + answers);
            assertEquals(1, answers.stream().filter(answer -> answer.equals("build_defaults_changed")).count(), "answers: " + answers);
            assertEquals(1, defaults.get(repository).revision());
        } finally { pool.shutdownNow(); }
    }
}
