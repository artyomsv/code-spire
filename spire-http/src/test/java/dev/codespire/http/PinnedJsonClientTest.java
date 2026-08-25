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
 *
 * <p><b>Not covered here:</b> scheme-default port normalization — an explicit {@code :443} on an
 * https base URL matching a redirect target with no explicit port, or the reverse — cannot be
 * exercised over the plain-HTTP loopback WireMock gives these tests; a genuine case needs a real TLS
 * listener. That behaviour ({@link PinnedJsonClient} effective-port comparison) rests on code
 * inspection rather than a running test.
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

    /**
     * The whole reason {@link PinnedJsonClient#getRaw} exists: a successful raw-file response is
     * ordinary source text, not JSON, and must not be treated as a redirected sign-in page the way
     * {@link PinnedJsonClient#getJson} would treat it.
     */
    @Test
    void getRawReturnsANonJsonBodyVerbatim() {
        server.stubFor(get(urlPathEqualTo("/raw-file")).willReturn(aResponse()
                .withHeader("Content-Type", "text/plain").withBody("class Alpha { }")));

        assertEquals("class Alpha { }", client.getRaw("/raw-file"));
    }

    /** getRaw shares the same failure classification as getJson — only the success path differs. */
    @Test
    void getRawStillBuildsTheAdaptersOwnExceptionOnFailure() {
        server.stubFor(get(urlPathEqualTo("/raw-missing")).willReturn(aResponse().withStatus(404)));

        TestApiException thrown = assertThrows(TestApiException.class, () -> client.getRaw("/raw-missing"));

        assertEquals(404, thrown.status);
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
     * The pin itself: a redirect that changes ORIGIN — same host, different port, so
     * {@code sameOrigin} is false — must not carry the credential to the new origin, even though the
     * cross-host SSRF check in {@code requireSafeRedirectTarget} does not intervene (the host is
     * unchanged, so that guard returns early and lets the hop through). Two real WireMock servers
     * make the difference observable directly: server A gets the credential on the initial request,
     * server B — the redirect target — must not.
     */
    @Test
    void doesNotSendTheCredentialAcrossAPortChangingRedirect() {
        WireMockServer other = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        other.start();
        try {
            server.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse().withStatus(302)
                    .withHeader("Location", "http://localhost:" + other.port() + "/thing")));
            other.stubFor(get(urlPathEqualTo("/thing")).willReturn(aResponse()
                    .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));

            assertTrue(client.getJson("/thing").path("ok").asBoolean());

            server.verify(getRequestedFor(urlPathEqualTo("/thing"))
                    .withHeader("Authorization", equalTo("Bearer TEST-token")));
            other.verify(getRequestedFor(urlPathEqualTo("/thing"))
                    .withHeader("Authorization", absent()));
        } finally {
            other.stop();
        }
    }
}
