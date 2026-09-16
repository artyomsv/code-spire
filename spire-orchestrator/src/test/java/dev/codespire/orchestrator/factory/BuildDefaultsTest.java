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
        models.create(new LlmModelInput("openai", model, "TEST model", "METERED",
                Map.of("INPUT", 100L, "OUTPUT", 200L), "max_tokens", true, null, Map.of(), true));
        models.create(new LlmModelInput("openai", disabled, "TEST disabled model", "METERED",
                Map.of("INPUT", 100L, "OUTPUT", 200L), "max_tokens", true, null, Map.of(), false));
    }

    private BuildDefaults.Input input(String branch, String harness, String model, long revision) {
        return new BuildDefaults.Input(revision, branch, harness, model);
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

    /** A repository with no bound account cannot read a head, and says so instead of failing as a 500. */
    @Test
    void aBranchHeadNeedsAnAccountAndABranch() {
        given().get("/api/repositories/" + repository + "/factory/branch-head").then().statusCode(400);
        given().get("/api/repositories/" + UUID.randomUUID() + "/factory/branch-head?branch=main").then().statusCode(404);
    }
}
