package dev.codespire.gateway.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * The authorization boundary on the gateway (D10 slice 1).
 *
 * <p>Authorization stays enabled across the whole suite; the behavioural tests carry a class-level
 * {@code @TestSecurity} admin identity rather than switching the boundary off, so they exercise the
 * real permission policy. This class is where the boundary itself is the subject: it makes the
 * assertions that only hold when a caller has the wrong identity, or none.
 *
 * <p>What each case protects is different in kind:
 * <ul>
 *   <li>The webhook edges must stay <b>public</b>. An SCM has no OIDC token to present, only an HMAC
 *       signature, so authenticating them would break every delivery — the one place where adding
 *       authentication is the defect.</li>
 *   <li>The registry must be <b>closed by default</b>. It holds every repository's webhook secret,
 *       and the permission policy is deny-by-default precisely so a forgotten path fails shut.</li>
 *   <li>A viewer must not reach it. Read access to a registration still exposes the routing key
 *       that addresses the repository.</li>
 * </ul>
 */
@QuarkusTest
class OperatorAuthTest {

    @Test
    void anUnauthenticatedCallerCannotReachTheRegistry() {
        given().when().get("/gw/webhook-repos").then().statusCode(401);
    }

    @Test
    void anUnauthenticatedCallerCannotReachTheAttentionFeed() {
        given().when().get("/gw/webhook-repos/attention").then().statusCode(401);
    }

    /**
     * The inbound edge is the reason this service is internet-facing. It authenticates by signature,
     * not by identity, so the permission policy must permit it explicitly — and an unknown key must
     * still be answered by the ingress (404), never by the security layer (401).
     */
    @Test
    void theWebhookEdgeStaysPublic() {
        given().when().post("/webhooks/github/unknown-key").then().statusCode(404);
    }

    @Test
    void healthStaysPublic() {
        given().when().get("/q/health").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void aViewerCanReadTheAttentionFeed() {
        given().when().get("/gw/webhook-repos/attention").then().statusCode(200);
    }

    /** A registration names the repository and the routing key that addresses it — admin only. */
    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void aViewerCannotReadTheRegistry() {
        given().when().get("/gw/webhook-repos").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "dev-operator", roles = {"spire-viewer", "spire-admin"})
    void anAdminCanReadTheRegistry() {
        given().when().get("/gw/webhook-repos").then().statusCode(200);
    }
}
