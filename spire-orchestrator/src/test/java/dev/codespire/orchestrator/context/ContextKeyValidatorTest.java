package dev.codespire.orchestrator.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credential validator had no test file at all, while carrying the whole {@code code} branch:
 * which platform a base URL is read as, the route that platform's probe uses, and — because a raw
 * file carries no account record to parse — an accepted/refused rule made of HTTP statuses rather
 * than a body. A wrong platform guess or an inverted 404 check would have shipped silently
 * (PR 63 QA review).
 *
 * <p>Plain JUnit, not {@code @QuarkusTest}: the class needs one collaborator ({@code mapper}) and an
 * HTTP endpoint, both of which a test can supply directly.
 */
class ContextKeyValidatorTest {

    private static WireMockServer server;
    private static ContextKeyValidator validator;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        validator = new ContextKeyValidator();
        validator.mapper = new ObjectMapper();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    private static String baseUrl() {
        return "http://localhost:" + server.port();
    }

    private static void answer(int status, String contentType, String body) {
        server.stubFor(any(anyUrl()).willReturn(
                aResponse().withStatus(status).withHeader("Content-Type", contentType).withBody(body)));
    }

    // --- platform inference -------------------------------------------------

    @Test
    void aHostNamingItsPlatformIsReadAsThatPlatform() {
        assertEquals("gitlab", ContextKeyValidator.codePlatform("https://gitlab.acme.example"));
        assertEquals("gitlab", ContextKeyValidator.codePlatform("https://GitLab.example.com"));
        assertEquals("bitbucket", ContextKeyValidator.codePlatform("https://api.bitbucket.org/2.0"));
    }

    /**
     * GitHub is the fallback because its own hostname is the least predictable of the three — and a
     * self-managed GitLab whose host does not say "gitlab" lands here too, which is exactly what the
     * Settings base-URL hint warns an operator about.
     */
    @Test
    void anyOtherHostFallsBackToGitHub() {
        assertEquals("github", ContextKeyValidator.codePlatform("https://api.github.com"));
        assertEquals("github", ContextKeyValidator.codePlatform("https://source.acme.example"));
    }

    /** A base URL the JDK cannot parse must pick a branch rather than throw out of the probe. */
    @Test
    void anUnparseableBaseUrlStillResolvesToAPlatform() {
        assertEquals("github", ContextKeyValidator.codePlatform("not a url"));
    }

    @Test
    void eachPlatformProbesItsOwnRawContentRoute() {
        assertTrue(ContextKeyValidator.codeCheckPath("github")
                .startsWith("/repos/codespire-connectivity-check/placeholder/contents/README.md"));
        assertTrue(ContextKeyValidator.codeCheckPath("bitbucket")
                .startsWith("/repositories/codespire-connectivity-check/placeholder/src/main/README.md"));
        // GitLab identifies the project by a percent-encoded path, slashes included.
        assertTrue(ContextKeyValidator.codeCheckPath("gitlab")
                .startsWith("/api/v4/projects/codespire-connectivity-check%2Fplaceholder/repository/files/"));
        assertTrue(ContextKeyValidator.codeCheckPath("gitlab").contains("/raw?ref=main"));
    }

    // --- the code type's accepted/refused rule -------------------------------

    /**
     * The placeholder repository is meant not to exist. Being told so truthfully proves the token was
     * read and accepted — this is the whole signal a raw-content API offers.
     */
    @Test
    void aCodeProbeAnsweredWith404CountsAsAnAcceptedCredential() {
        answer(404, "application/json", "{\"message\":\"Not Found\"}");

        ContextKeyValidator.CheckOutcome outcome =
                validator.check("code", baseUrl(), "bearer", null, "token");

        assertTrue(outcome.ok());
        assertNull(outcome.account(), "a raw-content API has no account record to report");
        assertFalse(outcome.isRejected());
    }

    @Test
    void aCodeProbeAnsweredWithTheFileItselfCountsAsAccepted() {
        answer(200, "text/plain", "# Placeholder\n");

        assertTrue(validator.check("code", baseUrl(), "bearer", null, "token").ok());
    }

    /**
     * The defence the non-{@code code} branch keeps by demanding parseable JSON. An SSO portal or auth
     * proxy answers 200 HTML to every path, the placeholder probe included, so accepting any 2xx made
     * Check go green while every real fetch failed at review time with nothing explaining it.
     */
    @Test
    void aCodeProbeAnsweredWithASignInPageIsNotAnAcceptedCredential() {
        answer(200, "text/html", "<!DOCTYPE html><html><body>Sign in</body></html>");

        ContextKeyValidator.CheckOutcome outcome =
                validator.check("code", baseUrl(), "bearer", null, "token");

        assertFalse(outcome.ok());
        assertTrue(outcome.isRejected(), "a 2xx that is a login page IS a refusal, not an outage");
        assertTrue(outcome.detail().contains("sign-in page"));
    }

    /** Content-Type is not required to give it away — an HTML body served as text is still HTML. */
    @Test
    void aSignInPageWithoutAnHtmlContentTypeIsStillRecognised() {
        answer(200, "text/plain", "<html><body>Sign in</body></html>");

        assertFalse(validator.check("code", baseUrl(), "bearer", null, "token").ok());
    }

    @Test
    void aCodeProbeRefusedWith401IsRejected() {
        answer(401, "application/json", "{\"message\":\"Bad credentials\"}");

        ContextKeyValidator.CheckOutcome outcome =
                validator.check("code", baseUrl(), "bearer", null, "token");

        assertFalse(outcome.ok());
        assertTrue(outcome.isRejected());
    }

    /** A 5xx is inconclusive — the credential was never judged, so it must not be marked bad. */
    @Test
    void aServerErrorIsNotTreatedAsACredentialRejection() {
        answer(503, "text/plain", "upstream unavailable");

        ContextKeyValidator.CheckOutcome outcome =
                validator.check("code", baseUrl(), "bearer", null, "token");

        assertFalse(outcome.ok());
        assertFalse(outcome.isRejected());
    }

    // --- save-time validation ------------------------------------------------

    @Test
    void pingAcceptsACodeCredentialThePlatformAnsweredWith404() {
        answer(404, "application/json", "{}");

        validator.ping("code", baseUrl(), "bearer", null, "token"); // must not throw
    }

    @Test
    void pingRefusesToSaveACodeCredentialThatOnlyReachesASignInPage() {
        answer(200, "text/html", "<html>Sign in</html>");

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> validator.ping("code", baseUrl(), "bearer", null, "token"));

        assertTrue(thrown.getMessage().contains("sign-in page"));
    }

    @Test
    void pingRefusesARejectedCodeCredential() {
        answer(403, "application/json", "{}");

        assertThrows(BadRequestException.class,
                () -> validator.ping("code", baseUrl(), "bearer", null, "token"));
    }

    // --- the unchanged branch, so the code branch cannot be widened by accident ---

    @Test
    void aNonCodeTypeStillNeedsAParseableAccountBody() {
        answer(200, "application/json", "{\"login\":\"bot\",\"name\":\"Review Bot\"}");

        ContextKeyValidator.CheckOutcome outcome =
                validator.check("github-issues", baseUrl(), "bearer", null, "token");

        assertTrue(outcome.ok());
        assertEquals("Review Bot", outcome.account());
    }

    @Test
    void aNonCodeTypeAnsweredWith404IsNotAccepted() {
        answer(404, "application/json", "{}");

        assertFalse(validator.check("github-issues", baseUrl(), "bearer", null, "token").ok(),
                "404 means accepted only for a raw-content probe of a deliberately absent path");
    }

    @Test
    void anUnsupportedTypeIsRefusedOutright() {
        assertThrows(BadRequestException.class,
                () -> validator.check("mystery", baseUrl(), "bearer", null, "token"));
    }
}
