package dev.codespire.orchestrator.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
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
class LlmProviderResourceTest {

    private static WireMockServer llm; // stands in for the LLM provider; baseUrl points here

    @Inject
    LlmProviderRegistry registry;

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
        llm.resetAll();
        llm.stubFor(get(urlEqualTo("/models"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"data\": [] }")));
    }

    private static Map<String, Object> body(Object apiKey) {
        var m = new java.util.HashMap<String, Object>();
        m.put("name", "OpenAI");
        m.put("type", "openai");
        m.put("baseUrl", llm.baseUrl()); // validator appends /models -> hits the stub
        m.put("model", "gpt-4o");
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

    @Test
    void rejectsAPrivateBaseUrlIsRelaxedInTest() {
        // %test sets allow-insecure-provider-urls=true, so localhost passes — the
        // create succeeds against the WireMock (guard behavior itself is covered by
        // ProviderUrlValidationTest).
        given().contentType("application/json").body(body("sk-good"))
                .when().post("/api/llm-providers").then().statusCode(equalTo(201));
    }
}
