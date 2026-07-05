package dev.codespire.orchestrator.provider;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/** The /api/providers REST layer: create/validate, and the token is never returned. */
@QuarkusTest
class ProviderResourceTest {

    private static Map<String, Object> body(String workspace, String authKind, Object secret, String username) {
        var m = new java.util.HashMap<String, Object>();
        m.put("name", "CF");
        m.put("type", "bitbucket-cloud");
        m.put("baseUrl", "https://api.bitbucket.org/2.0");
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
