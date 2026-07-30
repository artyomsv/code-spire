package dev.codespire.context.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The HTTP layer: auth shape, status carrying, and the SSRF posture on redirects. */
class GitHubIssueClientTest {

    private static WireMockServer server;
    private static GitHubIssueClient client;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        client = new GitHubIssueClient(
                new GitHubIssueConfig("http://localhost:" + server.port(), "bearer", "TEST-token", Set.of()),
                new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    @Test
    void sendsTheTokenAsABearerHeaderWithTheApiVersion() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"number\":1,\"title\":\"Widget spins backwards\"}")));

        JsonNode issue = client.getJson("/repos/acme/widgets/issues/1");

        assertEquals("Widget spins backwards", issue.path("title").asText());
        server.verify(getRequestedFor(urlPathEqualTo("/repos/acme/widgets/issues/1"))
                .withHeader("Authorization", equalTo("Bearer TEST-token"))
                .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28")));
    }

    @Test
    void carriesTheStatusSoTheProviderCanSkipA404ButFailOnA401() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/404")).willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "application/json").withBody("{}")));
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/401")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json").withBody("{}")));

        assertEquals(404, assertThrows(GitHubIssueApiException.class,
                () -> client.getJson("/repos/acme/widgets/issues/404")).status());
        assertEquals(401, assertThrows(GitHubIssueApiException.class,
                () -> client.getJson("/repos/acme/widgets/issues/401")).status());
    }

    /**
     * A 401 body can echo the token that was rejected, so no upstream body may reach the message an
     * auth failure produces. Statuses that are not credential outcomes may still carry a snippet.
     */
    @Test
    void neverPutsTheUpstreamBodyIntoAnAuthFailureMessage() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/9")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"Bad credentials for TEST-token\"}")));

        GitHubIssueApiException thrown = assertThrows(GitHubIssueApiException.class,
                () -> client.getJson("/repos/acme/widgets/issues/9"));

        assertFalse(thrown.getMessage().contains("TEST-token"));
    }

    /** A non-JSON 2xx means the request landed on a sign-in page: the token was not accepted. */
    @Test
    void reportsANonJsonSuccessAsARejectedCredentialRatherThanAParseError() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/2")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html").withBody("<html>Sign in</html>")));

        GitHubIssueApiException thrown = assertThrows(GitHubIssueApiException.class,
                () -> client.getJson("/repos/acme/widgets/issues/2"));

        assertEquals(200, thrown.status());
    }

    @Test
    void refusesACrossHostRedirectIntoPrivateAddressSpace() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/3")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "http://127.0.0.1:9/internal")));

        assertThrows(GitHubIssueApiException.class, () -> client.getJson("/repos/acme/widgets/issues/3"));
    }

    @Test
    void rejectsAConfigWhoseAuthKindIsNotBearer() {
        assertThrows(IllegalArgumentException.class,
                () -> new GitHubIssueConfig("https://api.github.com", "basic", "TEST-token", Set.of()));
    }
}
