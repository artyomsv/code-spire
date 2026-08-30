package dev.codespire.scm.bitbucket;

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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proving a Bitbucket account belongs to the operator at the browser.
 *
 * <p>This is the adapter the whole feature is most obviously for: a Bitbucket author id is
 * {@code 557058:ee019d01-863e-...}, a value no operator could reasonably type and which appears
 * nowhere in the product. Asserting it comes back verbatim is asserting the link matches real rows.
 */
class BitbucketOperatorOAuthTest {

    /** A real account id's shape. Synthetic, but shaped so a truncating bug would show. */
    private static final String ACCOUNT_ID = "557058:TEST0000-0000-4000-8000-000000000000";

    private static WireMockServer server;

    private final BitbucketOperatorOAuth oauth = new BitbucketOperatorOAuth(new ObjectMapper());

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
        return new OAuthApp(ScmType.BITBUCKET_CLOUD, server.baseUrl(), server.baseUrl(),
                "TEST-CLIENT", "TEST-SECRET");
    }

    @Test
    void sendsTheOperatorToTheHostedSignInWhenNoBaseUrlIsSet() {
        OAuthApp hosted = new OAuthApp(ScmType.BITBUCKET_CLOUD, null, null, "TEST-CLIENT", "TEST-SECRET");

        String url = oauth.authorizeUrl(hosted, "TEST-STATE", "https://spire.example.invalid/cb");

        assertTrue(url.startsWith(BitbucketOperatorOAuth.HOSTED_WEB + "/site/oauth2/authorize"));
        assertTrue(url.contains("client_id=TEST-CLIENT"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("state=TEST-STATE"));
    }

    /**
     * Bitbucket refuses the client credentials in the form body and wants HTTP Basic. Sending them
     * the way the other two adapters do returns a 400 that names nothing recognisable.
     */
    @Test
    void returnsTheAccountIdTheIngressRecords() {
        server.stubFor(post(urlPathEqualTo("/site/oauth2/access_token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"TEST-TOKEN\"}")));
        server.stubFor(get(urlPathEqualTo("/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"account_id\":\"" + ACCOUNT_ID + "\",\"username\":\"test-author\","
                                + "\"display_name\":\"TEST Author\"}")));

        Author author = oauth.identify(app(), "TEST-CODE", "https://spire.example.invalid/cb");

        assertEquals(ACCOUNT_ID, author.providerUserId());
        assertEquals("TEST Author", author.displayName());
        // "TEST-CLIENT:TEST-SECRET" base64-encoded.
        server.verify(postRequestedFor(urlPathEqualTo("/site/oauth2/access_token"))
                .withHeader("Authorization", equalTo("Basic VEVTVC1DTElFTlQ6VEVTVC1TRUNSRVQ=")));
    }

    @Test
    void raisesTheAdaptersOwnExceptionWhenTheCodeIsRefused() {
        server.stubFor(post(urlPathEqualTo("/site/oauth2/access_token"))
                .willReturn(aResponse().withStatus(400)));

        assertThrows(BitbucketApiException.class,
                () -> oauth.identify(app(), "TEST-CODE", "https://spire.example.invalid/cb"));
    }
}
