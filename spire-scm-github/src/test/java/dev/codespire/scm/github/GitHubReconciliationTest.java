package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.CommentSink.ThreadResolution;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReconciliationTest {

    private WireMockServer wireMock;
    private GitHubClient client;
    private final RepoRef repo = new RepoRef("ws", "repo");

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new GitHubClient(
                new GitHubConfig(wireMock.baseUrl(), "test-token", "unused"), new ObjectMapper());
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void fetchCompareDiffHitsTheCompareEndpointWithDiffMedia() {
        wireMock.stubFor(get(urlEqualTo("/repos/ws/repo/compare/aaa...bbb"))
                .willReturn(aResponse().withStatus(200).withBody("diff --git a/x b/x")));
        String diff = new GitHubDiffSource(client).fetchCompareDiff(repo, "aaa", "bbb");
        assertEquals("diff --git a/x b/x", diff);
    }

    @Test
    void updateCommentPatchesTheIssueComment() {
        wireMock.stubFor(patch(urlEqualTo("/repos/ws/repo/issues/comments/42"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":42}")));
        CommentRef ref = new GitHubCommentSink(client).updateComment(repo, 1L, "42", "new body");
        assertEquals("42", ref.commentId());
    }

    @Test
    void resolveThreadResolvesAnUnresolvedThread() {
        wireMock.stubFor(post(urlPathEqualTo("/graphql"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("reviewThreads"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"repository":{"pullRequest":{"reviewThreads":{
                                  "pageInfo":{"hasNextPage":false,"endCursor":null},
                                  "nodes":[{"id":"RT_1","isResolved":false,
                                            "comments":{"nodes":[{"databaseId":42}]}}]}}}}}""")));
        wireMock.stubFor(post(urlPathEqualTo("/graphql"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("resolveReviewThread"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"resolveReviewThread\":{\"thread\":{\"isResolved\":true}}}}")));
        ThreadResolution result = new GitHubCommentSink(client)
                .resolveThread(repo, 1L, new ThreadRef("42"));
        assertEquals(ThreadResolution.RESOLVED_NOW, result);
    }

    @Test
    void resolveThreadReportsAlreadyResolvedWithoutMutating() {
        wireMock.stubFor(post(urlPathEqualTo("/graphql"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"repository":{"pullRequest":{"reviewThreads":{
                                  "pageInfo":{"hasNextPage":false,"endCursor":null},
                                  "nodes":[{"id":"RT_1","isResolved":true,
                                            "comments":{"nodes":[{"databaseId":42}]}}]}}}}}""")));
        ThreadResolution result = new GitHubCommentSink(client)
                .resolveThread(repo, 1L, new ThreadRef("42"));
        assertEquals(ThreadResolution.ALREADY_RESOLVED, result);
        assertTrue(wireMock.findAll(com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlPathEqualTo("/graphql"))).stream()
                .noneMatch(r -> r.getBodyAsString().contains("resolveReviewThread")));
    }

    @Test
    void gheBaseUrlRoutesGraphQlToApiGraphql() {
        GitHubClient ghe = new GitHubClient(
                new GitHubConfig(wireMock.baseUrl() + "/api/v3", "t", "unused"), new ObjectMapper());
        wireMock.stubFor(post(urlEqualTo("/api/graphql"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"repository":{"pullRequest":{"reviewThreads":{
                                  "pageInfo":{"hasNextPage":false,"endCursor":null},
                                  "nodes":[]}}}}}""")));
        assertEquals(ThreadResolution.ALREADY_RESOLVED,
                new GitHubCommentSink(ghe).resolveThread(repo, 1L, new ThreadRef("42")));
        assertFalse(wireMock.findAll(postRequestedFor(urlEqualTo("/api/graphql"))).isEmpty());
    }

    @Test
    void resolveExhaustedPaginationReportsUnsupportedNotResolved() {
        // Every page returns a full 100-node page with hasNextPage still true and none of the
        // nodes' databaseIds matching the target — the loop must hit MAX_THREAD_PAGES and stop.
        wireMock.stubFor(post(urlPathEqualTo("/graphql"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(nonMatchingPageOfHundred())));
        ThreadResolution result = new GitHubCommentSink(client)
                .resolveThread(repo, 1L, new ThreadRef("999999"));
        assertEquals(ThreadResolution.UNSUPPORTED, result);
    }

    private static String nonMatchingPageOfHundred() {
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (i > 0) {
                nodes.append(',');
            }
            nodes.append("{\"id\":\"RT_").append(i).append("\",\"isResolved\":false,")
                    .append("\"comments\":{\"nodes\":[{\"databaseId\":").append(i).append("}]}}");
        }
        return "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{"
                + "\"pageInfo\":{\"hasNextPage\":true,\"endCursor\":\"c\"},"
                + "\"nodes\":[" + nodes + "]}}}}}";
    }
}
