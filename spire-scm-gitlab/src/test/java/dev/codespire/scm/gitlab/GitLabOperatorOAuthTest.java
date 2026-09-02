package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.OAuthApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proving a GitLab account belongs to the operator at the browser.
 *
 * <p>The self-hosted case is the one worth pinning here: the sign-in and the API share a host but
 * not a path, so an operator who fills in only the sign-in base must not have the API fall back to
 * gitlab.com — which would identify them against a completely different instance.
 */
class GitLabOperatorOAuthTest {

    private static WireMockServer server;

    private final GitLabOperatorOAuth oauth = new GitLabOperatorOAuth(new ObjectMapper());

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    /** Only the sign-in base is set, exactly as a self-hosted operator would fill the form. */
    private OAuthApp selfHosted() {
        return new OAuthApp(ScmType.GITLAB, server.baseUrl(), null, "TEST-CLIENT", "TEST-SECRET");
    }

    @Test
    void sendsTheOperatorToTheHostedSignInWhenNoBaseUrlIsSet() {
        OAuthApp hosted = new OAuthApp(ScmType.GITLAB, null, null, "TEST-CLIENT", "TEST-SECRET");

        String url = oauth.authorizeUrl(hosted, "TEST-STATE", "https://spire.example.invalid/cb");

        assertTrue(url.startsWith(GitLabOperatorOAuth.HOSTED + "/oauth/authorize"));
        assertTrue(url.contains("response_type=code"));
        // read_user only -- never `api`, which would grant this deployment the operator's full access.
        assertTrue(url.contains("scope=read_user"));
        assertTrue(url.contains("state=TEST-STATE"));
    }

    /**
     * The API base falls back to the SIGN-IN base, not to the hosted service. Falling back to
     * gitlab.com would identify a self-hosted operator against a stranger's account with the same
     * name — and the sign-in itself would have succeeded, so nothing would look wrong.
     */
    @Test
    void identifiesAgainstTheSameInstanceItSignedInTo() {
        server.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"TEST-TOKEN\"}")));
        server.stubFor(get(urlPathEqualTo("/api/v4/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":40124851,\"username\":\"test-author\",\"name\":\"TEST Author\"}")));

        Author author = oauth.identify(selfHosted(), "TEST-CODE", "https://spire.example.invalid/cb");

        assertEquals("40124851", author.providerUserId());
        assertEquals("test-author", author.username());
    }

    @Test
    void raisesTheAdaptersOwnExceptionWhenTheCodeIsRefused() {
        server.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse().withStatus(401)));

        assertThrows(GitLabApiException.class,
                () -> oauth.identify(selfHosted(), "TEST-CODE", "https://spire.example.invalid/cb"));
    }
}
