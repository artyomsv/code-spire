package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * The pool's operator surface (FR-F12).
 *
 * <p>Admin by ADR-022's THIRD rule rather than its first: this is configuration, so even the listing
 * is admin — a pool listing is an inventory of every model endpoint this deployment reaches, which is
 * the argument that made every other registry's reads admin-only.
 */
@QuarkusTest
class HarnessCredentialResourceTest {

    private static final String SECRET = "TEST-agent-key-never-returned";

    private static String body(String label) {
        return """
                {"label":"%s","type":"openai","baseUrl":"https://api.openai.com","apiKey":"%s"}
                """.formatted(label, SECRET);
    }

    private static String added() {
        return given().contentType("application/json").body(body("TEST-pool-res-" + UUID.randomUUID()))
                .when().post("/api/harness-credentials")
                .then().statusCode(201)
                .extract().path("id");
    }

    /**
     * The key goes in and never comes back, and that is a property of the view type rather than of
     * remembering to strip it — {@code MemberView} has no field for one.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aKeyIsWriteOnly() {
        added();

        given().when().get("/api/harness-credentials")
                .then().statusCode(200)
                .body(not(containsString(SECRET)));
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aMemberWithoutAKeyIsRefused() {
        given().contentType("application/json")
                .body("{\"label\":\"TEST-pool-nokey\",\"type\":\"openai\",\"baseUrl\":\"https://x.invalid\"}")
                .when().post("/api/harness-credentials")
                .then().statusCode(400).body(containsString("apiKey"));
    }

    /**
     * Disabling a member must not destroy what it paid for.
     *
     * <p>A {@code factory_run} row holds its member by foreign key, so a hard delete would take a
     * finished run's attribution with it — the same call ADR-024 made when a delete button turned out
     * to be destroying a charge ledger.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aMemberIsDisabledRatherThanDeleted() {
        String id = added();

        given().when().delete("/api/harness-credentials/" + id).then().statusCode(204);
        // Still listed, so the row an attribution points at is still there -- AND actually disabled.
        // Asserting only the listing let a mutation that stopped setting enabled=false pass.
        given().when().get("/api/harness-credentials")
                .then().statusCode(200)
                .body(containsString(id))
                .body("find { it.id == '" + id + "' }.enabled", org.hamcrest.Matchers.is(false));

        // And it comes back, because disabling is not deletion.
        given().when().post("/api/harness-credentials/" + id + "/enable").then().statusCode(204);
        given().when().get("/api/harness-credentials")
                .then().statusCode(200)
                .body("find { it.id == '" + id + "' }.enabled", org.hamcrest.Matchers.is(true));
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void clearingARejectionOnAHealthyMemberIsNotFound() {
        // The operator action reports that it found nothing to clear, rather than answering success
        // for a member that was never refused.
        String id = added();

        given().when().post("/api/harness-credentials/" + id + "/clear-rejection")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void anUnknownIdIsNotFoundAndAMalformedOneIsABadRequest() {
        given().when().delete("/api/harness-credentials/" + UUID.randomUUID()).then().statusCode(404);
        given().when().delete("/api/harness-credentials/not-a-uuid").then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "spire-viewer")
    void aViewerMayNotEvenListThePool() {
        // Configuration, so the READ is admin too: the listing names every model endpoint this
        // deployment reaches, which is an inventory rather than an absence of secrets.
        given().when().get("/api/harness-credentials").then().statusCode(403);
    }
}
