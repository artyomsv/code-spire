package dev.codespire.orchestrator.settings;

import dev.codespire.orchestrator.caps.CapPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * The spend-cap limits: GET reports {@code null} for anything unset (never {@code 0} -- a blank field
 * becoming {@code 0} is precisely how ADR-023's "unknown became zero" bug entered, and here it would
 * turn "no cap" into "cap of zero", refusing every review), PUT round-trips a value and clears one back
 * to unset, and a non-positive limit is rejected with an actionable body, not merely a 400 -- this
 * module has a documented class of bodiless {@code BadRequestException} (techdebt/spire-orchestrator/3-3).
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class CapSettingsResourceTest {

    @Inject
    AppSettingRepository settings;

    // Every limit key resets to blank ("unset") so this suite never leaks a configured cap into a
    // sibling test that assumes an unconfigured deployment.
    @AfterEach
    void clearEveryLimit() {
        settings.set(CapPolicy.KEY_MAX_CHANGED_FILES, "");
        settings.set(CapPolicy.KEY_MAX_DIFF_BYTES, "");
        settings.set(CapPolicy.KEY_SPEND_CAP, "");
        settings.set(CapPolicy.KEY_CALLS, "");
        settings.set(CapPolicy.KEY_WINDOW_MINUTES, "");
    }

    @Test
    void getReportsNullForEveryUnsetLimit() {
        when().get("/api/settings/caps")
                .then().statusCode(200)
                .body("maxChangedFiles", nullValue())
                .body("maxDiffBytes", nullValue())
                .body("spendCapMillicents", nullValue())
                .body("callCap", nullValue())
                .body("windowMinutes", equalTo(1440));
    }

    @Test
    void putRoundTripsAStoredLimit() {
        given().contentType("application/json")
                .body("""
                        {"maxChangedFiles":500,"maxDiffBytes":2000000,
                         "spendCapMillicents":500000,"callCap":100,"windowMinutes":60}
                        """)
                .when().put("/api/settings/caps")
                .then().statusCode(200)
                .body("maxChangedFiles", equalTo(500))
                .body("maxDiffBytes", equalTo(2_000_000))
                .body("spendCapMillicents", equalTo(500_000))
                .body("callCap", equalTo(100))
                .body("windowMinutes", equalTo(60));

        when().get("/api/settings/caps")
                .then().statusCode(200)
                .body("maxChangedFiles", equalTo(500))
                .body("windowMinutes", equalTo(60));
    }

    @Test
    void putWithNullClearsAPreviouslyStoredLimitBackToUnset() {
        given().contentType("application/json")
                .body("""
                        {"maxChangedFiles":500,"maxDiffBytes":2000000,
                         "spendCapMillicents":500000,"callCap":100,"windowMinutes":60}
                        """)
                .when().put("/api/settings/caps").then().statusCode(200);

        given().contentType("application/json")
                .body("""
                        {"maxChangedFiles":null,"maxDiffBytes":null,
                         "spendCapMillicents":null,"callCap":null,"windowMinutes":null}
                        """)
                .when().put("/api/settings/caps")
                .then().statusCode(200)
                .body("maxChangedFiles", nullValue())
                .body("maxDiffBytes", nullValue())
                .body("spendCapMillicents", nullValue())
                .body("callCap", nullValue())
                .body("windowMinutes", equalTo(1440));
    }

    @Test
    void aZeroOrNegativeLimitIsRejectedNamingTheField() {
        given().contentType("application/json")
                .body("""
                        {"maxChangedFiles":0,"maxDiffBytes":null,
                         "spendCapMillicents":null,"callCap":null,"windowMinutes":null}
                        """)
                .when().put("/api/settings/caps")
                .then().statusCode(400)
                .body(containsString("maxChangedFiles"));

        given().contentType("application/json")
                .body("""
                        {"maxChangedFiles":null,"maxDiffBytes":null,
                         "spendCapMillicents":-1,"callCap":null,"windowMinutes":null}
                        """)
                .when().put("/api/settings/caps")
                .then().statusCode(400)
                .body(containsString("spendCapMillicents"));
    }

    @Test
    void aNonPositiveWindowIsRejectedNamingTheField() {
        given().contentType("application/json")
                .body("""
                        {"maxChangedFiles":null,"maxDiffBytes":null,
                         "spendCapMillicents":null,"callCap":null,"windowMinutes":0}
                        """)
                .when().put("/api/settings/caps")
                .then().statusCode(400)
                .body(containsString("windowMinutes"));
    }

    /**
     * The window is the one field with an upper bound, because it is the one field that is arithmetic:
     * {@code Instant.now().minus(window)} throws beyond the instant range. Accepted with a 200 and then
     * fatal on every use was the worst available answer — it took down the whole attention panel (the
     * throw escapes {@code collect}'s SQLException catch), dead-lettered reviews and follow-ups, and
     * made {@code GET} itself 500, so the operator could not clear the value through the product.
     *
     * <p>The four caps are deliberately unbounded above: they are compared, never added to an instant,
     * so an implausibly large one is merely an unreachable limit.
     */
    @Test
    void anOutOfRangeWindowIsRejectedNamingTheField() {
        given().contentType("application/json")
                .body("""
                        {"maxChangedFiles":null,"maxDiffBytes":null,
                         "spendCapMillicents":null,"callCap":null,"windowMinutes":99999999999999999}
                        """)
                .when().put("/api/settings/caps")
                .then().statusCode(400)
                .body(containsString("windowMinutes"));

        given().contentType("application/json")
                .body("""
                        {"maxChangedFiles":null,"maxDiffBytes":null,
                         "spendCapMillicents":null,"callCap":null,"windowMinutes":525600}
                        """)
                .when().put("/api/settings/caps")
                .then().statusCode(200)
                .body("windowMinutes", equalTo(525_600));
    }

    /** An enormous cap is an unreachable limit, not a fault — only the window is bounded above. */
    @Test
    void aVeryLargeSpendCapIsStillAccepted() {
        given().contentType("application/json")
                .body("""
                        {"maxChangedFiles":null,"maxDiffBytes":null,
                         "spendCapMillicents":9000000000000000000,"callCap":null,"windowMinutes":null}
                        """)
                .when().put("/api/settings/caps")
                .then().statusCode(200);
    }

    @Test
    void aMissingBodyIsRejected() {
        given().contentType("application/json")
                .when().put("/api/settings/caps")
                .then().statusCode(400);
    }
}
