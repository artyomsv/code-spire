package dev.codespire.orchestrator.repository;

import dev.codespire.orchestrator.llm.LlmModelInput;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** A repository's review model beside its provider: both or neither, of the provider's vendor on a new choice. */
@QuarkusTest
@TestSecurity(user = "TEST-admin", roles = {"spire-viewer", "spire-admin"})
class RepositoryReviewModelTest extends RepositoryReviewWithFixture {

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
}
