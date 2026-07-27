package dev.codespire.gateway.attention;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import dev.codespire.gateway.registry.WebhookRepoInput;
import dev.codespire.gateway.registry.WebhookRepoRegistry;
import dev.codespire.gateway.registry.WebhookRepoSecret;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class WebhookAttentionResourceTest {

    @Inject
    WebhookRepoRegistry registry;

    @Inject
    DataSource dataSource;

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

    /**
     * {@code registry.create()} always mints a secret, so a secret-missing row can only be
     * produced below the registry — a direct insert into {@code webhook_repo} with a blank
     * {@code webhook_secret} (the column is NOT NULL, so blank rather than null; the query
     * behind {@code missingSecretTargets()} treats both the same).
     */
    @Test
    void aSecretMissingRegistrationIsReportedWithItsTarget() throws SQLException {
        String target = "TEST-OWNER/TEST-REPO-nosecret";
        insertSecretlessRegistration(target);

        given().when().get("/api/webhook-repos/attention")
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("find { it.code == 'WEBHOOK_SECRET_MISSING' && it.subject == '" + target + "' }.severity",
                        is("WARNING"))
                .body("find { it.code == 'WEBHOOK_SECRET_MISSING' && it.subject == '" + target + "' }.action",
                        is("/settings/webhooks"));
    }

    /**
     * The resource builds secret-missing rows before rejection rows (see its javadoc); this
     * pins that ordering as behavior rather than an incidental read of the code, so a future
     * reordering of the two loops fails a test instead of only silently reshuffling the bell.
     * Asserts relative order, not exact positions, so it survives a third condition being added.
     */
    @Test
    void secretMissingRowsPrecedeRejectionRows() throws SQLException {
        insertSecretlessRegistration("TEST-OWNER/TEST-REPO-nosecret-order");
        WebhookRepoSecret rejecting = registry.create(
                new WebhookRepoInput("stub", "repo", "TEST-OWNER/TEST-REPO-rejecting-order", true));
        registry.recordRejection(rejecting.repo().webhookKey(), "bad_signature");

        List<String> codes = given().when().get("/api/webhook-repos/attention")
                .then().statusCode(200)
                .extract().jsonPath().getList("code", String.class);

        int firstMissing = codes.indexOf("WEBHOOK_SECRET_MISSING");
        int firstRejecting = codes.indexOf("WEBHOOK_DELIVERIES_REJECTED");
        assertTrue(firstMissing >= 0, "expected a WEBHOOK_SECRET_MISSING row");
        assertTrue(firstRejecting >= 0, "expected a WEBHOOK_DELIVERIES_REJECTED row");
        assertTrue(firstMissing < firstRejecting,
                "expected secret-missing rows to precede rejection rows");
    }

    /** Bypasses {@code registry.create()} (which always mints a secret) with a direct insert. */
    private void insertSecretlessRegistration(String target) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO webhook_repo (id, provider_type, scope, target, webhook_key, "
                    + "webhook_secret, enabled) VALUES ('" + UUID.randomUUID() + "', 'stub', 'repo', '"
                    + target + "', '" + "TEST-key-" + UUID.randomUUID() + "', '', TRUE)");
        }
    }
}
