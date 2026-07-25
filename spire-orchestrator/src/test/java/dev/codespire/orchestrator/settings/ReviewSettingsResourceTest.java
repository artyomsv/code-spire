package dev.codespire.orchestrator.settings;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * The review pipeline's retry budget: GET reports the effective value, PUT persists it (no restart),
 * and out-of-range input is rejected rather than silently clamped at the edge. Backed by a real
 * datasource so the app_setting round-trip is exercised.
 *
 * <p>This budget is deliberately NOT the conversation one — an operator who set the conversation field
 * to 5 saw a review stop after 3, which is what put this on its own endpoint and its own Settings group.
 */
@QuarkusTest
class ReviewSettingsResourceTest {

    // Restore the seed default so sibling orchestrator tests see the budget they expect.
    @AfterEach
    void resetToDefault() {
        given().contentType("application/json").body(Map.of("maxAttempts", 3))
                .when().put("/api/settings/review").then().statusCode(200);
    }

    @Test
    void getReportsTheEffectiveBudget() {
        when().get("/api/settings/review")
                .then().statusCode(200).body("maxAttempts", greaterThanOrEqualTo(1));
    }

    @Test
    void putPersistsWithoutRestart() {
        given().contentType("application/json").body(Map.of("maxAttempts", 5))
                .when().put("/api/settings/review")
                .then().statusCode(200).body("maxAttempts", equalTo(5));

        when().get("/api/settings/review")
                .then().statusCode(200).body("maxAttempts", equalTo(5));
    }

    @Test
    void rejectsZeroOrNegative() {
        given().contentType("application/json").body(Map.of("maxAttempts", 0))
                .when().put("/api/settings/review").then().statusCode(400);
        given().contentType("application/json").body(Map.of("maxAttempts", -1))
                .when().put("/api/settings/review").then().statusCode(400);
    }

    @Test
    void rejectsAnAbsurdBudget() {
        // Every attempt re-runs the whole pipeline, so a typo here would hammer a dead provider.
        given().contentType("application/json").body(Map.of("maxAttempts", 1000))
                .when().put("/api/settings/review").then().statusCode(400);
    }
}
