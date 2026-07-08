package dev.codespire.orchestrator.context;

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
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The /api/context-providers REST layer: create validates the credential against
 * the source (a WireMock /rest/api/2/myself stub), the secret is never returned,
 * an unsupported type / missing username / rejected credential is a 400, and the
 * default can be switched. Mirrors {@link dev.codespire.orchestrator.llm.LlmProviderResourceTest}.
 */
@QuarkusTest
class ContextProviderResourceTest {

    private static WireMockServer jira; // stands in for Jira; baseUrl points here

    @Inject
    ContextProviderRegistry registry;

    @BeforeAll
    static void startJira() {
        jira = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        jira.start();
    }

    @AfterAll
    static void stopJira() {
        jira.stop();
    }

    @BeforeEach
    void reset() {
        registry.list().forEach(v -> registry.delete(UUID.fromString(v.id())));
        jira.resetAll();
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"accountId\": \"abc\", \"emailAddress\": \"bot@acme.com\" }")));
    }

    private static Map<String, Object> body(Object secret) {
        var m = new java.util.HashMap<String, Object>();
        m.put("name", "Acme Jira");
        m.put("type", "jira");
        m.put("baseUrl", jira.baseUrl()); // validator appends /rest/api/2/myself -> hits the stub
        m.put("authKind", "basic");
        m.put("username", "bot@acme.com");
        if (secret != null) {
            m.put("secret", secret);
        }
        return m;
    }

    @Test
    void createsValidatesTheCredentialAndFirstIsDefault() {
        given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers")
                .then().statusCode(201)
                .body("hasSecret", is(true))
                .body("isDefault", is(true))      // first provider auto-defaults
                .body("secret", nullValue())      // secret never returned
                .body("id", notNullValue());
    }

    @Test
    void rejectsACredentialTheProviderDoesNotAccept() {
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse().withStatus(401)));
        given().contentType("application/json").body(body("bad-token"))
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void rejectsAnUnsupportedType() {
        var b = body("jira-token");
        b.put("type", "confluence"); // not yet a supported context provider type
        given().contentType("application/json").body(b)
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void requiresASecretOnCreate() {
        given().contentType("application/json").body(body(null))
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void basicAuthRequiresAUsername() {
        var b = body("jira-token");
        b.remove("username");
        given().contentType("application/json").body(b)
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void acceptsABearerProviderWithoutUsername() {
        var b = body("pat-token");
        b.put("authKind", "bearer");
        b.remove("username");
        given().contentType("application/json").body(b)
                .when().post("/api/context-providers")
                .then().statusCode(201).body("authKind", is("bearer")).body("hasSecret", is(true));
    }

    @Test
    void listNeverReturnsTheSecret() {
        given().contentType("application/json").body(body("jira-secret"))
                .when().post("/api/context-providers").then().statusCode(201);
        when().get("/api/context-providers")
                .then().statusCode(200)
                .body("[0].hasSecret", is(true))
                .body("[0].secret", nullValue());
    }

    @Test
    void setDefaultSwitchesTheDefault() {
        String first = given().contentType("application/json").body(body("t-1"))
                .when().post("/api/context-providers").then().statusCode(201)
                .extract().path("id");
        String second = given().contentType("application/json").body(body("t-2"))
                .when().post("/api/context-providers").then().statusCode(201)
                .extract().path("id");

        given().when().put("/api/context-providers/" + second + "/default")
                .then().statusCode(200).body("isDefault", is(true));
        when().get("/api/context-providers/" + first)
                .then().statusCode(200).body("isDefault", is(false));
    }

    @Test
    void updateWithoutASecretKeepsTheStoredOne() {
        String id = given().contentType("application/json").body(body("keep-me"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(body(null)) // no secret -> no re-validation
                .when().put("/api/context-providers/" + id)
                .then().statusCode(200).body("hasSecret", is(true));
    }

    @Test
    void deleteRemovesTheProvider() {
        String id = given().contentType("application/json").body(body("t-x"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().when().delete("/api/context-providers/" + id).then().statusCode(204);
        when().get("/api/context-providers/" + id).then().statusCode(404);
    }

    @Test
    void checkReportsConnectedWithTheTokenOwner() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().when().post("/api/context-providers/" + id + "/check")
                .then().statusCode(200)
                .body("ok", is(true))
                .body("account", is("bot@acme.com")); // from the /myself stub (emailAddress fallback)
    }

    @Test
    void checkReportsFailureWhenTheTokenIsNoLongerAccepted() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        // token later revoked at the source
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse().withStatus(401)));
        given().when().post("/api/context-providers/" + id + "/check")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("account", nullValue())
                .body("detail", notNullValue());
    }

    @Test
    void createIsRejectedWhenValidationHitsASignInPage() {
        // A 200 that is HTML (an SSO/login redirect) must NOT pass validation.
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody("<!DOCTYPE html><html><head><title>Log in</title></head></html>")));
        given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void checkReportsFailureWhenJiraReturnsASignInPage() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        // token later starts bouncing to a 200 HTML sign-in page
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody("<!DOCTYPE html><html>Log in</html>")));
        given().when().post("/api/context-providers/" + id + "/check")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("detail", notNullValue());
    }

    @Test
    void previewResolvesABareNumberViaProjectKeysAndReturnsTheItem() {
        var b = body("jira-token");
        b.put("projectKeys", "ACME");
        String id = given().contentType("application/json").body(b)
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        jira.stubFor(get(urlPathEqualTo("/rest/api/2/issue/ACME-12345")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\":\"ACME-12345\",\"fields\":{"
                        + "\"summary\":\"Widget bug\",\"status\":{\"name\":\"Open\"},\"issuetype\":{\"name\":\"Bug\"}}}")));

        given().contentType("application/json").body(java.util.Map.of("text", "12345"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200)
                .body("status", is("OK"))
                .body("keys[0]", is("ACME-12345"))
                .body("items[0].kind", is("JIRA_TICKET"))
                .body("items[0].title", is("ACME-12345 — Widget bug"));
    }

    @Test
    void previewIsEmptyForABareNumberWithoutProjectKeys() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(java.util.Map.of("text", "12345"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200).body("status", is("EMPTY")).body("detail", notNullValue());
    }

    @Test
    void rejectsAPrivateBaseUrlIsRelaxedInTest() {
        // %test sets allow-insecure-provider-urls=true, so localhost passes — the create
        // succeeds against the WireMock (guard behavior is covered by ProviderUrlValidationTest).
        given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(equalTo(201));
    }
}
