package dev.codespire.orchestrator.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * The authorization boundary on the orchestrator (D10 slice 3) — the largest surface, and the one
 * holding the provider registry, the event store and the dead-letter queue.
 *
 * <p>Three rules decide the role matrix, and they are different rules:
 * <ul>
 *   <li><b>Can it spend money or change behaviour?</b> Registering, re-running and replaying all
 *       re-trigger pipeline processing that reaches paid LLM calls, so they are admin even though
 *       two of them look like reads.</li>
 *   <li><b>What does the payload contain?</b> This is the one a mutation-shaped rule misses
 *       entirely. {@code GET /api/dlq} changes nothing and was viewer-readable under the first
 *       rule alone — while returning raw wire records that carry findings quoting source and the
 *       brokered SCM credential.</li>
 *   <li><b>Is it configuration?</b> A viewer reads <em>reviews</em>; how the system is wired is not
 *       theirs to see. This rule supersedes an earlier decision that the registries were
 *       viewer-readable because no secret is ever in the payload. That was true and is still true —
 *       and it turned out to be the wrong test. A registry listing is an inventory of every
 *       repository, endpoint, host and model an operator has connected, which describes the reach
 *       of the deployment whether or not a credential is quoted. "No secrets in the body" answers a
 *       narrower question than "should this reader know this".</li>
 * </ul>
 */
@QuarkusTest
class OperatorAuthTest {

    /**
     * Every configuration read the dashboard's Configure section is built from — one entry per
     * settings screen. Listed here rather than asserted one-by-one so that adding a registry without
     * deciding its role is a failing test rather than an oversight: a new screen whose endpoint is
     * missing from this list is invisible to both tests below.
     */
    private static final String[] CONFIGURATION_READS = {
            "/api/providers",
            "/api/llm-providers",
            "/api/llm-models",
            "/api/context-providers",
            "/api/prompts",
            "/api/settings/review-mode",
            "/api/settings/review",
            "/api/settings/conversation",
            "/api/settings/conversation-level",
            "/api/settings/caps",
    };

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

    /**
     * A viewer reads reviews and nothing about how the system is wired — not the repositories, the
     * models, the context sources, the prompts or the global settings. Refused on the way IN, at the
     * API: hiding the dashboard's Configure section is a courtesy to the operator, never the control.
     */
    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void aViewerCannotReadConfiguration() {
        for (String path : CONFIGURATION_READS) {
            given().when().get(path).then().statusCode(403);
        }
    }

    /** The same reads, for the role that is allowed them — so the rule above is a boundary, not a wall. */
    @Test
    @TestSecurity(user = "dev-operator", roles = {"spire-viewer", "spire-admin"})
    void anAdminCanReadConfiguration() {
        for (String path : CONFIGURATION_READS) {
            given().when().get(path).then().statusCode(200);
        }
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
     * A code-flow callback the framework declined must land on the dashboard, not on a 404.
     *
     * <p>The OIDC mechanism claims the redirect path before routing sees it, but only while a state
     * cookie exists to match the callback against — five minutes by default. A login page left open
     * past that, or submitted twice, produced a 404 <em>immediately after valid credentials were
     * accepted</em>. In dev it was worse than a bare 404: an unmatched path is answered with Quarkus's
     * development "resources overview", which lists every endpoint in the service to any signed-in
     * operator, viewer included.
     *
     * <p>This asserts only the fall-through. That the real callback is still intercepted cannot be
     * shown here — it needs a live state cookie — and was verified against a running service: with a
     * valid cookie and a bad code the mechanism answers 401, so this resource never sees it.
     */
    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void anUnclaimedCallbackReturnsToTheDashboard() {
        given().redirects().follow(false)
                .when().get("/api/auth/callback?state=TEST-stale&code=TEST-stale")
                .then().statusCode(303).header("Location", org.hamcrest.Matchers.endsWith("/"));
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

    // ---- /api/me: what the dashboard needs to know about its own session ----

    /** Reachable without a session, or the interface could never learn that it needs one. */
    @Test
    void meIsReadableAnonymouslyAndReportsNotSignedIn() {
        given().when().get("/api/me").then().statusCode(200)
                .body("authenticated", is(false))
                .body("user", is(""))
                .body("roles", empty());
    }

    @Test
    @TestSecurity(user = "dev-viewer", roles = "spire-viewer")
    void meReportsTheSignedInOperatorAndTheirRoles() {
        given().when().get("/api/me").then().statusCode(200)
                .body("authenticated", is(true))
                .body("user", is("dev-viewer"))
                .body("roles", contains("spire-viewer"));
    }

    /** An operator's unrelated realm roles are not this application's business to report. */
    @Test
    @TestSecurity(user = "dev-operator", roles = {"spire-admin", "some-other-app-role"})
    void meReportsOnlySpireRoles() {
        given().when().get("/api/me").then().statusCode(200)
                .body("roles", contains("spire-admin"));
    }
}
