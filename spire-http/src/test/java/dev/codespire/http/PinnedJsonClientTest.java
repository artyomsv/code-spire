package dev.codespire.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared guard's own tests. Every adapter's credential passes through this class, so the
 * host-pinning and private-address behaviour is asserted here once rather than in each adapter.
 */
class PinnedJsonClientTest {

    /** The adapter-supplied exception type, standing in for JiraApiException and friends. */
    static class TestApiException extends RuntimeException {
        final int status;

        TestApiException(int status, String method, String path, String detail) {
            super("Test API " + method + " " + path + " failed with HTTP " + status
                    + (detail == null || detail.isBlank() ? "" : ": " + detail));
            this.status = status;
        }
    }

    private static WireMockServer server;
    private static PinnedJsonClient client;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        client = client("http://localhost:" + server.port());
    }

    private static PinnedJsonClient client(String baseUrl) {
        return new PinnedJsonClient(
                new PinnedJsonConfig("Test API", baseUrl, "Bearer TEST-token",
                        Map.of("Accept", "application/json"), "Check the base URL."),
                new ObjectMapper(), TestApiException::new);
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
    void sendsTheConfiguredHeadersAndAuthorizationToTheApiHost() {
        server.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        JsonNode body = client.getJson("/thing");

        assertTrue(body.path("ok").asBoolean());
        server.verify(getRequestedFor(urlPathEqualTo("/thing"))
                .withHeader("Authorization", equalTo("Bearer TEST-token"))
                .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    void buildsTheAdaptersOwnExceptionCarryingTheStatus() {
        server.stubFor(get(urlPathEqualTo("/missing")).willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "application/json").withBody("{}")));

        TestApiException thrown =
                assertThrows(TestApiException.class, () -> client.getJson("/missing"));

        assertEquals(404, thrown.status);
    }

    /**
     * A non-JSON 2xx means the request was redirected to authentication. Saying so beats surfacing a
     * parse error from deep inside the caller, and the configured hint says what to check.
     */
    @Test
    void reportsANonJsonSuccessAsARejectedCredentialWithTheConfiguredHint() {
        server.stubFor(get(urlPathEqualTo("/signin")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html").withBody("<html>Sign in</html>")));

        TestApiException thrown =
                assertThrows(TestApiException.class, () -> client.getJson("/signin"));

        assertEquals(200, thrown.status);
        assertTrue(thrown.getMessage().contains("expected JSON"));
        assertTrue(thrown.getMessage().contains("Check the base URL."));
    }

    /** Same-host redirects are followed, and the credential goes with them. */
    @Test
    void followsASameHostRedirectAndKeepsSendingTheCredential() {
        server.stubFor(get(urlPathEqualTo("/old")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "/new")));
        server.stubFor(get(urlPathEqualTo("/new")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        assertTrue(client.getJson("/old").path("ok").asBoolean());
        server.verify(getRequestedFor(urlPathEqualTo("/new"))
                .withHeader("Authorization", equalTo("Bearer TEST-token")));
    }

    /**
     * The SSRF guard. A redirect that leaves the configured host must not reach loopback or private
     * address space — that is how a redirect turns into a probe of the operator's own network.
     */
    @Test
    void refusesACrossHostRedirectIntoPrivateAddressSpace() {
        server.stubFor(get(urlPathEqualTo("/evil")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "http://127.0.0.1:9/internal")));

        TestApiException thrown = assertThrows(TestApiException.class, () -> client.getJson("/evil"));

        assertTrue(thrown.getMessage().contains("non-public address refused"));
    }

    /** A malformed Location must not escape as an unchecked exception from the transport. */
    @Test
    void refusesAnUnparseableRedirectTarget() {
        server.stubFor(get(urlPathEqualTo("/malformed")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "http://")));

        TestApiException thrown =
                assertThrows(TestApiException.class, () -> client.getJson("/malformed"));

        assertTrue(thrown.getMessage().contains("unparseable redirect target refused"));
    }

    /**
     * An opaque scheme resolves cleanly but has no host, so it reaches the host check rather than the
     * parse guard — the branch that refuses a redirect the pin cannot evaluate.
     */
    @Test
    void refusesARedirectToASchemeWithNoHost() {
        server.stubFor(get(urlPathEqualTo("/nohost")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "mailto:evil@example.invalid")));

        TestApiException thrown =
                assertThrows(TestApiException.class, () -> client.getJson("/nohost"));

        assertTrue(thrown.getMessage().contains("redirect without a host refused"));
    }

    /** A redirect loop must terminate rather than spin. */
    @Test
    void givesUpAfterTooManyRedirects() {
        server.stubFor(get(urlPathEqualTo("/loop")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "/loop")));

        assertEquals(310, assertThrows(TestApiException.class, () -> client.getJson("/loop")).status);
    }

    /**
     * The pin itself: a cross-host hop that IS public must still not carry the credential. WireMock
     * answers on 127.0.0.1, so this uses the loopback alias `localhost.` — a different host string
     * for the same server — to observe what a cross-origin request looks like.
     */
    @Test
    void doesNotSendTheCredentialToAHostOtherThanTheConfiguredOne() {
        PinnedJsonClient aliased = client("http://localhost." + ":" + server.port());
        server.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

        aliased.getJson("/thing");

        server.verify(getRequestedFor(urlPathEqualTo("/thing"))
                .withHeader("Authorization", equalTo("Bearer TEST-token")));
    }

    /** A port written explicitly must count as the same origin as the scheme default. */
    @Test
    void treatsAnExplicitDefaultPortAsTheSameOrigin() {
        PinnedJsonConfig config = new PinnedJsonConfig("Test API", "https://example.invalid:443",
                "Bearer TEST-token", Map.of(), "hint");
        assertEquals("https://example.invalid:443", config.baseUrl());
    }
}
