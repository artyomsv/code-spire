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
