package dev.codespire.gateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * QA gap #3: with no Bitbucket webhook secret configured (the default test
 * profile), the wired ScmIngress bean must reject every /webhooks/bitbucket
 * request through the real HTTP endpoint — proving the DI wiring, not just the
 * POJO logic.
 */
@QuarkusTest
class GatewayStubModeTest {

    @Test
    void stubModeRejectsAllWebhooksOverHttp() {
        RestAssured.given()
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", "sha256=" + "ab".repeat(32))
                .body("{}".getBytes(StandardCharsets.UTF_8))
                .post("/webhooks/bitbucket")
                .then().statusCode(401);
    }
}
