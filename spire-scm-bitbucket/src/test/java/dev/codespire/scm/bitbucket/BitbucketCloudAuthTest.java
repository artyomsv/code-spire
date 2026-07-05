package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Authorization header selection: Bearer access token vs Basic username/password. */
class BitbucketCloudAuthTest {

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(get(urlEqualTo("/ping"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void bearerTokenSendsBearerHeader() {
        BitbucketCloudClient client = new BitbucketCloudClient(
                new BitbucketCloudConfig("http://localhost:" + server.port(),
                        null, null, "tok-abc", "sec", "acct"),
                new ObjectMapper());
        client.getJson("/ping");
        server.verify(getRequestedFor(urlEqualTo("/ping"))
                .withHeader("Authorization", equalTo("Bearer tok-abc")));
    }

    @Test
    void basicAuthSendsBasicHeader() {
        BitbucketCloudClient client = new BitbucketCloudClient(
                new BitbucketCloudConfig("http://localhost:" + server.port(),
                        "user@example.com", "token123", "sec", "acct"),
                new ObjectMapper());
        client.getJson("/ping");
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "user@example.com:token123".getBytes(StandardCharsets.UTF_8));
        server.verify(getRequestedFor(urlEqualTo("/ping"))
                .withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void tokenTakesPrecedenceOverBasic() {
        BitbucketCloudClient client = new BitbucketCloudClient(
                new BitbucketCloudConfig("http://localhost:" + server.port(),
                        "user", "pw", "tok-xyz", "sec", "acct"),
                new ObjectMapper());
        client.getJson("/ping");
        server.verify(getRequestedFor(urlEqualTo("/ping"))
                .withHeader("Authorization", equalTo("Bearer tok-xyz")));
    }

    @Test
    void rejectsWhenNoAuthProvided() {
        assertThrows(IllegalArgumentException.class, () ->
                new BitbucketCloudConfig("http://x", null, null, null, "sec", "acct"));
    }
}
