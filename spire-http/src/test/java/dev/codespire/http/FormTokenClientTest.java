package dev.codespire.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OAuth exchange every SCM adapter shares.
 *
 * <p>Three of these tests are about a client secret rather than about HTTP. It travels in the form
 * body, so where it can end up — a redirect to another host, an error message, a plaintext wire —
 * is the whole risk surface of this class.
 */
class FormTokenClientTest {

    /** Stands in for GitHubApiException and friends; every adapter supplies its own. */
    static class TestApiException extends RuntimeException {
        final int status;

        TestApiException(int status, String method, String path, String detail) {
            super("Test API " + method + " " + path + " failed with HTTP " + status
                    + (detail == null || detail.isBlank() ? "" : ": " + detail));
            this.status = status;
        }
    }

    private static final HttpFailures FAILURES = TestApiException::new;
    private static WireMockServer server;

    private final FormTokenClient client = new FormTokenClient(new ObjectMapper());

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

    private String tokenUrl() {
        return server.baseUrl() + "/oauth/token";
    }

    private static Map<String, String> form() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", "TEST-CLIENT");
        form.put("client_secret", "TEST-SECRET");
        form.put("code", "TEST-CODE");
        return form;
    }

    @Test
    void returnsTheAccessTokenAndAsksForJson() {
        server.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"TEST-TOKEN\",\"token_type\":\"bearer\"}")));

        assertEquals("TEST-TOKEN", client.accessToken(tokenUrl(), form(), null, FAILURES));

        // Without the Accept header one platform answers form-encoded, and the parse below fails on
        // a response that is otherwise perfectly good.
        server.verify(postRequestedFor(urlPathEqualTo("/oauth/token"))
                .withHeader("Accept", equalTo("application/json"))
                .withRequestBody(containing("client_secret=TEST-SECRET")));
    }

    /** One platform refuses the credentials in the body and wants them as HTTP Basic. */
    @Test
    void sendsBasicCredentialsWhenTheCallerAsksFor() {
        server.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"TEST-TOKEN\"}")));

        client.accessToken(tokenUrl(), form(), "TEST-CLIENT:TEST-SECRET", FAILURES);

        // "TEST-CLIENT:TEST-SECRET" base64-encoded.
        server.verify(postRequestedFor(urlPathEqualTo("/oauth/token"))
                .withHeader("Authorization", equalTo("Basic VEVTVC1DTElFTlQ6VEVTVC1TRUNSRVQ=")));
    }

    /**
     * The failure that would otherwise sign somebody in as nobody.
     *
     * <p>OAuth providers answer a rejected code with <b>HTTP 200</b> and an error field, so a status
     * check alone passes and the caller reads a null token as an empty identity.
     */
    @Test
    void rejectsA200ThatCarriesAnErrorRatherThanAToken() {
        server.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"bad_verification_code\",\"error_description\":\"expired\"}")));

        TestApiException refused = assertThrows(TestApiException.class,
                () -> client.accessToken(tokenUrl(), form(), null, FAILURES));
        assertEquals(200, refused.status);
        // The error CODE is a fixed vocabulary and is safe to report; it is what an operator needs.
        assertTrue(refused.getMessage().contains("bad_verification_code"));
    }

    /**
     * An error response echoes back the parameters it rejected, and on this path one of them IS the
     * client secret. Nothing from the body may reach a message a log or a screen could carry.
     */
    @Test
    void neverRepeatsTheResponseBodyOfANonSuccess() {
        server.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("client_secret=TEST-SECRET was rejected")));

        TestApiException refused = assertThrows(TestApiException.class,
                () -> client.accessToken(tokenUrl(), form(), null, FAILURES));
        assertEquals(400, refused.status);
        assertFalse(refused.getMessage().contains("TEST-SECRET"),
                "a rejected request's body echoes the secret back and must never be repeated");
    }

    /**
     * A token endpoint has no reason to redirect, and following one would carry the client secret to
     * whatever host answered. Refused rather than followed — which is also why this class is not a
     * fourth hand-rolled redirect loop.
     */
    @Test
    void refusesToFollowARedirectRatherThanCarryTheSecretToAnotherHost() {
        server.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "https://elsewhere.example.invalid/token")));

        TestApiException refused = assertThrows(TestApiException.class,
                () -> client.accessToken(tokenUrl(), form(), null, FAILURES));
        assertEquals(302, refused.status);
    }

    /**
     * A secret in a form body over plaintext is a published secret.
     *
     * <p>Loopback is exempt and that is not a relaxation: a request to 127.0.0.1 never reaches a
     * wire. It is also what makes the guard testable at all — every test above answers over plain
     * http on localhost — without a configuration flag that could be switched off anywhere.
     */
    @Test
    void refusesPlaintextOffTheLocalMachine() {
        TestApiException refused = assertThrows(TestApiException.class,
                () -> client.accessToken("http://scm.example.invalid/oauth/token", form(), null, FAILURES));
        assertTrue(refused.getMessage().contains("https"));
    }

    @Test
    void rejectsABodyThatIsNotJson() {
        server.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html")
                        .withBody("<html><body>Sign in</body></html>")));

        assertThrows(TestApiException.class, () -> client.accessToken(tokenUrl(), form(), null, FAILURES));
    }
}
