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

    @Test void aReviewLlmProviderThatDoesNotExistIsBadRequest() {
        UUID missing = UUID.randomUUID();
        given().contentType("application/json").body(repositoryReviewingWith(missing)).post("/api/repositories")
                .then().statusCode(400);
        var repo = repositories.create(repository(null, null));
        given().contentType("application/json").body(repositoryReviewingWith(missing))
                .put("/api/repositories/" + repo.id() + "?revision=1").then().statusCode(400);
        assertNull(repositories.get(repo.id()).orElseThrow().reviewLlmProviderId(), "a refused save changes nothing");
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

    UUID llmProvider(String name) {
        String model = "TEST-repository-model-" + UUID.randomUUID();
        llmModels.create(new LlmModelInput("openai", model, model, "UNMETERED", java.util.Map.of(),
                null, null, null, null, true, java.util.List.of()));
        UUID id = UUID.fromString(llmProviders.create(new LlmProviderInput("TEST-" + name, "openai",
                "https://llm.example.invalid", "TEST-key", model, null, null, true, false)).id());
        createdLlmProviders.add(id);
        return id;
    }

    RepositoryInput repositoryReviewingWith(UUID provider) {
        return new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, null, provider);
    }

    /** Runs before the fixture removes the repository, so the provider is unnamed first; a named one cannot go. */
    @AfterEach void removeLlmProviders() throws Exception {
        execute("UPDATE repository SET review_llm_provider_id=NULL WHERE workspace=?", workspace);
        for (UUID id : createdLlmProviders) execute("DELETE FROM llm_provider WHERE id=?", id);
    }
}
