package dev.codespire.gateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The gateway-owned /api/webhook-repos CRUD: create generates a routing key and
 * stores the secret encrypted (never echoed); no provider registry is involved —
 * providerType is supplied directly.
 */
@QuarkusTest
class WebhookRepoResourceTest {

    private static Map<String, Object> body(String providerType, String scope, String target, Object secret) {
        var m = new HashMap<String, Object>();
        m.put("providerType", providerType);
        m.put("scope", scope);
        m.put("target", target);
        m.put("enabled", true);
        if (secret != null) {
            m.put("secret", secret);
        }
        return m;
    }

    @Test
    void createRepoScopeGeneratesAKeyAndNeverEchoesTheSecret() {
        given().contentType("application/json").body(body("github", "repo", "wh-create/repo", "hooksecret"))
                .when().post("/api/webhook-repos")
                .then().statusCode(201)
                .body("scope", equalTo("repo"))
                .body("target", equalTo("wh-create/repo"))
                .body("providerType", equalTo("github"))
                .body("hasSecret", is(true))
                .body("webhookKey", notNullValue())
                .body("secret", nullValue());
    }

    @Test
    void createOrgScopeAcceptsAnOwnerTarget() {
        given().contentType("application/json").body(body("github", "org", "wh-org", "s"))
                .when().post("/api/webhook-repos")
                .then().statusCode(201)
                .body("scope", equalTo("org"))
                .body("target", equalTo("wh-org"));
    }

    @Test
    void eachRegistrationGetsADistinctKey() {
        String k1 = given().contentType("application/json").body(body("github", "repo", "wh-distinct/a", "s1"))
                .when().post("/api/webhook-repos").then().statusCode(201).extract().path("webhookKey");
        String k2 = given().contentType("application/json").body(body("github", "repo", "wh-distinct/b", "s2"))
                .when().post("/api/webhook-repos").then().statusCode(201).extract().path("webhookKey");
        org.junit.jupiter.api.Assertions.assertNotEquals(k1, k2);
    }

    @Test
    void updateKeepsTheKeyAndRotatesSecretOnlyWhenSupplied() {
        String id = given().contentType("application/json").body(body("github", "repo", "wh-update/repo", "s1"))
                .when().post("/api/webhook-repos").then().statusCode(201).extract().path("id");
        String key = given().when().get("/api/webhook-repos/" + id).then().statusCode(200)
                .extract().path("webhookKey");

        given().contentType("application/json").body(body("github", "repo", "wh-update/renamed", null))
                .when().put("/api/webhook-repos/" + id)
                .then().statusCode(200)
                .body("target", equalTo("wh-update/renamed"))
                .body("hasSecret", is(true))
                .body("webhookKey", equalTo(key));
    }

    @Test
    void rejectsMissingSecretOnCreate() {
        given().contentType("application/json").body(body("github", "repo", "wh-nosecret/repo", null))
                .when().post("/api/webhook-repos")
                .then().statusCode(400);
    }

    @Test
    void rejectsScopeTargetMismatch() {
        // repo scope needs owner/repo; org scope needs a bare owner.
        given().contentType("application/json").body(body("github", "repo", "wh-noslash", "s"))
                .when().post("/api/webhook-repos").then().statusCode(400);
        given().contentType("application/json").body(body("github", "org", "wh/with-slash", "s"))
                .when().post("/api/webhook-repos").then().statusCode(400);
        given().contentType("application/json").body(body("github", "repo", "../evil", "s"))
                .when().post("/api/webhook-repos").then().statusCode(400);
    }

    @Test
    void rejectsAnUnknownProviderType() {
        given().contentType("application/json").body(body("gitea", "repo", "wh-badtype/repo", "s"))
                .when().post("/api/webhook-repos")
                .then().statusCode(400);
    }

    @Test
    void deletesAndThenIsGone() {
        String id = given().contentType("application/json").body(body("github", "repo", "wh-delete/repo", "s"))
                .when().post("/api/webhook-repos").then().statusCode(201).extract().path("id");
        given().when().delete("/api/webhook-repos/" + id).then().statusCode(204);
        given().when().get("/api/webhook-repos/" + id).then().statusCode(404);
    }

    @Test
    void listsCreatedRegistrationWithoutTheSecret() {
        given().contentType("application/json").body(body("github", "repo", "wh-list/repo", "s"))
                .when().post("/api/webhook-repos").then().statusCode(201);
        given().when().get("/api/webhook-repos")
                .then().statusCode(200)
                .body("findAll { it.target == 'wh-list/repo' }.size()", equalTo(1))
                .body("find { it.target == 'wh-list/repo' }.webhookSecret", is(nullValue()));
    }
}
