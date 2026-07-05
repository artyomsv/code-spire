package dev.codespire.orchestrator.ingress;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The manual "register PR" endpoint (stub provider): valid input emits a
 * PullRequestEventReceived and echoes the reviewId; bad input is rejected 400.
 */
@QuarkusTest
class ManualRegisterResourceTest {

    @Test
    void registersByFields() {
        given().contentType("application/json").body(Map.of("workspace", "acme", "slug", "web", "pr", 7))
                .when().post("/api/reviews/register")
                .then().statusCode(200).body("reviewId", equalTo("review::acme/web#7"));
    }

    @Test
    void registersByUrl() {
        given().contentType("application/json")
                .body(Map.of("url", "https://bitbucket.org/acme/web/pull-requests/9"))
                .when().post("/api/reviews/register")
                .then().statusCode(200).body("reviewId", equalTo("review::acme/web#9"));
    }

    @Test
    void rejectsEmptyBody() {
        given().contentType("application/json").body("{}")
                .when().post("/api/reviews/register")
                .then().statusCode(400);
    }

    @Test
    void rejectsUnparseableUrl() {
        given().contentType("application/json").body(Map.of("url", "not-a-pr-url"))
                .when().post("/api/reviews/register")
                .then().statusCode(400);
    }

    @Test
    void rejectsNonPositivePr() {
        given().contentType("application/json").body(Map.of("workspace", "acme", "slug", "web", "pr", 0))
                .when().post("/api/reviews/register")
                .then().statusCode(400);
    }
}
