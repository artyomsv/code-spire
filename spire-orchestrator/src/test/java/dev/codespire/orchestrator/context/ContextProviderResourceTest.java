package dev.codespire.orchestrator.context;

import io.quarkus.test.security.TestSecurity;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The /api/context-providers REST layer: create validates the credential against
 * the source (a WireMock /rest/api/2/myself stub), the secret is never returned,
 * an unsupported type / missing username / rejected credential is a 400, and the
 * default can be switched. Mirrors {@link dev.codespire.orchestrator.llm.LlmProviderResourceTest}.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ContextProviderResourceTest {

    private static WireMockServer jira; // stands in for Jira; baseUrl points here
    private static WireMockServer github; // stands in for the GitHub API
    private static WireMockServer gitlab; // stands in for a GitLab instance

    @Inject
    ContextProviderRegistry registry;

    @BeforeAll
    static void startJira() {
        jira = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        jira.start();
        github = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        github.start();
        gitlab = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        gitlab.start();
    }

    @AfterAll
    static void stopJira() {
        jira.stop();
        github.stop();
        gitlab.stop();
    }

    @BeforeEach
    void reset() {
        registry.list().forEach(v -> registry.delete(UUID.fromString(v.id())));
        jira.resetAll();
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"accountId\": \"abc\", \"emailAddress\": \"bot@acme.com\" }")));
        github.resetAll();
        github.stubFor(get(urlEqualTo("/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"login\": \"spire-bot\", \"name\": \"Spire Bot\" }")));
        gitlab.resetAll();
        gitlab.stubFor(get(urlEqualTo("/api/v4/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"id\": 1, \"username\": \"spire-bot\", \"name\": \"Spire Bot\" }")));
    }

    private static Map<String, Object> body(Object secret) {
        var m = new java.util.HashMap<String, Object>();
        m.put("name", "Acme Jira");
        m.put("type", "jira");
        m.put("baseUrl", jira.baseUrl()); // validator appends /rest/api/2/myself -> hits the stub
        m.put("authKind", "basic");
        m.put("username", "bot@acme.com");
        if (secret != null) {
            m.put("secret", secret);
        }
        return m;
    }

    @Test
    void createsValidatesTheCredentialAndStoresIt() {
        given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers")
                .then().statusCode(201)
                .body("hasSecret", is(true))
                .body("enabled", is(true))        // every enabled provider participates — no default concept
                .body("secret", nullValue())      // secret never returned
                .body("id", notNullValue());
    }

    @Test
    void rejectsACredentialTheProviderDoesNotAccept() {
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse().withStatus(401)));
        given().contentType("application/json").body(body("bad-token"))
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void rejectsAnUnsupportedType() {
        var b = body("jira-token");
        b.put("type", "notion"); // not a supported context provider type
        given().contentType("application/json").body(b)
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void requiresASecretOnCreate() {
        given().contentType("application/json").body(body(null))
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void basicAuthRequiresAUsername() {
        var b = body("jira-token");
        b.remove("username");
        given().contentType("application/json").body(b)
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void acceptsABearerProviderWithoutUsername() {
        var b = body("pat-token");
        b.put("authKind", "bearer");
        b.remove("username");
        given().contentType("application/json").body(b)
                .when().post("/api/context-providers")
                .then().statusCode(201).body("authKind", is("bearer")).body("hasSecret", is(true));
    }

    @Test
    void listNeverReturnsTheSecret() {
        given().contentType("application/json").body(body("jira-secret"))
                .when().post("/api/context-providers").then().statusCode(201);
        when().get("/api/context-providers")
                .then().statusCode(200)
                .body("[0].hasSecret", is(true))
                .body("[0].secret", nullValue());
    }

    @Test
    void multipleProvidersCoexistWithoutADefault() {
        given().contentType("application/json").body(body("t-1"))
                .when().post("/api/context-providers").then().statusCode(201);
        given().contentType("application/json").body(body("t-2"))
                .when().post("/api/context-providers").then().statusCode(201);

        // Both remain enabled and non-default — every enabled provider is brokered to the worker.
        when().get("/api/context-providers")
                .then().statusCode(200)
                .body("size()", is(2))
                .body("isDefault", org.hamcrest.Matchers.everyItem(is(false)))
                .body("enabled", org.hamcrest.Matchers.everyItem(is(true)));
    }

    @Test
    void updateWithoutASecretKeepsTheStoredOne() {
        String id = given().contentType("application/json").body(body("keep-me"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(body(null)) // no secret -> no re-validation
                .when().put("/api/context-providers/" + id)
                .then().statusCode(200).body("hasSecret", is(true));
    }

    @Test
    void deleteRemovesTheProvider() {
        String id = given().contentType("application/json").body(body("t-x"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().when().delete("/api/context-providers/" + id).then().statusCode(204);
        when().get("/api/context-providers/" + id).then().statusCode(404);
    }

    @Test
    void checkReportsConnectedWithTheTokenOwner() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().when().post("/api/context-providers/" + id + "/check")
                .then().statusCode(200)
                .body("ok", is(true))
                .body("account", is("bot@acme.com")); // from the /myself stub (emailAddress fallback)
    }

    @Test
    void checkReportsFailureWhenTheTokenIsNoLongerAccepted() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        // token later revoked at the source
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse().withStatus(401)));
        given().when().post("/api/context-providers/" + id + "/check")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("account", nullValue())
                .body("detail", notNullValue());
    }

    @Test
    void createIsRejectedWhenValidationHitsASignInPage() {
        // A 200 that is HTML (an SSO/login redirect) must NOT pass validation.
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody("<!DOCTYPE html><html><head><title>Log in</title></head></html>")));
        given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void checkReportsFailureWhenJiraReturnsASignInPage() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        // token later starts bouncing to a 200 HTML sign-in page
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody("<!DOCTYPE html><html>Log in</html>")));
        given().when().post("/api/context-providers/" + id + "/check")
                .then().statusCode(200)
                .body("ok", is(false))
                .body("detail", notNullValue());
    }

    // ---- create/update: a successful save is itself a passing check --------------------------
    //
    // validator.ping(...) is an authoritative probe: it throws and 400s the save if the
    // credential is bad, so a successful save already proved the credential works. Neither
    // create nor update used to record that, so a rejected credential stayed rejected even
    // after the operator pasted a working one and saved successfully.

    @Test
    void createRecordsAPassingCheck() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().when().get("/api/context-providers/" + id)
                .then().statusCode(200).body("lastCheckOk", is(true)).body("lastCheckError", nullValue());
    }

    /** The negative case that protects the decision: silence must not read as success. */
    @Test
    void anUpdateWithNoNewSecretDoesNotClearARejectedCredential() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse().withStatus(401)));
        given().when().post("/api/context-providers/" + id + "/check").then().statusCode(200).body("ok", is(false));

        // Re-stub success so an accidental re-validation on update would clear the rejection
        // (this is exactly the branch that must NOT run without a new secret).
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"accountId\": \"abc\", \"emailAddress\": \"bot@acme.com\" }")));
        given().contentType("application/json").body(body(null)) // no secret -> keeps the stored one
                .when().put("/api/context-providers/" + id).then().statusCode(200);

        // An update that never touched the credential must not clear its rejection.
        given().when().get("/api/context-providers/" + id)
                .then().statusCode(200).body("lastCheckOk", is(false));
    }

    /** The positive counterpart: a genuine re-validation does clear the rejection. */
    @Test
    void anUpdateWithANewSecretClearsARejectedCredential() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse().withStatus(401)));
        given().when().post("/api/context-providers/" + id + "/check").then().statusCode(200).body("ok", is(false));

        jira.stubFor(get(urlEqualTo("/rest/api/2/myself"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"accountId\": \"abc\", \"emailAddress\": \"bot@acme.com\" }")));
        given().contentType("application/json").body(body("new-secret"))
                .when().put("/api/context-providers/" + id).then().statusCode(200);

        given().when().get("/api/context-providers/" + id)
                .then().statusCode(200).body("lastCheckOk", is(true)).body("lastCheckError", nullValue());
    }

    // ---- check: only a genuine auth rejection may write FALSE ---------------------------------
    //
    // An unreachable provider or a 5xx is inconclusive, not proof the credential is bad — writing
    // FALSE for either would light up the attention panel for a transient outage that fixing the
    // network could never clear.

    @Test
    void checkOnAnUnreachableProviderDoesNotWriteFalseFromNeverChecked() {
        // A create through the REST endpoint always records a passing check now (the create-records-
        // a-check fix above), so reaching a genuinely never-checked row means going around it via the
        // registry directly — exactly what ProviderCheckRecordTest.created() does for the SCM registry.
        WireMockServer temp = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        temp.start();
        String baseUrl = temp.baseUrl();
        temp.stop(); // unreachable from the very first check

        ContextProviderView view = registry.create(new ContextProviderInput("Acme Jira Temp", "jira",
                baseUrl, "basic", "bot@acme.com", "jira-token", null, true, false));

        given().when().post("/api/context-providers/" + view.id() + "/check")
                .then().statusCode(200).body("ok", is(false));
        given().when().get("/api/context-providers/" + view.id())
                .then().statusCode(200).body("lastCheckOk", nullValue());
    }

    @Test
    void checkFailureFromA5xxDoesNotClearAPriorPass() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().when().post("/api/context-providers/" + id + "/check").then().statusCode(200).body("ok", is(true));

        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse().withStatus(500)));
        given().when().post("/api/context-providers/" + id + "/check")
                .then().statusCode(200).body("ok", is(false));

        given().when().get("/api/context-providers/" + id)
                .then().statusCode(200).body("lastCheckOk", is(true));
    }

    @Test
    void checkFailureFromAnAuthRejectionDoesWriteFalse() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse().withStatus(401)));

        given().when().post("/api/context-providers/" + id + "/check").then().statusCode(200).body("ok", is(false));

        given().when().get("/api/context-providers/" + id)
                .then().statusCode(200).body("lastCheckOk", is(false));
    }

    /**
     * A sign-in page is also a genuine rejection, just expressed as 200-HTML instead of a status
     * code: {@code ping()} already blocks the save for exactly this response, so {@code check()}
     * must record it as a rejection the same way, not leave it inconclusive like a 5xx.
     */
    @Test
    void checkFailureFromASignInPageDoesWriteFalse() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        // token later starts bouncing to a 200 HTML sign-in page (an SSO/login redirect)
        jira.stubFor(get(urlEqualTo("/rest/api/2/myself")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody("<!DOCTYPE html><html>Log in</html>")));

        given().when().post("/api/context-providers/" + id + "/check").then().statusCode(200).body("ok", is(false));

        given().when().get("/api/context-providers/" + id)
                .then().statusCode(200).body("lastCheckOk", is(false));
    }

    @Test
    void previewResolvesABareNumberViaProjectKeysAndReturnsTheItem() {
        var b = body("jira-token");
        b.put("projectKeys", "ACME");
        String id = given().contentType("application/json").body(b)
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        jira.stubFor(get(urlPathEqualTo("/rest/api/2/issue/ACME-12345")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\":\"ACME-12345\",\"fields\":{"
                        + "\"summary\":\"Widget bug\",\"status\":{\"name\":\"Open\"},\"issuetype\":{\"name\":\"Bug\"}}}")));

        given().contentType("application/json").body(java.util.Map.of("text", "12345"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200)
                .body("status", is("OK"))
                .body("keys[0]", is("ACME-12345"))
                .body("items[0].kind", is("JIRA_TICKET"))
                .body("items[0].title", is("ACME-12345 — Widget bug"));
    }

    @Test
    void previewIsEmptyForABareNumberWithoutProjectKeys() {
        String id = given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(java.util.Map.of("text", "12345"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200).body("status", is("EMPTY")).body("detail", notNullValue());
    }

    /** A Confluence provider body pointing at the same WireMock (validator appends /rest/api/user/current). */
    private static Map<String, Object> confluenceBody(Object secret) {
        var m = new java.util.HashMap<String, Object>();
        m.put("name", "Acme Confluence");
        m.put("type", "confluence");
        m.put("baseUrl", jira.baseUrl());
        m.put("authKind", "basic");
        m.put("username", "bot@acme.com");
        if (secret != null) {
            m.put("secret", secret);
        }
        return m;
    }

    @Test
    void createsAConfluenceProviderValidatingAgainstUserCurrent() {
        jira.stubFor(get(urlEqualTo("/rest/api/user/current"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"accountId\": \"abc\", \"displayName\": \"Bot Account\" }")));
        given().contentType("application/json").body(confluenceBody("conf-token"))
                .when().post("/api/context-providers")
                .then().statusCode(201)
                .body("type", is("confluence"))
                .body("hasSecret", is(true))
                .body("secret", nullValue());
    }

    @Test
    void previewResolvesAConfluencePageUrlAndReturnsTheItem() {
        jira.stubFor(get(urlEqualTo("/rest/api/user/current"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"displayName\": \"Bot Account\" }")));
        String id = given().contentType("application/json").body(confluenceBody("conf-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        jira.stubFor(get(urlPathEqualTo("/rest/api/content/999")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"999\",\"title\":\"Design Doc\","
                        + "\"body\":{\"storage\":{\"value\":\"<p>the plan</p>\"}}}")));

        given().contentType("application/json")
                .body(java.util.Map.of("text", jira.baseUrl() + "/spaces/ENG/pages/999/Design-Doc"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200)
                .body("status", is("OK"))
                .body("keys[0]", is("999"))
                .body("items[0].kind", is("CONFLUENCE_PAGE"))
                .body("items[0].title", is("Design Doc"));
    }

    @Test
    void confluencePreviewIsEmptyWhenNoPageIsFound() {
        jira.stubFor(get(urlEqualTo("/rest/api/user/current"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"displayName\": \"Bot Account\" }")));
        String id = given().contentType("application/json").body(confluenceBody("conf-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(java.util.Map.of("text", "not a page reference"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200).body("status", is("EMPTY")).body("detail", notNullValue());
    }

    @Test
    void rejectsAPrivateBaseUrlIsRelaxedInTest() {
        // %test sets allow-insecure-provider-urls=true, so localhost passes — the create
        // succeeds against the WireMock (guard behavior is covered by ProviderUrlValidationTest).
        given().contentType("application/json").body(body("jira-token"))
                .when().post("/api/context-providers").then().statusCode(equalTo(201));
    }

    // ---- github-issues / gitlab-issues: bearer-only, and preview through the real REST surface ----

    private static Map<String, Object> githubBody(Object secret) {
        var m = new java.util.HashMap<String, Object>();
        m.put("name", "Acme GitHub Issues");
        m.put("type", "github-issues");
        m.put("baseUrl", github.baseUrl());
        m.put("authKind", "bearer");
        if (secret != null) {
            m.put("secret", secret);
        }
        return m;
    }

    private static Map<String, Object> gitlabBody(Object secret) {
        var m = new java.util.HashMap<String, Object>();
        m.put("name", "Acme GitLab Issues");
        m.put("type", "gitlab-issues");
        m.put("baseUrl", gitlab.baseUrl());
        m.put("authKind", "bearer");
        if (secret != null) {
            m.put("secret", secret);
        }
        return m;
    }

    /**
     * The spec requires basic auth to be refused when the row is SAVED, not merely when the worker
     * later builds a client from it. Without this the operator gets no feedback at all: the save
     * succeeds — the credential ping can even pass, since GitHub accepts a PAT as a Basic password —
     * and the failure surfaces later as a broken context step or a raw 500 from preview.
     *
     * <p>Each body points at ITS OWN WireMock (with a valid ping stub and a username, so every other
     * validation passes) — pointing both at one shared stubless server would 400 from an unrelated
     * 404 in {@code ping()} regardless of whether this guard exists, proving nothing.
     */
    @Test
    void refusesBasicAuthForTheIssueTypes() {
        var githubBasic = githubBody("TEST-token");
        githubBasic.put("authKind", "basic");
        githubBasic.put("username", "bot@acme.com");
        given().contentType("application/json").body(githubBasic)
                .when().post("/api/context-providers").then().statusCode(400);

        var gitlabBasic = gitlabBody("TEST-token");
        gitlabBasic.put("authKind", "basic");
        gitlabBasic.put("username", "bot@acme.com");
        given().contentType("application/json").body(gitlabBasic)
                .when().post("/api/context-providers").then().statusCode(400);
    }

    @Test
    void gitHubPreviewOfABareReferenceReturnsTheGuidance() {
        String id = given().contentType("application/json").body(githubBody("TEST-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(java.util.Map.of("text", "#123"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200)
                .body("status", is("EMPTY"))
                .body("detail", containsString("owner/repo#123"));
    }

    @Test
    void gitHubPreviewResolvesAQualifiedReferenceAndReturnsTheItem() {
        String id = given().contentType("application/json").body(githubBody("TEST-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        github.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/42")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"number\":42,\"title\":\"Widget crashes on save\",\"state\":\"open\","
                        + "\"body\":\"Steps to reproduce...\","
                        + "\"html_url\":\"https://github.invalid/acme/widgets/issues/42\"}")));

        given().contentType("application/json").body(java.util.Map.of("text", "acme/widgets#42"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200)
                .body("status", is("OK"))
                .body("items[0].kind", is("ISSUE"))
                .body("items[0].title", containsString("Widget crashes on save"));
    }

    @Test
    void gitLabPreviewOfABareReferenceReturnsTheGuidance() {
        String id = given().contentType("application/json").body(gitlabBody("TEST-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(java.util.Map.of("text", "#123"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200)
                .body("status", is("EMPTY"))
                .body("detail", containsString("group/project#123"));
    }

    /**
     * GitLab spells three objects with three sigils and only issues have a qualified short form, so
     * one guidance message cannot be right for all three. A live pass typed {@code !3} and was told
     * to write {@code group/project#123} — advice for a different object.
     */
    @Test
    void gitLabPreviewOfABareMergeRequestNamesTheMergeRequestForm() {
        String id = given().contentType("application/json").body(gitlabBody("TEST-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(java.util.Map.of("text", "!3"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200)
                .body("status", is("EMPTY"))
                .body("detail", containsString("merge_requests"))
                .body("detail", not(containsString("group/project#123")));
    }

    /**
     * An epic is group-scoped and has no qualified form at all, so pointing the operator at
     * {@code group/project#123} sends them somewhere that cannot work. Only the group URL resolves.
     */
    @Test
    void gitLabPreviewOfABareEpicNamesTheGroupEpicUrl() {
        String id = given().contentType("application/json").body(gitlabBody("TEST-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(java.util.Map.of("text", "&7"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200)
                .body("status", is("EMPTY"))
                .body("detail", containsString("epics"))
                .body("detail", not(containsString("group/project#123")));
    }

    /**
     * A reference the provider recognised, attempted and got nothing for used to come back as EMPTY
     * with a null detail — indistinguishable from "the token cannot see it". Only the BARE case
     * explained itself.
     */
    @Test
    void previewExplainsWhenARecognisedReferenceResolvesToNothing() {
        String id = given().contentType("application/json").body(githubBody("TEST-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        github.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/404"))
                .willReturn(aResponse().withStatus(404)));

        given().contentType("application/json").body(java.util.Map.of("text", "acme/widgets#404"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200)
                .body("status", is("EMPTY"))
                .body("detail", notNullValue())
                .body("detail", containsString("acme/widgets#404"));
    }

    @Test
    void gitLabPreviewResolvesAQualifiedReferenceAndReturnsTheItem() {
        String id = given().contentType("application/json").body(gitlabBody("TEST-token"))
                .when().post("/api/context-providers").then().statusCode(201).extract().path("id");
        gitlab.stubFor(get(urlPathEqualTo("/api/v4/projects/acme%2Fwidgets/issues/42")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"iid\":42,\"title\":\"Widget crashes on save\",\"state\":\"opened\","
                        + "\"description\":\"Steps to reproduce...\","
                        + "\"web_url\":\"https://gitlab.invalid/acme/widgets/-/issues/42\"}")));

        given().contentType("application/json").body(java.util.Map.of("text", "acme/widgets#42"))
                .when().post("/api/context-providers/" + id + "/preview")
                .then().statusCode(200)
                .body("status", is("OK"))
                .body("items[0].kind", is("ISSUE"))
                .body("items[0].title", containsString("Widget crashes on save"));
    }
}
