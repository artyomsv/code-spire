package dev.codespire.orchestrator.settings;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * The Review diff limit: empty means "as much as the model's window allows", a number from 24,000 to 2,000,000 caps
 * the diff of every API review, anything else is a 400, and only an admin reads or changes it. Backed by a real
 * datasource so the app_setting round-trip is exercised.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReviewDiffLimitResourceTest {

    private static final String PATH = "/api/settings/review-diff-limit";

    @Inject
    ReviewDiffLimit stored;

    /** Unset again, so no other suite's review is capped by this one. Through the bean: the viewer case cannot PUT. */
    @AfterEach
    void clear() {
        stored.set(null);
    }

    @Test
    void unsetReadsAsNull() {
        when().get(PATH).then().statusCode(200).body("limitTokens", nullValue());
    }

    @Test
    void aLimitPersistsWithoutRestart() {
        given().contentType("application/json").body(limit(90_000)).when().put(PATH)
                .then().statusCode(200).body("limitTokens", equalTo(90_000));

        when().get(PATH).then().statusCode(200).body("limitTokens", equalTo(90_000));
    }

    @Test
    void nullClearsIt() {
        given().contentType("application/json").body(limit(90_000)).when().put(PATH).then().statusCode(200);

        given().contentType("application/json").body(limit(null)).when().put(PATH)
                .then().statusCode(200).body("limitTokens", nullValue());
        when().get(PATH).then().statusCode(200).body("limitTokens", nullValue());
    }

    @Test
    void theBoundsAreInclusive() {
        given().contentType("application/json").body(limit(ReviewDiffLimit.MIN_TOKENS)).when().put(PATH)
                .then().statusCode(200).body("limitTokens", equalTo(24_000));
        given().contentType("application/json").body(limit(ReviewDiffLimit.MAX_TOKENS)).when().put(PATH)
                .then().statusCode(200).body("limitTokens", equalTo(2_000_000));
    }

    @Test
    void anythingOutsideTheRangeIsRefused() {
        given().contentType("application/json").body(limit(23_999)).when().put(PATH).then().statusCode(400);
        given().contentType("application/json").body(limit(2_000_001)).when().put(PATH).then().statusCode(400);
        given().contentType("application/json").body(limit(0)).when().put(PATH).then().statusCode(400);
        given().contentType("application/json").body(limit(-1)).when().put(PATH).then().statusCode(400);
        when().get(PATH).then().statusCode(200).body("limitTokens", nullValue());
    }

    @Test
    void aFractionIsRefusedRatherThanTruncated() {
        given().contentType("application/json").body("{\"limitTokens\":30000.5}").when().put(PATH)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "test-viewer", roles = "spire-viewer")
    void aViewerCannotReadOrChangeIt() {
        when().get(PATH).then().statusCode(403);
        given().contentType("application/json").body(limit(90_000)).when().put(PATH).then().statusCode(403);
    }

    private static Map<String, Object> limit(Integer tokens) {
        Map<String, Object> body = new HashMap<>();
        body.put("limitTokens", tokens);
        return body;
    }
}
