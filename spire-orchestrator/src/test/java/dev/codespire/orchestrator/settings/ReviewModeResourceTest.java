package dev.codespire.orchestrator.settings;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.oneOf;

/**
 * The runtime review-mode toggle: GET reports the effective mode, PUT flips it and
 * persists (no restart), and invalid input is rejected. Backed by a real datasource
 * so the app_setting round-trip is exercised.
 */
@QuarkusTest
class ReviewModeResourceTest {

    // Leave the shared setting on the seed default so sibling orchestrator tests
    // (which assume active) are unaffected by a leaked override.
    @AfterEach
    void resetToActive() {
        given().contentType("application/json").body(Map.of("mode", "active"))
                .when().put("/api/settings/review-mode").then().statusCode(200);
    }

    @Test
    void getReportsAValidMode() {
        when().get("/api/settings/review-mode")
                .then().statusCode(200).body("mode", oneOf("observe", "active"));
    }

    @Test
    void togglesToObserveAndBackWithoutRestart() {
        given().contentType("application/json").body(Map.of("mode", "observe"))
                .when().put("/api/settings/review-mode")
                .then().statusCode(200).body("mode", equalTo("observe"));

        when().get("/api/settings/review-mode")
                .then().statusCode(200).body("mode", equalTo("observe"));

        given().contentType("application/json").body(Map.of("mode", "active"))
                .when().put("/api/settings/review-mode")
                .then().statusCode(200).body("mode", equalTo("active"));

        when().get("/api/settings/review-mode")
                .then().statusCode(200).body("mode", equalTo("active"));
    }

    @Test
    void acceptsMixedCaseAndTrimsIt() {
        given().contentType("application/json").body(Map.of("mode", "  OBSERVE  "))
                .when().put("/api/settings/review-mode")
                .then().statusCode(200).body("mode", equalTo("observe"));
    }

    @Test
    void rejectsAnUnknownMode() {
        given().contentType("application/json").body(Map.of("mode", "bogus"))
                .when().put("/api/settings/review-mode").then().statusCode(400);
    }

    @Test
    void rejectsAMissingMode() {
        given().contentType("application/json").body("{}")
                .when().put("/api/settings/review-mode").then().statusCode(400);
    }
}
