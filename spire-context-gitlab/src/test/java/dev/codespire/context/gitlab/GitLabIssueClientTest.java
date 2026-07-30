package dev.codespire.context.gitlab;

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

/** The HTTP layer: auth shape, nested-path encoding, status carrying, SSRF posture. */
class GitLabIssueClientTest {

    private static WireMockServer server;
    private static GitLabIssueClient client;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        client = new GitLabIssueClient(
                new GitLabIssueConfig("http://localhost:" + server.port(), "bearer", "TEST-token", Set.of()),
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

    /** A nested namespace must reach the API as one URL-encoded path segment, or it 404s. */
    @Test
    void encodesANestedProjectPathAsASingleSegment() {
        assertEquals("acme%2Ftools%2Fwidgets", GitLabIssueClient.encodePath("acme/tools/widgets"));
    }

    @Test
    void sendsTheTokenAsABearerHeader() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/acme%2Fwidgets/issues/1"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"iid\":1,\"title\":\"Widget spins backwards\"}")));

        JsonNode issue = client.getJson("/api/v4/projects/acme%2Fwidgets/issues/1");

        assertEquals("Widget spins backwards", issue.path("title").asText());
        server.verify(getRequestedFor(urlPathEqualTo("/api/v4/projects/acme%2Fwidgets/issues/1"))
                .withHeader("Authorization", equalTo("Bearer TEST-token")));
    }

    @Test
    void carriesTheStatusSoTheProviderCanSkipA404ButFailOnA401() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/x/issues/404")).willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "application/json").withBody("{}")));
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/x/issues/401")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json").withBody("{}")));

        assertEquals(404, assertThrows(GitLabIssueApiException.class,
                () -> client.getJson("/api/v4/projects/x/issues/404")).status());
        assertEquals(401, assertThrows(GitLabIssueApiException.class,
                () -> client.getJson("/api/v4/projects/x/issues/401")).status());
    }

    /** A 401 body can echo the token that was rejected, so it must not reach the message. */
    @Test
    void neverPutsTheUpstreamBodyIntoAnAuthFailureMessage() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/x/issues/9")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"401 Unauthorized for TEST-token\"}")));

        GitLabIssueApiException thrown = assertThrows(GitLabIssueApiException.class,
                () -> client.getJson("/api/v4/projects/x/issues/9"));

        assertFalse(thrown.getMessage().contains("TEST-token"));
    }

    @Test
    void reportsANonJsonSuccessAsARejectedCredential() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/x/issues/2")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html").withBody("<html>Sign in</html>")));

        assertEquals(200, assertThrows(GitLabIssueApiException.class,
                () -> client.getJson("/api/v4/projects/x/issues/2")).status());
    }

    @Test
    void refusesACrossHostRedirectIntoPrivateAddressSpace() {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/x/issues/3")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "http://127.0.0.1:9/internal")));

        assertThrows(GitLabIssueApiException.class, () -> client.getJson("/api/v4/projects/x/issues/3"));
    }

    @Test
    void rejectsAConfigWhoseAuthKindIsNotBearer() {
        assertThrows(IllegalArgumentException.class,
                () -> new GitLabIssueConfig("https://gitlab.com", "basic", "TEST-token", Set.of()));
    }
}
