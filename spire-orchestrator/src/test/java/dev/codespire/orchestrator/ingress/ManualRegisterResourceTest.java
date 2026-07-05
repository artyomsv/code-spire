package dev.codespire.orchestrator.ingress;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The manual "register PR" endpoint: bad input is rejected; a workspace with no
 * registered provider is 404; a registered provider's credentials are used to
 * fetch the PR (verified against a WireMock Bitbucket).
 */
@QuarkusTest
class ManualRegisterResourceTest {

    @Inject
    ProviderRegistry providers;

    private WireMockServer wm;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void rejectsEmptyBody() {
        given().contentType("application/json").body("{}")
                .when().post("/api/reviews/register").then().statusCode(400);
    }

    @Test
    void rejectsUnparseableUrl() {
        given().contentType("application/json").body(Map.of("url", "not-a-pr-url"))
                .when().post("/api/reviews/register").then().statusCode(400);
    }

    @Test
    void rejectsNonPositivePr() {
        given().contentType("application/json").body(Map.of("workspace", "acme", "slug", "web", "pr", 0))
                .when().post("/api/reviews/register").then().statusCode(400);
    }

    @Test
    void noProviderRegistered_isNotFound() {
        given().contentType("application/json").body(Map.of("workspace", "unregistered-ws", "slug", "web", "pr", 5))
                .when().post("/api/reviews/register").then().statusCode(404);
    }

    @Test
    void registersUsingTheProvidersToken() {
        providers.create(new ProviderInput("CF", "bitbucket-cloud", wm.baseUrl(), "mrx-ws",
                "bearer", null, "provider-tok", "acct", true, List.of()));
        wm.stubFor(get(urlEqualTo("/repositories/mrx-ws/repo/pullrequests/5"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "id": 5, "title": "T", "description": "",
                          "source": { "branch": {"name": "f"}, "commit": {"hash": "abc123"} },
                          "destination": { "branch": {"name": "main"} },
                          "author": { "account_id": "a", "nickname": "n", "display_name": "N" },
                          "links": { "html": {"href": "http://x"} } }
                        """)));

        given().contentType("application/json").body(Map.of("workspace", "mrx-ws", "slug", "repo", "pr", 5))
                .when().post("/api/reviews/register")
                .then().statusCode(200).body("reviewId", equalTo("review::mrx-ws/repo#5"));

        wm.verify(getRequestedFor(urlEqualTo("/repositories/mrx-ws/repo/pullrequests/5"))
                .withHeader("Authorization",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer provider-tok")));
    }
}
