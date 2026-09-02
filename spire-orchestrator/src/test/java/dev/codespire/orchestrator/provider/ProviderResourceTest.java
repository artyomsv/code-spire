package dev.codespire.orchestrator.provider;

import io.quarkus.test.security.TestSecurity;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * The /api/providers REST layer: create/validate, token never returned, and the
 * auto-resolve/validate step — on create the token is checked against the SCM
 * (a WireMock stub here) and the bot account id is filled from the token owner
 * when left blank.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ProviderResourceTest {

    private static WireMockServer scm; // stands in for the SCM; baseUrl points here
    private static final String RESOLVED_ACCOUNT_ID = "712020:resolved-bot";

    @BeforeAll
    static void startScm() {
        scm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        scm.start();
    }

    @AfterAll
    static void stopScm() {
        scm.stop();
    }

    @BeforeEach
    void stubWhoami() {
        scm.resetAll();
        scm.stubFor(get(urlEqualTo("/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "account_id": "%s", "username": "spire_bot", "display_name": "Code Spire Bot" }
                        """.formatted(RESOLVED_ACCOUNT_ID))));
    }

    private static Map<String, Object> body(String workspace, String authKind, Object secret, String username) {
        var m = new java.util.HashMap<String, Object>();
        m.put("name", "CF");
        m.put("type", "bitbucket-cloud");
        m.put("baseUrl", scm.baseUrl()); // the client appends /user -> hits the stub
        m.put("workspace", workspace);
        m.put("authKind", authKind);
        m.put("botAccountId", "acct-1");
        m.put("enabled", true);
        m.put("authors", List.of("alice"));
        if (secret != null) {
            m.put("secret", secret);
        }
        if (username != null) {
            m.put("authUsername", username);
        }
        return m;
    }

    @jakarta.inject.Inject
    ProviderRegistry registry;

    @Test
    void aFactoryRoleSurvivesTheRestPathOnCreateAndUpdate() {
        // resolveIdentity rebuilt the input with the 12-argument constructor, so a FACTORY
        // registration through this endpoint was stored as the workspace's REVIEWER: the review
        // pipeline held the push token and POST /api/runs answered 409 forever. Only a test that
        // goes through the resource can see it — every earlier test called the registry directly.
        var b = body("rest-factory", "bearer", "tok-abc", null);
        b.put("role", "FACTORY");
        String id = given().contentType("application/json").body(b)
                .when().post("/api/providers")
                .then().statusCode(201)
                .body("role", equalTo("FACTORY"))
                .extract().path("id");

        org.junit.jupiter.api.Assertions.assertTrue(
                registry.resolve("bitbucket-cloud", "rest-factory", ProviderRole.FACTORY).isPresent());
        org.junit.jupiter.api.Assertions.assertTrue(
                registry.resolve("bitbucket-cloud", "rest-factory").isEmpty(),
                "a FACTORY registration is never the workspace's reviewer");

        // The dashboard's edit form sends NO role. An update without one must keep the stored role —
        // writing the default there demoted every FACTORY registration edited in Settings to the
        // workspace's reviewer, which is the round-1 critical back through the UI path.
        var update = body("rest-factory", "bearer", "", null);
        update.remove("role");
        given().contentType("application/json").body(update)
                .when().put("/api/providers/" + id)
                .then().statusCode(200)
                .body("role", equalTo("FACTORY"));
        org.junit.jupiter.api.Assertions.assertTrue(
                registry.resolve("bitbucket-cloud", "rest-factory", ProviderRole.FACTORY).isPresent());

        // An explicit role on update still changes it: that is a deliberate operator action.
        update.put("role", "REVIEWER");
        given().contentType("application/json").body(update)
                .when().put("/api/providers/" + id)
                .then().statusCode(200)
                .body("role", equalTo("REVIEWER"));
        org.junit.jupiter.api.Assertions.assertTrue(
                registry.resolve("bitbucket-cloud", "rest-factory").isPresent());
    }

    @Test
    void aRoleOutsideTheClosedSetIsA400NamingTheSet() {
        var b = body("rest-role", "bearer", "tok-abc", null);
        b.put("role", "OVERLORD");
        given().contentType("application/json").body(b)
                .when().post("/api/providers")
                .then().statusCode(400);
    }

    @Test
    void createReturns201AndNeverEchoesTheSecret() {
        given().contentType("application/json").body(body("rest-create", "bearer", "tok-abc", null))
                .when().post("/api/providers")
                .then().statusCode(201)
                .body("hasSecret", is(true))
                .body("workspace", equalTo("rest-create"))
                .body("secret", is(nullOrEmpty()))
                .body("authors[0]", equalTo("alice"));
    }

    @Test
    void autoResolvesBotAccountIdFromTheTokenWhenBlank() {
        var b = body("rest-resolve", "bearer", "tok-abc", null);
        b.put("botAccountId", ""); // operator leaves it blank -> server fills it
        given().contentType("application/json").body(b)
                .when().post("/api/providers")
                .then().statusCode(201)
                .body("botAccountId", equalTo(RESOLVED_ACCOUNT_ID));
    }

    @Test
    void theValidatedTokensIdentityOverridesASubmittedBotAccountId() {
        // The token says who the bot is. Honouring a submitted id instead meant that saving a different
        // bot's token updated the username but kept the previous account's id, and the ADR-013 self-loop
        // guard matches on that id — so the bot no longer recognized its own comments.
        given().contentType("application/json").body(body("rest-explicit", "bearer", "tok-abc", null))
                .when().post("/api/providers")
                .then().statusCode(201)
                .body("botAccountId", equalTo(RESOLVED_ACCOUNT_ID));
    }

    @Test
    void rejectsAnInvalidToken() {
        scm.resetAll();
        scm.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(401)));
        given().contentType("application/json").body(body("rest-badtoken", "bearer", "bad-tok", null))
                .when().post("/api/providers")
                .then().statusCode(400); // token validated up front, fails fast
    }

    @Test
    void rejectsMissingSecretOnCreate() {
        given().contentType("application/json").body(body("rest-nosecret", "bearer", null, null))
                .when().post("/api/providers")
                .then().statusCode(400);
    }

    @Test
    void rejectsBasicWithoutUsername() {
        given().contentType("application/json").body(body("rest-basic", "basic", "tok", null))
                .when().post("/api/providers")
                .then().statusCode(400);
    }

    @Test
    void rejectsUnknownType() {
        var b = body("rest-type", "bearer", "tok", null);
        b.put("type", "gitea"); // not a supported SCM type
        given().contentType("application/json").body(b)
                .when().post("/api/providers")
                .then().statusCode(400);
    }

    @Test
    void checkReportsOkWithTheTokenOwner() {
        String id = given().contentType("application/json").body(body("rest-check-ok", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201)
                .extract().path("id");
        given().when().post("/api/providers/" + id + "/check")
                .then().statusCode(200)
                .body("ok", is(true))
                .body("account", equalTo("spire_bot"));
    }

    @Test
    void checkReportsFailureOnRejectedToken() {
        String id = given().contentType("application/json").body(body("rest-check-fail", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201)
                .extract().path("id");
        // the token is later rejected upstream — the live check must surface it
        scm.resetAll();
        scm.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(401)));
        given().when().post("/api/providers/" + id + "/check")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("detail", org.hamcrest.Matchers.containsString("Authentication failed"));
    }

    @Test
    void verifyRepoReportsOkWhenRepoExists() {
        String id = given().contentType("application/json").body(body("rest-verify-ok", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201).extract().path("id");
        scm.stubFor(get(urlEqualTo("/repositories/rest-verify-ok/widgets")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{ \"full_name\": \"rest-verify-ok/widgets\" }")));
        given().contentType("application/json").body(Map.of("repo", "rest-verify-ok/widgets"))
                .when().post("/api/providers/" + id + "/verify-repo")
                .then().statusCode(200).body("ok", is(true));
    }

    @Test
    void verifyRepoReportsNotFound() {
        String id = given().contentType("application/json").body(body("rest-verify-404", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201).extract().path("id");
        scm.stubFor(get(urlEqualTo("/repositories/rest-verify-404/ghost")).willReturn(aResponse().withStatus(404)));
        given().contentType("application/json").body(Map.of("repo", "rest-verify-404/ghost"))
                .when().post("/api/providers/" + id + "/verify-repo")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("detail", org.hamcrest.Matchers.containsString("not found"));
    }

    @Test
    void verifyRepoReportsUnauthorized() {
        String id = given().contentType("application/json").body(body("rest-verify-401", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201).extract().path("id");
        scm.stubFor(get(urlEqualTo("/repositories/rest-verify-401/secret")).willReturn(aResponse().withStatus(403)));
        given().contentType("application/json").body(Map.of("repo", "rest-verify-401/secret"))
                .when().post("/api/providers/" + id + "/verify-repo")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("detail", org.hamcrest.Matchers.containsString("Authentication failed"));
    }

    @Test
    void verifyRepoRejectsABlankRepo() {
        String id = given().contentType("application/json").body(body("rest-verify-blank", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(Map.of("repo", ""))
                .when().post("/api/providers/" + id + "/verify-repo")
                .then().statusCode(400);
    }

    @Test
    void verifyRepoRejectsAMultiSegmentRepo() {
        String id = given().contentType("application/json").body(body("rest-verify-multi", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(Map.of("repo", "owner/group/repo"))
                .when().post("/api/providers/" + id + "/verify-repo")
                .then().statusCode(400);
    }

    @Test
    void listsCreatedProvider() {
        given().contentType("application/json").body(body("rest-list", "bearer", "tok", null))
                .when().post("/api/providers").then().statusCode(201);
        given().when().get("/api/providers")
                .then().statusCode(200)
                .body("findAll { it.workspace == 'rest-list' }.size()", equalTo(1));
    }

    private static org.hamcrest.Matcher<Object> nullOrEmpty() {
        return org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.nullValue(),
                org.hamcrest.Matchers.equalTo(""));
    }
}
