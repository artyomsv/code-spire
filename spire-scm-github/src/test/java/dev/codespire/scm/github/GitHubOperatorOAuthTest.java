package dev.codespire.scm.github;

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
 * Proving a GitHub account belongs to the operator at the browser.
 *
 * <p>The assertion that matters most is the id: it must be the same value the ingress records as a
 * pull request's author, or the link matches no rows and the operator sees an empty activity screen
 * that looks exactly like having done nothing.
 */
class GitHubOperatorOAuthTest {

    private static WireMockServer server;

    private final GitHubOperatorOAuth oauth = new GitHubOperatorOAuth(new ObjectMapper());

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

    private OAuthApp app() {
        return new OAuthApp(ScmType.GITHUB, server.baseUrl(), server.baseUrl(), "TEST-CLIENT", "TEST-SECRET");
    }

    @Test
    void sendsTheOperatorToTheHostedSignInWhenNoBaseUrlIsSet() {
        OAuthApp hosted = new OAuthApp(ScmType.GITHUB, null, null, "TEST-CLIENT", "TEST-SECRET");

        String url = oauth.authorizeUrl(hosted, "TEST-STATE", "https://spire.example.invalid/cb");

        assertTrue(url.startsWith(GitHubOperatorOAuth.HOSTED_WEB + "/login/oauth/authorize"));
        assertTrue(url.contains("client_id=TEST-CLIENT"));
        assertTrue(url.contains("state=TEST-STATE"));
        // Only the account's own profile. A scope granting repository access would make signing in
        // to prove an identity a reason to hand this deployment far more than it needs.
        assertTrue(url.contains("scope=read%3Auser"));
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fspire.example.invalid%2Fcb"));
    }

    @Test
    void usesTheConfiguredHostForAnEnterpriseInstall() {
        String url = oauth.authorizeUrl(app(), "TEST-STATE", "https://spire.example.invalid/cb");

        assertTrue(url.startsWith(server.baseUrl() + "/login/oauth/authorize"));
    }

    @Test
    void returnsTheStableIdTheIngressRecordsForAPullRequestAuthor() {
        server.stubFor(post(urlPathEqualTo("/login/oauth/access_token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"TEST-TOKEN\"}")));
        server.stubFor(get(urlPathEqualTo("/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":3218389,\"login\":\"test-author\",\"name\":\"TEST Author\"}")));

        Author author = oauth.identify(app(), "TEST-CODE", "https://spire.example.invalid/cb");

        assertEquals("3218389", author.providerUserId());
        assertEquals("test-author", author.username());
        assertEquals("TEST Author", author.displayName());
    }

    @Test
    void raisesTheAdaptersOwnExceptionWhenTheCodeIsRefused() {
        server.stubFor(post(urlPathEqualTo("/login/oauth/access_token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"bad_verification_code\"}")));

        assertThrows(GitHubApiException.class,
                () -> oauth.identify(app(), "TEST-CODE", "https://spire.example.invalid/cb"));
    }
}
