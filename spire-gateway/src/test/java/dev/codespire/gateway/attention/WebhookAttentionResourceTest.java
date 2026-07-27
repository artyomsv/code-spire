package dev.codespire.gateway.attention;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import dev.codespire.gateway.registry.WebhookRepoInput;
import dev.codespire.gateway.registry.WebhookRepoRegistry;
import dev.codespire.gateway.registry.WebhookRepoSecret;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class WebhookAttentionResourceTest {

    @Inject
    WebhookRepoRegistry registry;

    @Test
    void aRejectingRegistrationIsReportedWithItsTarget() {
        WebhookRepoSecret created = registry.create(
                new WebhookRepoInput("stub", "repo", "TEST-OWNER/TEST-REPO-att", true));
        registry.recordRejection(created.repo().webhookKey(), "bad_signature");

        given().when().get("/api/webhook-repos/attention")
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("code", hasItem("WEBHOOK_DELIVERIES_REJECTED"))
                .body("subject", hasItem("TEST-OWNER/TEST-REPO-att"))
                .body("findAll { it.code == 'WEBHOOK_DELIVERIES_REJECTED' }.severity",
                        everyItem(is("WARNING")));
    }

    /**
     * The literal /attention segment must win over the sibling @Path("/{id}") GET, which parses
     * its argument as a UUID. JAX-RS resolves literal segments ahead of templates, but that is a
     * spec guarantee few readers hold in mind and a refactor could silently reorder it into a
     * 400. This pair is the guard.
     */
    @Test
    void theAttentionPathDoesNotShadowTheByIdPath() {
        WebhookRepoSecret created = registry.create(
                new WebhookRepoInput("stub", "repo", "TEST-OWNER/TEST-REPO-shadow", true));

        given().when().get("/api/webhook-repos/attention").then().statusCode(200);
        given().when().get("/api/webhook-repos/" + created.repo().id()).then().statusCode(200);
    }
}
