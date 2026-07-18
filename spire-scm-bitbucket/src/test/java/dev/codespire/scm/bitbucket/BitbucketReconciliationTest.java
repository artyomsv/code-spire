package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.CommentSink.ThreadResolution;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BitbucketReconciliationTest {

    private WireMockServer wireMock;
    private BitbucketCloudClient client;
    private final RepoRef repo = new RepoRef("ws", "repo");

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new BitbucketCloudClient(
                new BitbucketCloudConfig(wireMock.baseUrl(), "user", "app-password", "unused"),
                new ObjectMapper());
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void fetchCompareDiffUsesTheTwoDotSpec() {
        wireMock.stubFor(get(urlEqualTo("/2.0/repositories/ws/repo/diff/bbb..aaa"))
                .willReturn(aResponse().withStatus(200).withBody("diff --git a/x b/x")));
        assertEquals("diff --git a/x b/x",
                new BitbucketCloudDiffSource(client).fetchCompareDiff(repo, "aaa", "bbb"));
    }

    @Test
    void updateCommentPutsRawContent() {
        wireMock.stubFor(put(urlEqualTo("/2.0/repositories/ws/repo/pullrequests/1/comments/42"))
                .withRequestBody(equalToJson("{\"content\":{\"raw\":\"new body\"}}"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42}")));
        assertEquals("42", new BitbucketCloudCommentSink(client)
                .updateComment(repo, 1L, "42", "new body").commentId());
    }

    @Test
    void resolveThreadStaysUnsupported() {
        assertEquals(ThreadResolution.UNSUPPORTED,
                new BitbucketCloudCommentSink(client).resolveThread(repo, 1L, new ThreadRef("42")));
    }
}
