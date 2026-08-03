package dev.codespire.orchestrator.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * The authorization boundary on the orchestrator (D10 slice 3) — the largest surface, and the one
 * holding the provider registry, the event store and the dead-letter queue.
 *
 * <p>Two rules decide the role matrix, and they are different rules:
 * <ul>
 *   <li><b>Can it spend money or change behaviour?</b> Registering, re-running and replaying all
 *       re-trigger pipeline processing that reaches paid LLM calls, so they are admin even though
 *       two of them look like reads.</li>
 *   <li><b>What does the payload contain?</b> This is the one a mutation-shaped rule misses
 *       entirely. {@code GET /api/dlq} changes nothing and was viewer-readable under the first
 *       rule alone — while returning raw wire records that carry findings quoting source and the
 *       brokered SCM credential.</li>
 * </ul>
 */
@QuarkusTest
class OperatorAuthTest {

    @Test
    void anUnauthenticatedCallerReachesNothing() {
        given().when().get("/api/reviews").then().statusCode(401);
        given().when().get("/api/providers").then().statusCode(401);
        given().when().get("/api/attention").then().statusCode(401);
    }

    @Test
    void healthStaysPublic() {
        given().when().get("/q/health").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void aViewerCanReadTheDashboard() {
        given().when().get("/api/reviews").then().statusCode(200);
        given().when().get("/api/attention").then().statusCode(200);
    }

    /** Provider metadata is viewer-readable because secrets are never in the payload — only `hasSecret`. */
    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void aViewerCanReadProviderMetadata() {
        given().when().get("/api/providers").then().statusCode(200);
    }

    /**
     * The finding a mutation-shaped role rule could not have caught: a listing that changes nothing
     * but discloses the raw payloads of failed messages.
     */
    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void aViewerCannotReadTheDeadLetterQueue() {
        given().when().get("/api/dlq").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void aViewerCannotChangeConfiguration() {
        given().contentType("application/json").body("{\"mode\":\"observe\"}")
                .when().put("/api/settings/review-mode").then().statusCode(403);
    }

    /** Registering a pull request starts a review, which spends money. */
    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void aViewerCannotRegisterAPullRequest() {
        given().contentType("application/json")
                .body("{\"workspace\":\"TEST-WS\",\"slug\":\"TEST-REPO\",\"pr\":1}")
                .when().post("/api/reviews/register").then().statusCode(403);
    }

    /** ...but may ask which provider a pasted URL belongs to: that parses, and nothing else. */
    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void aViewerMayResolveAPullRequestUrl() {
        given().contentType("application/json")
                .body("{\"url\":\"https://example.invalid/TEST-WS/TEST-REPO/pull/1\"}")
                .when().post("/api/reviews/register/resolve")
                .then().statusCode(org.hamcrest.Matchers.not(403));
    }

    @Test
    @TestSecurity(user = "dev-operator", roles = {"spire-viewer", "spire-admin"})
    void anAdminCanReadTheDeadLetterQueue() {
        given().when().get("/api/dlq").then().statusCode(200);
    }

    /**
     * An authenticated caller holding neither role is refused. Authentication is not authorization:
     * without this, anyone the identity provider knows would reach the dashboard.
     */
    @Test
    @TestSecurity(user = "stranger", roles = "some-other-app-role")
    void anIdentityWithoutASpireRoleIsRefused() {
        given().when().get("/api/reviews").then().statusCode(403);
    }
}
