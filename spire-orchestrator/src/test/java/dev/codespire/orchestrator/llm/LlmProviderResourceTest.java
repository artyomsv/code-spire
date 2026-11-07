package dev.codespire.orchestrator.llm;

import io.quarkus.test.security.TestSecurity;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The /api/llm-providers REST layer: create validates the key against the provider
 * (a WireMock /models stub), the key is never returned, an unsupported type or a
 * rejected key is a 400, and the default can be switched.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class LlmProviderResourceTest {

    private static WireMockServer llm; // stands in for the LLM provider; baseUrl points here

    @Inject
    LlmProviderRegistry registry;

    @Inject
    LlmModelRegistry models;

    @Inject
    dev.codespire.orchestrator.repository.RepositoryRegistry repositories;

    @Inject
    javax.sql.DataSource dataSource;

    private static final String NAMING_WORKSPACE = "TEST-llm-provider-delete";

    @BeforeAll
    static void startLlm() {
        llm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        llm.start();
    }

    @AfterAll
    static void stopLlm() {
        llm.stop();
    }

    @BeforeEach
    void reset() {
        registry.list().forEach(v -> registry.delete(UUID.fromString(v.id())));
        // A provider's model must be catalogued (LlmProviderModelGuardTest) — idempotent, since
        // this runs before every test in the class and the model name is unique.
        ensureCatalogued("openai", "TEST-MODEL");
        ensureCatalogued("anthropic", "claude-sonnet-4");
        ensureCatalogued("gemini", "gemini-2.5-pro");
        llm.resetAll();
        llm.stubFor(get(urlEqualTo("/models"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"data\": [] }")));
    }

    private void ensureCatalogued(String type, String name) {
        if (models.list().stream().noneMatch(m -> m.name().equals(name))) {
            models.create(new LlmModelInput(type, name, name, "UNMETERED", Map.of(),
                    null, null, null, null, true, java.util.List.of()));
        }
    }

    private static Map<String, Object> body(Object apiKey) {
        var m = new java.util.HashMap<String, Object>();
        m.put("name", "OpenAI");
        m.put("type", "openai");
        m.put("baseUrl", llm.baseUrl()); // validator appends /models -> hits the stub
        m.put("model", "TEST-MODEL");
        if (apiKey != null) {
            m.put("apiKey", apiKey);
        }
        return m;
    }

    @Test
    void createsValidatesTheKeyAndFirstIsDefault() {
        given().contentType("application/json").body(body("sk-good"))
                .when().post("/api/llm-providers")
                .then().statusCode(201)
                .body("hasApiKey", is(true))
                .body("isDefault", is(true))     // first provider auto-defaults
                .body("apiKey", nullValue())     // key never returned
                .body("id", notNullValue());
    }

    @Test
    void rejectsAKeyTheProviderDoesNotAccept() {
        llm.stubFor(get(urlEqualTo("/models")).willReturn(aResponse().withStatus(401)));
        given().contentType("application/json").body(body("sk-bad"))
                .when().post("/api/llm-providers").then().statusCode(400);
    }

    /** What the key check says, from a stubbed {@code /models} answer. */
    private String keyCheckSays(int status, String contentType, String body) {
        llm.stubFor(get(urlEqualTo("/models")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", contentType).withBody(body)));
        return given().contentType("application/json").body(body("TEST-key"))
                .when().post("/api/llm-providers")
                .then().statusCode(400)
                .contentType(org.hamcrest.Matchers.containsString("text/plain"))
                .extract().asString();
    }

    /**
     * The dialog shows the response body, and an empty one left the operator with "(400)". Anthropic
     * refuses a user key that is not scoped to a workspace; that answer is recognised and told in
     * the application's own words.
     */
    @Test
    void anAnthropicUserKeyIsToldToUseAWorkspaceKey() {
        String said = keyCheckSays(400, "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"TEST-This API key is not scoped to a workspace, so add a header.\"}}");

        org.junit.jupiter.api.Assertions.assertEquals(KeyCheckReason.WORKSPACE_REQUIRED.message(400), said);
        org.junit.jupiter.api.Assertions.assertTrue(said.contains("inside a workspace"), said);
        org.junit.jupiter.api.Assertions.assertFalse(said.contains("TEST-"), said);
    }

    @Test
    void aRejectedKeySaysSo() {
        org.junit.jupiter.api.Assertions.assertEquals(KeyCheckReason.REJECTED.message(401),
                keyCheckSays(401, "application/json", "{\"error\":{\"message\":\"TEST-invalid x-api-key\"}}"));
    }

    @Test
    void aRateLimitedKeySaysSo() {
        org.junit.jupiter.api.Assertions.assertEquals(KeyCheckReason.RATE_LIMITED.message(429),
                keyCheckSays(429, "application/json", "{}"));
    }

    @Test
    void aFailingProviderSaysSo() {
        org.junit.jupiter.api.Assertions.assertEquals(KeyCheckReason.PROVIDER_FAILING.message(502),
                keyCheckSays(502, "text/html", "<html>TEST-gateway</html>"));
    }

    @Test
    void anyOtherRefusalNamesTheStatusOnly() {
        org.junit.jupiter.api.Assertions.assertEquals(KeyCheckReason.REFUSED.message(404),
                keyCheckSays(404, "application/json", "{\"error\":{\"message\":\"TEST-not found\"}}"));
    }

    /**
     * Provider text is never returned: it may echo part of the key or name an account, and the reason
     * is also logged and stored as the provider's last check error.
     */
    @Test
    void theProvidersOwnTextNeverComesBack() {
        String said = keyCheckSays(401, "application/json",
                "{\"error\":{\"message\":\"TEST-secret-***3456 for TEST-account@example.invalid\"}}");

        org.junit.jupiter.api.Assertions.assertFalse(said.contains("TEST-"), said);
    }

    /**
     * An error body is read only up to a bound: a workspace marker placed past it is never seen, so
     * a huge body cannot fill the heap on its way to a short message.
     */
    @Test
    void anErrorBodyIsReadOnlyUpToItsBound() {
        String padding = "x".repeat(KeyCheckReason.MAX_ERROR_BODY_BYTES + 1024);
        String said = keyCheckSays(400, "text/plain", padding + " not scoped to a workspace");

        org.junit.jupiter.api.Assertions.assertEquals(KeyCheckReason.REFUSED.message(400), said);
    }

    /** A large models list is not needed and is not read; the key still saves. */
    @Test
    void aLargeSuccessfulAnswerStillSaves() {
        llm.stubFor(get(urlEqualTo("/models")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[\"" + "m".repeat(4 * 1024 * 1024) + "\"]}")));

        given().contentType("application/json").body(body("TEST-key"))
                .when().post("/api/llm-providers").then().statusCode(201);
    }

    @Test
    void anUnreachableProviderSaysSo() {
        var b = body("TEST-key");
        b.put("baseUrl", "http://localhost:1"); // nothing listens on port 1
        given().contentType("application/json").body(b)
                .when().post("/api/llm-providers")
                .then().statusCode(400)
                .body(equalTo(KeyCheckReason.UNREACHABLE.message(0)));
    }

    @Test
    void aProviderThatDoesNotAnswerInTimeSaysSo() {
        llm.stubFor(get(urlEqualTo("/models")).willReturn(aResponse().withStatus(200)
                .withFixedDelay((int) LlmKeyValidator.TIMEOUT.toMillis() + 1500)));

        given().contentType("application/json").body(body("TEST-key"))
                .when().post("/api/llm-providers")
                .then().statusCode(400)
                .body(equalTo(KeyCheckReason.TIMEOUT.message(0)));
    }

    /** The Check button, its log line and the stored last error read the same fixed reason. */
    @Test
    void theCheckButtonReportsTheSameFixedReason() {
        String id = given().contentType("application/json").body(body("TEST-key"))
                .when().post("/api/llm-providers").then().statusCode(201).extract().path("id");
        llm.stubFor(get(urlEqualTo("/models")).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"TEST-access disabled\"}}")));

        given().when().post("/api/llm-providers/" + id + "/check")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("detail", equalTo(KeyCheckReason.REJECTED.message(403)));
    }

    @Test
    void rejectsAnUnsupportedType() {
        var b = body("sk-good");
        b.put("type", "cohere"); // not a supported provider type
        given().contentType("application/json").body(b)
                .when().post("/api/llm-providers").then().statusCode(400);
    }

    @Test
    void createsAnAnthropicProviderValidatingItsKey() {
        var b = body("sk-ant");
        b.put("type", "anthropic");
        b.put("model", "claude-sonnet-4");
        given().contentType("application/json").body(b)
                .when().post("/api/llm-providers")
                .then().statusCode(201).body("type", is("anthropic")).body("apiKey", nullValue());
    }

    @Test
    void createsAGeminiProviderValidatingItsKey() {
        var b = body("gk-1");
        b.put("type", "gemini");
        b.put("model", "gemini-2.5-pro");
        given().contentType("application/json").body(b)
                .when().post("/api/llm-providers")
                .then().statusCode(201).body("type", is("gemini")).body("apiKey", nullValue());
    }

    @Test
    void requiresAKeyOnCreate() {
        given().contentType("application/json").body(body(null))
                .when().post("/api/llm-providers").then().statusCode(400);
    }

    @Test
    void listNeverReturnsTheKey() {
        given().contentType("application/json").body(body("sk-secret"))
                .when().post("/api/llm-providers").then().statusCode(201);
        when().get("/api/llm-providers")
                .then().statusCode(200)
                .body("[0].hasApiKey", is(true))
                .body("[0].apiKey", nullValue());
    }

    @Test
    void setDefaultSwitchesTheDefault() {
        String first = given().contentType("application/json").body(body("sk-1"))
                .when().post("/api/llm-providers").then().statusCode(201)
                .extract().path("id");
        String second = given().contentType("application/json").body(body("sk-2"))
                .when().post("/api/llm-providers").then().statusCode(201)
                .extract().path("id");

        // first is the default; switch to second
        given().when().put("/api/llm-providers/" + second + "/default")
                .then().statusCode(200).body("isDefault", is(true));
        when().get("/api/llm-providers/" + first)
                .then().statusCode(200).body("isDefault", is(false));
    }

    @Test
    void updateWithoutAKeyKeepsTheStoredOne() {
        String id = given().contentType("application/json").body(body("sk-keep"))
                .when().post("/api/llm-providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(body(null)) // no apiKey -> no re-validation
                .when().put("/api/llm-providers/" + id)
                .then().statusCode(200).body("hasApiKey", is(true));
    }

    @Test
    void deleteRemovesTheProvider() {
        String id = given().contentType("application/json").body(body("sk-x"))
                .when().post("/api/llm-providers").then().statusCode(201).extract().path("id");
        given().when().delete("/api/llm-providers/" + id).then().statusCode(204);
        when().get("/api/llm-providers/" + id).then().statusCode(404);
    }

    /**
     * A provider a repository reviews with cannot be deleted: the answer names the repository, so the
     * operator knows what to change first, and the provider row is left exactly as it was.
     */
    @Test
    void deletingAProviderARepositoryNamesIsRefusedAndNamesTheRepository() throws Exception {
        String id = given().contentType("application/json").body(body("sk-named"))
                .when().post("/api/llm-providers").then().statusCode(201).extract().path("id");
        UUID repository = repositories.create(new dev.codespire.orchestrator.repository.RepositoryInput(
                "github", "https://github.example.invalid", NAMING_WORKSPACE, "TEST-repo", true, null, null)).id();
        execute("UPDATE repository SET review_llm_provider_id=? WHERE id=?", UUID.fromString(id), repository);

        given().when().delete("/api/llm-providers/" + id)
                .then().statusCode(409).body(org.hamcrest.Matchers.containsString(NAMING_WORKSPACE + "/TEST-repo"));
        when().get("/api/llm-providers/" + id).then().statusCode(200).body("hasApiKey", is(true));
    }

    /** The repository goes first: the next reset deletes every provider, and this one is still named. */
    @AfterEach
    void removeTheNamingRepository() throws Exception {
        execute("DELETE FROM repository_registration_bridge WHERE target=?", NAMING_WORKSPACE + "/TEST-repo");
        execute("DELETE FROM repository_account WHERE repository_id IN (SELECT id FROM repository WHERE workspace=?)",
                NAMING_WORKSPACE);
        execute("DELETE FROM repository WHERE workspace=?", NAMING_WORKSPACE);
    }

    private void execute(String sql, Object... parameters) throws Exception {
        try (java.sql.Connection c = dataSource.getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) ps.setObject(i + 1, parameters[i]);
            ps.executeUpdate();
        }
    }

    @Test
    void rejectsAPrivateBaseUrlIsRelaxedInTest() {
        // %test sets allow-insecure-provider-urls=true, so localhost passes — the
        // create succeeds against the WireMock (guard behavior itself is covered by
        // ProviderUrlValidationTest).
        given().contentType("application/json").body(body("sk-good"))
                .when().post("/api/llm-providers").then().statusCode(equalTo(201));
    }
}
