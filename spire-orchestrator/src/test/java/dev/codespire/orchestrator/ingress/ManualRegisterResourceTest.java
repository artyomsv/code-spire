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
                "bearer", null, "provider-tok", "acct", true, List.of(), null, null));
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

    @Test
    void registersGitLabMergeRequestByUrlWithNestedGroup() {
        // workspace = top group; slug = the rest of the nested namespace + project.
        providers.create(new ProviderInput("GL", "gitlab", wm.baseUrl(), "grp",
                "bearer", null, "gl-tok", "botid", true, List.of(), null, null));
        // The project path is addressed URL-encoded (group/subgroup/project -> one segment).
        wm.stubFor(get(urlEqualTo("/projects/grp%2Fsub%2Fproj/merge_requests/9"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "iid": 9, "title": "T", "description": "",
                          "source_branch": "f", "target_branch": "main",
                          "diff_refs": { "base_sha": "b", "start_sha": "s", "head_sha": "h" },
                          "author": { "id": 1, "username": "n", "name": "N" },
                          "web_url": "http://x" }
                        """)));

        given().contentType("application/json")
                .body(Map.of("url", wm.baseUrl() + "/grp/sub/proj/-/merge_requests/9"))
                .when().post("/api/reviews/register")
                .then().statusCode(200).body("reviewId", equalTo("review::grp/sub/proj#9"));

        wm.verify(getRequestedFor(urlEqualTo("/projects/grp%2Fsub%2Fproj/merge_requests/9"))
                .withHeader("Authorization",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer gl-tok")));
    }

    @Test
    void resolveParsesGitLabUrlAndReportsTheRegisteredProvider() {
        providers.create(new ProviderInput("GL", "gitlab", wm.baseUrl(), "grp2",
                "bearer", null, "gl-tok", "botid", true, List.of(), null, null));
        // No SCM call — the resolve endpoint only parses + looks up the provider.
        given().contentType("application/json")
                .body(Map.of("url", "https://gitlab.com/grp2/sub/proj/-/merge_requests/9"))
                .when().post("/api/reviews/register/resolve")
                .then().statusCode(200)
                .body("workspace", equalTo("grp2"))
                .body("slug", equalTo("sub/proj"))
                .body("pr", equalTo(9))
                .body("providerRegistered", equalTo(true))
                .body("providerType", equalTo("gitlab"))
                .body("providerName", equalTo("GL"));
    }

    @Test
    void resolveReportsNoProviderForAnUnregisteredWorkspace() {
        given().contentType("application/json")
                .body(Map.of("url", "https://github.com/nobody-here/repo/pull/3"))
                .when().post("/api/reviews/register/resolve")
                .then().statusCode(200)
                .body("workspace", equalTo("nobody-here"))
                .body("slug", equalTo("repo"))
                .body("pr", equalTo(3))
                .body("providerRegistered", equalTo(false));
    }

    @Test
    void resolveRejectsAnUnparseableUrl() {
        given().contentType("application/json").body(Map.of("url", "not-a-pr-url"))
                .when().post("/api/reviews/register/resolve").then().statusCode(400);
    }
}
