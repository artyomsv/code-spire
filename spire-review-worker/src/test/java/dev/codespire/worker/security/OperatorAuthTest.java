package dev.codespire.worker.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * The authorization boundary on the worker (D10 slice 2).
 *
 * <p>The worker's only browser-facing endpoint serves a review's assembled context — issue and page
 * text pulled from the sources a review consulted, which quotes material from systems the operator
 * may not otherwise expose. It is readable by a viewer, but not by nobody.
 *
 * <p>Health stays public so an orchestrator or a container runtime can probe a service it has no
 * identity for.
 */
@QuarkusTest
class OperatorAuthTest {

    private static final String CONTEXT = "/wk/review-context/TEST-WS/TEST-REPO/1";

    @Test
    void anUnauthenticatedCallerCannotReadAssembledContext() {
        given().when().get(CONTEXT).then().statusCode(401);
    }

    @Test
    void healthStaysPublic() {
        given().when().get("/q/health").then().statusCode(200);
    }

    /**
     * A viewer is enough here, unlike the gateway's registry. The context is what the review already
     * showed the operator on the detail page; it carries no credential and no routing key.
     */
    @Test
    @TestSecurity(user = "test-viewer", roles = "spire-viewer")
    void aViewerCanReadAssembledContext() {
        given().when().get(CONTEXT).then().statusCode(200);
    }

    /**
     * An authenticated caller with neither role is still refused. Authentication is not authorization
     * — anyone the identity provider knows would otherwise reach it.
     */
    @Test
    @TestSecurity(user = "stranger", roles = "some-other-app-role")
    void anIdentityWithoutASpireRoleIsRefused() {
        given().when().get(CONTEXT).then().statusCode(403);
    }
}
