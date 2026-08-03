package dev.codespire.orchestrator.web;

import io.quarkus.test.security.TestSecurity;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;

/**
 * The description is fetched live, so its failures are the SCM's. Each must surface as itself: an
 * empty description would read as "this pull request has no description", which is a different fact.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
// PER_CLASS so the cleanup below can be a plain @Inject-ed instance method — deleting through the
// registry directly avoids depending on the app's own HTTP listener still being reachable by the
// time @AfterAll runs.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewDescriptionResourceTest {

    @Inject
    ProviderRegistry providerRegistry;

    private static WireMockServer scm; // stands in for the SCM; baseUrl points here
    private static boolean providerRegistered; // scm_provider has a unique (type, workspace) constraint
    private static String acmeProviderId; // captured on registration so it can be deleted afterward

    @BeforeAll
    static void startScm() {
        scm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        scm.start();
        scm.stubFor(get(urlEqualTo("/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "account_id": "acct-1", "username": "spire_bot", "display_name": "Spire Bot" }
                        """)));
        scm.stubFor(get(urlEqualTo("/repositories/acme/widgets/pullrequests/1"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "title": "Add the widget catalog", "description": "Implements the widget catalog.",
                          "source": { "branch": {"name": "feature"}, "commit": {"hash": "abc123"} },
                          "destination": { "branch": {"name": "main"} },
                          "author": { "account_id": "a1", "nickname": "alice", "display_name": "Alice" },
                          "links": { "html": {"href": "https://bitbucket.example/acme/widgets/pull-requests/1"} } }
                        """)));
        scm.stubFor(get(urlEqualTo("/repositories/acme/widgets/pullrequests/2"))
                .willReturn(aResponse().withStatus(401)));
    }

    @AfterAll
    void stopScmAndDeleteAcmeProvider() {
        scm.stop();
        if (acmeProviderId != null) {
            providerRegistry.delete(UUID.fromString(acmeProviderId));
        }
    }

    // Registration is deferred to the first test (RestAssured's port isn't wired up during
    // @BeforeAll) and done only once: scm_provider has a unique (type, workspace) constraint,
    // and all tests in this class share the one "acme" provider anyway.
    @BeforeEach
    void registerAcmeProviderOnce() {
        if (providerRegistered) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Acme");
        body.put("type", "bitbucket-cloud");
        body.put("baseUrl", scm.baseUrl());
        body.put("workspace", "acme");
        body.put("authKind", "bearer");
        body.put("secret", "tok-abc");
        body.put("enabled", true);
        body.put("authors", List.of());
        acmeProviderId = given().contentType("application/json").body(body)
                .when().post("/api/providers").then().statusCode(201)
                .extract().path("id");
        providerRegistered = true;
    }

    @Test
    void returnsTheDescriptionOfAKnownPullRequest() {
        when().get("/api/reviews/acme/widgets/1/description")
                .then().statusCode(200)
                .body("description", containsString("Implements"));
    }

    @Test
    void reportsNotFoundWhenTheScmDoesNotKnowThePullRequest() {
        when().get("/api/reviews/acme/widgets/99999/description")
                .then().statusCode(404);
    }

    /**
     * A workspace with no enabled provider must say so. Returning an empty description would read
     * as "this pull request has no description", which is a different fact entirely.
     */
    @Test
    void reportsNoProviderRatherThanAnEmptyDescription() {
        when().get("/api/reviews/unregistered/repo/1/description")
                .then().statusCode(404)
                .body(containsString("No enabled provider"));
    }

    /**
     * A revoked/rotated bot token must surface as a distinct, actionable failure — not the same
     * empty-description shape a genuinely blank pull request would produce.
     */
    @Test
    void reportsRejectedCredentialWhenTheScmReturnsUnauthorized() {
        when().get("/api/reviews/acme/widgets/2/description")
                .then().statusCode(503);
    }
}
