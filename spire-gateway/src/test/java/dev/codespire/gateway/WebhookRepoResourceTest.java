package dev.codespire.gateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The gateway-owned /api/webhook-repos CRUD: create/rotate mint the secret
 * server-side and reveal it exactly once ({@code WebhookRepoSecret(repo, secret)} —
 * nested {@code repo.*} view + top-level {@code secret}); every other read (get,
 * list, update) returns the flat {@code WebhookRepoView} with no secret at all,
 * only {@code hasSecret}. No provider registry is involved — providerType is
 * supplied directly.
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
    void createRepoScopeGeneratesAKeyAndRevealsTheSecretExactlyOnce() {
        given().contentType("application/json").body(body("github", "repo", "wh-create/repo", "hooksecret"))
                .when().post("/api/webhook-repos")
                .then().statusCode(201)
                .body("repo.scope", equalTo("repo"))
                .body("repo.target", equalTo("wh-create/repo"))
                .body("repo.providerType", equalTo("github"))
                .body("repo.hasSecret", is(true))
                .body("repo.webhookKey", notNullValue())
                .body("secret", notNullValue()); // the one time it's ever revealed (WebhookRepoSecret)
    }

    @Test
    void createOrgScopeAcceptsAnOwnerTarget() {
        given().contentType("application/json").body(body("github", "org", "wh-org", "s"))
                .when().post("/api/webhook-repos")
                .then().statusCode(201)
                .body("repo.scope", equalTo("org"))
                .body("repo.target", equalTo("wh-org"));
    }

    @Test
    void eachRegistrationGetsADistinctKey() {
        String k1 = given().contentType("application/json").body(body("github", "repo", "wh-distinct/a", "s1"))
                .when().post("/api/webhook-repos").then().statusCode(201).extract().path("repo.webhookKey");
        String k2 = given().contentType("application/json").body(body("github", "repo", "wh-distinct/b", "s2"))
                .when().post("/api/webhook-repos").then().statusCode(201).extract().path("repo.webhookKey");
        org.junit.jupiter.api.Assertions.assertNotEquals(k1, k2);
    }

    @Test
    void updateKeepsTheKeyAndRotatesSecretOnlyWhenSupplied() {
        String id = given().contentType("application/json").body(body("github", "repo", "wh-update/repo", "s1"))
                .when().post("/api/webhook-repos").then().statusCode(201).extract().path("repo.id");
        String key = given().when().get("/api/webhook-repos/" + id).then().statusCode(200)
                .extract().path("webhookKey");

        // update() returns the flat WebhookRepoView (Optional<WebhookRepoView>, not WebhookRepoSecret) —
        // renaming never touches the secret, so there is nothing to reveal here.
        given().contentType("application/json").body(body("github", "repo", "wh-update/renamed", null))
                .when().put("/api/webhook-repos/" + id)
                .then().statusCode(200)
                .body("target", equalTo("wh-update/renamed"))
                .body("hasSecret", is(true))
                .body("webhookKey", equalTo(key));
    }

    @Test
    void createIgnoresAnyClientSuppliedSecret() {
        // WebhookRepoInput carries no secret field at all (see its javadoc) — the server
        // always mints its own on create; a client-supplied value is silently dropped,
        // never rejected and never honored.
        given().contentType("application/json")
                .body(body("github", "repo", "wh-nosecret/repo", "client-supplied-value"))
                .when().post("/api/webhook-repos")
                .then().statusCode(201)
                .body("repo.hasSecret", is(true))
                .body("secret", notNullValue())
                .body("secret", not(equalTo("client-supplied-value")));
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
                .when().post("/api/webhook-repos").then().statusCode(201).extract().path("repo.id");
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
