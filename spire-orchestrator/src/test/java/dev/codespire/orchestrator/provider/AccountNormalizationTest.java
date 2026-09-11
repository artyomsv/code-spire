package dev.codespire.orchestrator.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.util.Map;
import java.util.UUID;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-admin"})
class AccountNormalizationTest {
    static WireMockServer server;
    @Inject ProviderClients clients;
    @Inject ProviderRegistry registry;
    @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
    @AfterAll static void stop() { server.stop(); }
    @BeforeEach void reset() { server.resetAll(); }

    private Map<String, Object> input(String type, String role) {
        var body = new java.util.HashMap<String, Object>();
        body.put("name", "Normalization " + UUID.randomUUID());
        body.put("type", type); body.put("baseUrl", server.baseUrl());
        body.put("authKind", "bearer"); body.put("secret", "account-test-token");
        if (role != null) { body.put("role", role); body.put("workspace", "workspace-" + UUID.randomUUID()); }
        return body;
    }

    @Test void atlassianRegistersWithoutAWorkspaceOrRoleAndChecksWithBasicAuth() {
        server.stubFor(get(urlEqualTo("/rest/api/3/myself")).withHeader("Authorization", matching("Basic .*"))
                .willReturn(okJson("{\"accountId\":\"bot-id\",\"displayName\":\"Site Bot\"}")));
        var body = input("atlassian", null);
        body.put("authKind", "basic"); body.put("authUsername", "bot@example.test");
        String id = given().contentType("application/json").body(body).post("/api/providers")
                .then().statusCode(201).body("role", org.hamcrest.Matchers.equalTo("CONTEXT")).body("workspace", nullValue())
                .body("reportedScopes", nullValue()).body("scopesCheckedAt", notNullValue()).extract().path("id");
        // NULL workspaces do not collide; two accounts at one site can coexist.
        given().contentType("application/json").body(body).post("/api/providers").then().statusCode(201);
        var checked = registry.get(UUID.fromString(id)).orElseThrow().scopesCheckedAt();
        given().post("/api/providers/" + id + "/check").then().statusCode(200).body("ok", org.hamcrest.Matchers.equalTo(true));
        assertTrue(registry.get(UUID.fromString(id)).orElseThrow().scopesCheckedAt().isAfter(checked));
        server.stubFor(get(urlEqualTo("/rest/api/3/myself")).willReturn(unauthorized()));
        server.stubFor(get(urlEqualTo("/wiki/rest/api/user/current")).willReturn(notFound()));
        given().post("/api/providers/" + id + "/check").then().statusCode(200).body("ok", org.hamcrest.Matchers.equalTo(false));
        var failed = registry.get(UUID.fromString(id)).orElseThrow();
        assertFalse(failed.lastCheckOk()); assertTrue(failed.enabled()); assertTrue(failed.hasSecret());
    }

    @Test void confluenceOnlySiteCanIdentifyItsAccount() {
        server.stubFor(get(urlEqualTo("/rest/api/3/myself")).willReturn(notFound()));
        server.stubFor(get(urlEqualTo("/wiki/rest/api/user/current"))
                .willReturn(okJson("{\"accountId\":\"wiki-bot\",\"displayName\":\"Wiki Bot\"}")));
        given().contentType("application/json").body(input("atlassian", null)).post("/api/providers")
                .then().statusCode(201).body("botAccountId", org.hamcrest.Matchers.equalTo("wiki-bot"));
    }

    @Test void reportedScopesAreStoredAndAdviceDoesNotBlockFactoryRegistration() {
        server.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"id\":123,\"login\":\"factory-bot\"}")
                .withHeader("X-OAuth-Scopes", "read:org")));
        String id = given().contentType("application/json").body(input("github", "FACTORY")).post("/api/providers")
                .then().statusCode(201).body("reportedScopes", org.hamcrest.Matchers.equalTo("read:org")).extract().path("id");
        assertTrue(ProviderClients.scopesNeedAttention("github", ProviderRole.FACTORY, "read:org"));
        assertTrue(ProviderClients.scopesNeedAttention("github", ProviderRole.REVIEWER, "read:org"));
        given().get("/api/attention").then().statusCode(200).body(containsString("ACCOUNT_SCOPE_ADVICE"));
        server.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"id\":123,\"login\":\"factory-bot\"}")
                .withHeader("X-OAuth-Scopes", "repo, read:org")));
        given().post("/api/providers/" + id + "/check").then().statusCode(200).body("ok", org.hamcrest.Matchers.equalTo(true));
        assertEquals("repo, read:org", registry.get(UUID.fromString(id)).orElseThrow().reportedScopes());
        assertFalse(ProviderClients.scopesNeedAttention("github", ProviderRole.FACTORY, "repo, repo"));
        // public_repo includes writes to public repositories. Private-repository reachability
        // cannot be inferred from this account-wide report (the per-repository check is separate).
        assertFalse(ProviderClients.scopesNeedAttention("github", ProviderRole.FACTORY, "public_repo"));
    }

    @Test void missingScopesStillRegistersAndEndpointPermissionsAreNotTokenScopes() {
        server.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"id\":123,\"login\":\"fine-grained-bot\"}")
                .withHeader("X-Accepted-GitHub-Permissions", "contents=write")));
        given().contentType("application/json").body(input("github", "FACTORY")).post("/api/providers")
                .then().statusCode(201).body("reportedScopes", nullValue()).body("scopesCheckedAt", notNullValue());
        assertFalse(ProviderClients.scopesNeedAttention("github", ProviderRole.FACTORY, null));
    }

    @Test void failedScopeProbePreservesTheStandingReportAndItsObservationTime() {
        server.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"id\":123,\"login\":\"factory-bot\"}")
                .withHeader("X-OAuth-Scopes", "read:org")));
        var body = input("github", "FACTORY");
        String id = given().contentType("application/json").body(body).post("/api/providers")
                .then().statusCode(201).extract().path("id");
        var before = registry.get(UUID.fromString(id)).orElseThrow();
        server.stubFor(get(urlEqualTo("/user")).willReturn(serviceUnavailable()));
        given().post("/api/providers/" + id + "/check").then().statusCode(200)
                .body("ok", org.hamcrest.Matchers.equalTo(false)).body("reportedScopes", org.hamcrest.Matchers.equalTo("read:org"));
        var after = registry.get(UUID.fromString(id)).orElseThrow();
        assertEquals(before.scopesCheckedAt(), after.scopesCheckedAt());
        given().get("/api/attention").then().statusCode(200).body(containsString(body.get("name").toString()));
        // An actual successful response with no header is an observation of unknown scopes.
        server.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"id\":123,\"login\":\"factory-bot\"}")));
        given().post("/api/providers/" + id + "/check").then().statusCode(200).body("reportedScopes", nullValue());
        assertTrue(registry.get(UUID.fromString(id)).orElseThrow().scopesCheckedAt().isAfter(before.scopesCheckedAt()));
    }

    @Test void readsGitLabSelfScopesFromBothSupportedAccountUrlShapes() {
        server.stubFor(get(urlEqualTo("/api/v4/personal_access_tokens/self"))
                .willReturn(okJson("{\"scopes\":[\"read_api\",\"read_repository\"]}")));
        for (String base : java.util.List.of(server.baseUrl(), server.baseUrl() + "/api/v4")) {
            assertEquals("read_api, read_repository", clients.reportedScopes("gitlab", base, "bearer", null, "token", "ws"));
        }
        server.stubFor(get(urlEqualTo("/api/v4/personal_access_tokens/self")).willReturn(forbidden()));
        assertNull(clients.reportedScopes("gitlab", server.baseUrl(), "bearer", null, "token", "ws"));
    }

    @Test void bitbucketFallbackReadsOnlyReportedScopeHeader() {
        server.stubFor(get(urlEqualTo("/user")).willReturn(unauthorized()));
        server.stubFor(get(urlEqualTo("/repositories/ws")).willReturn(okJson("{}").withHeader("x-oauth-scopes", "repository")));
        assertEquals("repository", clients.reportedScopes("bitbucket-cloud", server.baseUrl(), "basic", "bot", "token", "ws"));
        assertTrue(ProviderClients.scopesNeedAttention("bitbucket-cloud", ProviderRole.FACTORY, "repository"));
        assertTrue(ProviderClients.scopesNeedAttention("gitlab", ProviderRole.REVIEWER, ""));
    }

    @Test void duplicateForgeAccountIsAReadableConflict() {
        server.stubFor(get(urlEqualTo("/user")).willReturn(okJson("{\"id\":123,\"login\":\"bot\"}")));
        var body = input("github", "REVIEWER");
        given().contentType("application/json").body(body).post("/api/providers").then().statusCode(201);
        given().contentType("application/json").body(body).post("/api/providers")
                .then().statusCode(409).body(containsString("already exists"));
    }
}
