package dev.codespire.orchestrator.repository;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import dev.codespire.orchestrator.llm.LlmModelInput;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.LlmProviderInput;
import dev.codespire.orchestrator.llm.LlmProviderRegistry;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "TEST-admin", roles = {"spire-viewer", "spire-admin"})
class RepositoryResourceTest extends RepositoryFixture {
    @Test void repositoryOwnsWorkspaceAndRoleBindings() {
        UUID reviewer = account("REVIEWER"), factory = account("FACTORY");
        String id = given().contentType("application/json").body(repository(reviewer, factory)).post("/api/repositories")
                .then().statusCode(201).body("workspace", equalTo(workspace))
                .body("reviewer.id", equalTo(reviewer.toString())).body("factory.id", equalTo(factory.toString()))
                .body(not(containsString("TEST-secret"))).extract().path("id");
        given().get("/api/repositories/" + id).then().statusCode(200)
                .body("reviewer.id", equalTo(reviewer.toString())).body("factory.id", equalTo(factory.toString()));
        assertEquals(1, repositories.list().stream().filter(r -> r.id().toString().equals(id)).count(),
                "Each role join must select exactly the repository's binding");
    }
    @Test void registersARepositoryWithExplicitRoleBindings() {
        UUID reviewer = account("REVIEWER"), factory = account("FACTORY");
        String id = given().contentType("application/json").body(repository(reviewer, factory)).post("/api/repositories")
                .then().statusCode(201).body("workspace", equalTo(workspace))
                .body("reviewer.id", equalTo(reviewer.toString())).body("factory.id", equalTo(factory.toString()))
                .body(not(containsString("TEST-secret"))).extract().path("id");
        given().get("/api/repositories/" + id).then().statusCode(200)
                .body("forgeOrigin", equalTo(origin)).body("factory.handle", equalTo("TEST-login-FACTORY"));
    }

    @Test void staleUpdateCannotOverwriteBindings() {
        UUID reviewer = account("REVIEWER");
        var repo = repositories.create(repository(reviewer, null));
        given().contentType("application/json").body(repository(null, null)).put("/api/repositories/" + repo.id() + "?revision=1").then().statusCode(200);
        given().contentType("application/json").body(repository(reviewer, null)).put("/api/repositories/" + repo.id() + "?revision=1")
                .then().statusCode(409).body(containsString("reload"));
        assertNull(repositories.get(repo.id()).orElseThrow().reviewer());
    }

    /** ADR-047: on by default, stored through update, read back by the saga; a missing row is empty. */
    @Test void reviewerMayApproveDefaultsOnAndRoundTripsThroughUpdate() {
        String id = given().contentType("application/json").body(repository(null, null)).post("/api/repositories")
                .then().statusCode(201).body("reviewerMayApprove", equalTo(true)).extract().path("id");
        given().contentType("application/json")
                .body(new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, false))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200).body("reviewerMayApprove", equalTo(false));
        given().get("/api/repositories/" + id).then().statusCode(200).body("reviewerMayApprove", equalTo(false));
        assertEquals(java.util.Optional.of(false), repositories.reviewerMayApprove(UUID.fromString(id)));
        assertTrue(repositories.reviewerMayApprove(UUID.randomUUID()).isEmpty(), "a missing row is empty, never a default");
    }

    /** V115: BLOCKER by default, stored through create and update, read back by the saga; a missing row is empty. */
    @Test void blockApprovalAtDefaultsToBlockerAndRoundTripsThroughCreateAndUpdate() {
        String id = given().contentType("application/json").body(repository(null, null)).post("/api/repositories")
                .then().statusCode(201).body("blockApprovalAt", equalTo("BLOCKER")).extract().path("id");
        given().contentType("application/json").body(blockingAt("major"))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200).body("blockApprovalAt", equalTo("MAJOR"));
        given().get("/api/repositories/" + id).then().statusCode(200).body("blockApprovalAt", equalTo("MAJOR"));
        assertEquals(java.util.Optional.of(BlockApprovalAt.MAJOR), repositories.blockApprovalAt(UUID.fromString(id)));
        given().contentType("application/json").body(blockingAt("blocker"))
                .put("/api/repositories/" + id + "?revision=2").then().statusCode(200).body("blockApprovalAt", equalTo("BLOCKER"));
        assertTrue(repositories.blockApprovalAt(UUID.randomUUID()).isEmpty(), "a missing row is empty, never a default");
    }

    /** An update that omits the level (an older client) keeps the stored one; only a create defaults it to BLOCKER. */
    @Test void anUpdateThatOmitsBlockApprovalAtKeepsTheStoredLevel() {
        String id = given().contentType("application/json").body(blockingAt("MAJOR")).post("/api/repositories")
                .then().statusCode(201).body("blockApprovalAt", equalTo("MAJOR")).extract().path("id");
        given().contentType("application/json").body(blockingAt(null))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200).body("blockApprovalAt", equalTo("MAJOR"));
        assertEquals(java.util.Optional.of(BlockApprovalAt.MAJOR), repositories.blockApprovalAt(UUID.fromString(id)));
    }

    @Test void blockApprovalAtMinorIsStoredOnCreate() {
        given().contentType("application/json").body(blockingAt("MINOR")).post("/api/repositories")
                .then().statusCode(201).body("blockApprovalAt", equalTo("MINOR"));
    }

    /** INFO and NIT never block, so they are not levels: refused with the reason, before V115's CHECK. */
    @Test void anUnknownBlockApprovalAtIsRefusedWithItsReason() {
        given().contentType("application/json").body(blockingAt("NIT")).post("/api/repositories")
                .then().statusCode(400).body(containsString("Block approval at"));
        var repo = repositories.create(repository(null, null));
        given().contentType("application/json").body(blockingAt("TEST-LEVEL"))
                .put("/api/repositories/" + repo.id() + "?revision=1").then().statusCode(400)
                .body(containsString("Block approval at"));
        assertEquals(BlockApprovalAt.BLOCKER, repositories.get(repo.id()).orElseThrow().blockApprovalAt(),
                "a refused save changes nothing");
    }

    /** normalize rebuilds the input component by component; the level must survive it, in its stored form. */
    @Test void normalizeKeepsTheBlockApprovalAt() {
        assertEquals("MINOR", repositories.normalize(blockingAt(" minor ")).blockApprovalAt());
        assertEquals("BLOCKER", repositories.normalize(blockingAt(null)).blockApprovalAt());
    }

    RepositoryInput blockingAt(String level) {
        return new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, null, null, null, level);
    }

    /** Harness reviews, decision 1: the provider a repository reviews with is stored and read back; null is the default. */
    @Test void reviewLlmProviderRoundTripsThroughCreateAndUpdate() {
        UUID first = llmProvider("first"), second = llmProvider("second");
        String id = given().contentType("application/json").body(repositoryReviewingWith(first)).post("/api/repositories")
                .then().statusCode(201).body("reviewLlmProviderId", equalTo(first.toString())).extract().path("id");
        given().contentType("application/json").body(repositoryReviewingWith(second))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200)
                .body("reviewLlmProviderId", equalTo(second.toString()));
        given().get("/api/repositories/" + id).then().statusCode(200).body("reviewLlmProviderId", equalTo(second.toString()));
        given().contentType("application/json").body(repositoryReviewingWith(null))
                .put("/api/repositories/" + id + "?revision=2").then().statusCode(200)
                .body("reviewLlmProviderId", nullValue());
    }

    /** The model is saved and read back beside its provider. */
    @Test void aProviderAndModelSaveAndRead() {
        UUID provider = llmProvider("model");
        String id = given().contentType("application/json").body(repositoryReviewingWith(provider)).post("/api/repositories")
                .then().statusCode(201).body("reviewLlmModel", equalTo(modelOf.get(provider))).extract().path("id");
        given().get("/api/repositories/" + id).then().statusCode(200)
                .body("reviewLlmProviderId", equalTo(provider.toString()))
                .body("reviewLlmModel", equalTo(modelOf.get(provider)));
    }

    /** Neither provider nor model is the global default; both columns clear together. */
    @Test void clearingBothFallsBackToTheDefault() {
        UUID provider = llmProvider("clear");
        String id = given().contentType("application/json").body(repositoryReviewingWith(provider)).post("/api/repositories")
                .then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(repositoryReviewingWith(null))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200)
                .body("reviewLlmProviderId", nullValue())
                .body("reviewLlmModel", nullValue());
    }

    /**
     * An older dashboard sends a provider with no model. Refused with the pairing reason, never a silent clear; the
     * reason is asserted because the vendor check would also refuse a null model, as unknown.
     */
    @Test void aProviderWithoutAModelIs400() {
        UUID provider = llmProvider("no-model");
        RepositoryInput withoutModel = repositoryReviewingWith(provider).withReviewLlm(provider, null);
        given().contentType("application/json").body(withoutModel).post("/api/repositories")
                .then().statusCode(400).body(containsString("together"));
    }

    /** Known, priced and enabled, but an anthropic model for an openai provider: only the vendor check refuses it. */
    @Test void aModelOfAnotherVendorIs400() {
        UUID provider = llmProvider("vendor");
        String anthropic = "TEST-repository-anthropic-" + UUID.randomUUID();
        llmModels.create(new LlmModelInput("anthropic", anthropic, anthropic, "UNMETERED", java.util.Map.of(),
                null, null, null, null, true, java.util.List.of()));
        RepositoryInput mismatched = repositoryReviewingWith(provider).withReviewLlm(provider, anthropic);
        given().contentType("application/json").body(mismatched).post("/api/repositories")
                .then().statusCode(400).body(containsString("model_not_for_provider"));
    }

    /**
     * A pair saved before the vendor rule (a gateway pair V118 copied) is not a new choice: saving another setting
     * sends it back unchanged and must succeed. Choosing another model of the wrong vendor is still refused.
     */
    @Test void aStoredCrossVendorPairStillSavesAndANewOneIsRefused() throws Exception {
        UUID provider = llmProvider("gateway");
        String anthropic = anthropicModel("gateway");
        String otherAnthropic = anthropicModel("gateway-other");
        RepositoryView repo = repositories.create(repository(null, null));
        execute("UPDATE repository SET review_llm_provider_id=?, review_llm_model=? WHERE id=?", provider, anthropic, repo.id());

        RepositoryInput onlyEnabledChanged = new RepositoryInput("gitlab", origin, workspace, "TEST-repo", false, null, null,
                null, provider).withReviewLlm(provider, anthropic);
        given().contentType("application/json").body(onlyEnabledChanged)
                .put("/api/repositories/" + repo.id() + "?revision=1").then().statusCode(200)
                .body("enabled", equalTo(false)).body("reviewLlmModel", equalTo(anthropic));

        RepositoryInput newCrossVendor = onlyEnabledChanged.withReviewLlm(provider, otherAnthropic);
        given().contentType("application/json").body(newCrossVendor)
                .put("/api/repositories/" + repo.id() + "?revision=2").then().statusCode(400)
                .body(containsString("model_not_for_provider"));
    }

    String anthropicModel(String name) {
        String model = "TEST-repository-anthropic-" + name + "-" + UUID.randomUUID();
        llmModels.create(new LlmModelInput("anthropic", model, model, "UNMETERED", java.util.Map.of(),
                null, null, null, null, true, java.util.List.of()));
        return model;
    }

    /** A harness review takes its model from the harness; an API model beside it is refused before the write. */
    @Test void aHarnessWithAModelIs400() {
        RepositoryInput harnessWithModel = repositoryReviewingOn(new ReviewHarness("codex", "TEST-model", null, "API_KEY"))
                .withReviewLlm(null, "TEST-repository-stray-model");
        given().contentType("application/json").body(harnessWithModel).post("/api/repositories")
                .then().statusCode(400).body(containsString("harness"));
    }

    /** normalize rebuilds the input component by component; the model must survive it, stripped. */
    @Test void normalizeKeepsTheModel() {
        UUID provider = UUID.randomUUID();
        RepositoryInput normalized = repositories.normalize(repositoryReviewingWith(null).withReviewLlm(provider, " TEST-m "));
        assertEquals(provider, normalized.reviewLlmProviderId());
        assertEquals("TEST-m", normalized.reviewLlmModel());
    }

    @Test void aReviewLlmProviderThatDoesNotExistIsBadRequest() {
        UUID missing = UUID.randomUUID();
        given().contentType("application/json").body(repositoryReviewingWith(missing)).post("/api/repositories")
                .then().statusCode(400);
        var repo = repositories.create(repository(null, null));
        given().contentType("application/json").body(repositoryReviewingWith(missing))
                .put("/api/repositories/" + repo.id() + "?revision=1").then().statusCode(400);
        assertNull(repositories.get(repo.id()).orElseThrow().reviewLlmProviderId(), "a refused save changes nothing");
    }

    /** A refusal says why in the body: an empty 400 left the dashboard only "Could not save repository". */
    @Test void aRefusedSaveNamesItsReasonInTheBody() {
        ReviewHarness claudeSeat = new ReviewHarness("claude_code", "TEST-model", null, "SUBSCRIPTION");
        given().contentType("application/json").body(repositoryReviewingOn(claudeSeat)).post("/api/repositories")
                .then().statusCode(400).body(containsString("subscription_not_allowed"));
        var repo = repositories.create(repository(null, null));
        given().contentType("application/json").body(repositoryReviewingOn(claudeSeat))
                .put("/api/repositories/" + repo.id() + "?revision=1").then().statusCode(400)
                .body(containsString("subscription_not_allowed"));
    }

    /** Harness reviews, step 5: the harness half of "Review with" is stored, read back and readable by the saga. */
    @Test void aReviewHarnessRoundTripsThroughCreateAndUpdate() {
        ReviewHarness seat = new ReviewHarness("codex", "TEST-model", "high", "SUBSCRIPTION");
        String id = given().contentType("application/json").body(repositoryReviewingOn(seat)).post("/api/repositories")
                .then().statusCode(201).body("reviewLlmProviderId", nullValue())
                .body("reviewHarness.harness", equalTo("codex")).body("reviewHarness.model", equalTo("TEST-model"))
                .body("reviewHarness.effort", equalTo("high")).body("reviewHarness.payWith", equalTo("SUBSCRIPTION"))
                .extract().path("id");
        ReviewHarness key = new ReviewHarness("codex", "TEST-other-model", null, "API_KEY");
        given().contentType("application/json").body(repositoryReviewingOn(key))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200)
                .body("reviewHarness.model", equalTo("TEST-other-model")).body("reviewHarness.payWith", equalTo("API_KEY"));
        assertEquals(java.util.Optional.of(key), repositories.reviewHarness(UUID.fromString(id)));
        assertEquals(key, repositories.get(UUID.fromString(id)).orElseThrow().reviewHarness());
    }

    /** Back to the API: the view's harness is null and all four columns are cleared, so V109's CHECK holds. */
    @Test void switchingBackToTheApiClearsTheHarness() throws Exception {
        ReviewHarness seat = new ReviewHarness("codex", "TEST-model", "high", "SUBSCRIPTION");
        String id = given().contentType("application/json").body(repositoryReviewingOn(seat)).post("/api/repositories")
                .then().statusCode(201).body("reviewHarness.harness", equalTo("codex")).extract().path("id");
        given().contentType("application/json").body(repositoryReviewingOn(null))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200)
                .body("reviewHarness", nullValue());
        assertTrue(repositories.reviewHarness(UUID.fromString(id)).isEmpty());
        assertEquals("null|null|null|null", harnessColumns(UUID.fromString(id)));
    }

    /** One "Review with" choice: an API provider or a harness, never both. */
    @Test void aProviderAndAHarnessTogetherAreRefused() {
        UUID provider = llmProvider("both");
        RepositoryInput both = new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, null,
                provider, new ReviewHarness("codex", "TEST-model", null, "API_KEY"));
        given().contentType("application/json").body(both).post("/api/repositories")
                .then().statusCode(400);
        RepositoryView repo = repositories.create(repository(null, null));
        given().contentType("application/json").body(both)
                .put("/api/repositories/" + repo.id() + "?revision=1").then().statusCode(400);
        assertNull(repositories.get(repo.id()).orElseThrow().reviewHarness(), "a refused save changes nothing");
    }

    /** Validated against the deployment's agent images, the way a build setup's harness is. */
    @Test void anUnknownHarnessIsRefused() {
        RepositoryInput unknown = repositoryReviewingOn(new ReviewHarness("TEST-no-such-harness", "TEST-model", null, "API_KEY"));
        given().contentType("application/json").body(unknown).post("/api/repositories")
                .then().statusCode(400);
    }

    /** A harness review needs a model; refused before the write, so it is the caller's mistake and not V109's CHECK. */
    @Test void aHarnessWithoutAModelIsRefused() {
        RepositoryInput noModel = repositoryReviewingOn(new ReviewHarness("codex", " ", null, "API_KEY"));
        given().contentType("application/json").body(noModel).post("/api/repositories")
                .then().statusCode(400);
    }

    /** A payment other than an API key or a seat is refused before the write, not by V109's CHECK. */
    @Test void anUnknownHarnessPaymentIsRefused() {
        RepositoryInput unknown = repositoryReviewingOn(new ReviewHarness("codex", "TEST-model", null, "TEST-CREDIT-CARD"));
        given().contentType("application/json").body(unknown).post("/api/repositories")
                .then().statusCode(400);
    }

    /** normalize rebuilds the input component by component; the harness must survive it. */
    @Test void normalizeKeepsTheHarness() {
        ReviewHarness seat = new ReviewHarness("codex", "TEST-model", "high", "SUBSCRIPTION");
        assertEquals(seat, repositories.normalize(repositoryReviewingOn(seat)).reviewHarness());
    }

    RepositoryInput repositoryReviewingOn(ReviewHarness harness) {
        return new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, null, null, harness);
    }

    String harnessColumns(UUID id) throws Exception {
        try (java.sql.Connection c = dataSource.getConnection(); java.sql.PreparedStatement ps = c.prepareStatement("""
                SELECT review_harness, review_harness_model, review_harness_effort, review_harness_pay_with
                FROM repository WHERE id=?""")) {
            ps.setObject(1, id);
            try (java.sql.ResultSet rows = ps.executeQuery()) {
                assertTrue(rows.next(), "the repository row exists");
                return rows.getString(1) + "|" + rows.getString(2) + "|" + rows.getString(3) + "|" + rows.getString(4);
            }
        }
    }

    @Test void duplicateCoordinatesCannotCreateSecondRepository() {
        repositories.create(repository(null, null));
        given().contentType("application/json").body(repository(null, null)).post("/api/repositories").then().statusCode(409);
    }

    @Test void missingKindIsBadRequest() {
        given().contentType("application/json").body("{}").post("/api/repositories").then().statusCode(400);
    }

    @Test @TestSecurity(user = "TEST-viewer", roles = "spire-viewer")
    void viewerCannotRegisterRepository() {
        given().contentType("application/json").body(repository(null, null)).post("/api/repositories").then().statusCode(403);
    }

    @Inject LlmProviderRegistry llmProviders;
    @Inject LlmModelRegistry llmModels;
    final java.util.Set<UUID> createdLlmProviders = new java.util.HashSet<>();

    final java.util.Map<UUID, String> modelOf = new java.util.HashMap<>();

    /** An openai provider and a priced openai model chosen with it. */
    UUID llmProvider(String name) {
        String model = "TEST-repository-model-" + UUID.randomUUID();
        llmModels.create(new LlmModelInput("openai", model, model, "UNMETERED", java.util.Map.of(),
                null, null, null, null, true, java.util.List.of()));
        UUID id = UUID.fromString(llmProviders.create(new LlmProviderInput("TEST-" + name, "openai",
                "https://llm.example.invalid", "TEST-key", null, null, true, false, null)).id());
        createdLlmProviders.add(id);
        modelOf.put(id, model);
        return id;
    }

    /** The provider with its own model, or neither (the global default); an unknown provider has no model. */
    RepositoryInput repositoryReviewingWith(UUID provider) {
        return new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, null, provider)
                .withReviewLlm(provider, provider == null ? null : modelOf.getOrDefault(provider, "TEST-repository-unknown"));
    }

    /** Runs before the fixture removes the repository, so the provider is unnamed first; a named one cannot go. */
    @AfterEach void removeLlmProviders() throws Exception {
        execute("UPDATE repository SET review_llm_provider_id=NULL, review_llm_model=NULL WHERE workspace=?", workspace);
        for (UUID id : createdLlmProviders) execute("DELETE FROM llm_provider WHERE id=?", id);
    }
}
