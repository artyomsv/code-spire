package dev.codespire.orchestrator.provider;

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
    void keepsAnExplicitBotAccountId() {
        given().contentType("application/json").body(body("rest-explicit", "bearer", "tok-abc", null))
                .when().post("/api/providers")
                .then().statusCode(201)
                .body("botAccountId", equalTo("acct-1")); // provided id respected, not overwritten
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
        b.put("type", "gitlab");
        given().contentType("application/json").body(b)
                .when().post("/api/providers")
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
