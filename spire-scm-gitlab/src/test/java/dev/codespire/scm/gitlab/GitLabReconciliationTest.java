package dev.codespire.scm.gitlab;

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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitLabReconciliationTest {

    private WireMockServer wireMock;
    private GitLabClient client;
    private final RepoRef repo = new RepoRef("ws", "repo");

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new GitLabClient(new GitLabConfig(wireMock.baseUrl(), "test-token"),
                new ObjectMapper());
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    @Test
    void compareSynthesizesOneUnifiedDiff() {
        wireMock.stubFor(get(urlEqualTo("/projects/ws%2Frepo/repository/compare?from=aaa&to=bbb"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"diffs":[{"old_path":"src/A.java","new_path":"src/A.java",
                                           "diff":"@@ -1 +1 @@\\n-x\\n+y\\n"}]}""")));
        String diff = new GitLabDiffSource(client).fetchCompareDiff(repo, "aaa", "bbb");
        assertTrue(diff.contains("--- a/src/A.java"));
        assertTrue(diff.contains("+++ b/src/A.java"));
        assertTrue(diff.contains("@@ -1 +1 @@"));
    }

    @Test
    void resolveThreadPutsResolvedTrueWhenUnresolved() {
        wireMock.stubFor(get(urlEqualTo("/projects/ws%2Frepo/merge_requests/1/discussions/d1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"d1\",\"notes\":[{\"id\":5,\"resolvable\":true,\"resolved\":false}]}")));
        wireMock.stubFor(put(urlEqualTo("/projects/ws%2Frepo/merge_requests/1/discussions/d1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"d1\"}")));
        assertEquals(ThreadResolution.RESOLVED_NOW,
                new GitLabCommentSink(client).resolveThread(repo, 1L, new ThreadRef("d1")));
    }

    @Test
    void resolveThreadDetectsHumanResolution() {
        wireMock.stubFor(get(urlEqualTo("/projects/ws%2Frepo/merge_requests/1/discussions/d1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"d1\",\"notes\":[{\"id\":5,\"resolvable\":true,\"resolved\":true}]}")));
        assertEquals(ThreadResolution.ALREADY_RESOLVED,
                new GitLabCommentSink(client).resolveThread(repo, 1L, new ThreadRef("d1")));
        assertTrue(wireMock.findAll(com.github.tomakehurst.wiremock.client.WireMock
                .putRequestedFor(urlEqualTo("/projects/ws%2Frepo/merge_requests/1/discussions/d1"))).isEmpty());
    }

    @Test
    void updateCommentPutsTheNoteBody() {
        wireMock.stubFor(put(urlEqualTo("/projects/ws%2Frepo/merge_requests/1/notes/7"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7}")));
        assertEquals("7", new GitLabCommentSink(client)
                .updateComment(repo, 1L, "7", "new body").commentId());
    }
}
