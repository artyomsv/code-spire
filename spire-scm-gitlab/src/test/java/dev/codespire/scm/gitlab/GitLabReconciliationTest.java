package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.CommentSink.ThreadResolution;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.diff.UnifiedDiffParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
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

    /**
     * The compare diff must PARSE, not merely look like a diff. This test used to assert only that
     * the text contained {@code ---}/{@code +++}/{@code @@} — all true of a string the shared parser
     * reads as zero files, because it keys on the {@code diff --git} line. That gap let the
     * incremental diff come back structurally empty on GitLab, which silently downgraded every
     * STILL_OPEN reconciliation verdict to UNCHANGED, so a partly-fixed finding got no reply.
     */
    @Test
    void compareSynthesizesADiffTheSharedParserCanRead() {
        wireMock.stubFor(get(urlEqualTo("/projects/ws%2Frepo/repository/compare?from=aaa&to=bbb"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"diffs":[{"old_path":"src/A.java","new_path":"src/A.java",
                                           "diff":"@@ -8,3 +8,3 @@\\n-x\\n+y\\n z\\n"}]}""")));

        String diff = new GitLabDiffSource(client).fetchCompareDiff(repo, "aaa", "bbb");

        assertTrue(diff.contains("diff --git a/src/A.java b/src/A.java"),
                "without this header the parser yields no files at all");
        List<FilePatch> parsed = UnifiedDiffParser.parse(diff);
        assertEquals(1, parsed.size(), "the incremental diff must parse to one changed file");
        assertEquals("src/A.java", parsed.getFirst().newPath());
        assertEquals(1, parsed.getFirst().hunks().size());
        // The OLD-side range is what downgradeUntouched tests a prior finding's line against.
        assertEquals(8, parsed.getFirst().hunks().getFirst().oldStart());
    }

    /** A rename in the incremental diff still has to parse — the header carries both paths. */
    @Test
    void compareCarriesRenameMarkersThroughToTheParser() {
        wireMock.stubFor(get(urlEqualTo("/projects/ws%2Frepo/repository/compare?from=aaa&to=bbb"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"diffs":[{"old_path":"src/Old.java","new_path":"src/New.java",
                                           "renamed_file":true,
                                           "diff":"@@ -1 +1 @@\\n-x\\n+y\\n"}]}""")));

        List<FilePatch> parsed = UnifiedDiffParser.parse(
                new GitLabDiffSource(client).fetchCompareDiff(repo, "aaa", "bbb"));

        assertEquals(1, parsed.size());
        assertEquals("src/Old.java", parsed.getFirst().oldPath());
        assertEquals("src/New.java", parsed.getFirst().newPath());
    }

    @Test
    void resolveThreadPutsResolvedTrueWhenUnresolved() {
        wireMock.stubFor(get(urlEqualTo("/projects/ws%2Frepo/merge_requests/1/discussions/d1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"d1\",\"notes\":[{\"id\":5,\"resolvable\":true,\"resolved\":false}]}")));
        wireMock.stubFor(put(urlEqualTo("/projects/ws%2Frepo/merge_requests/1/discussions/d1"))
                .withRequestBody(equalToJson("{\"resolved\":true}"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"d1\"}")));
        assertEquals(ThreadResolution.RESOLVED_NOW,
                new GitLabCommentSink(client).resolveThread(repo, 1L, new ThreadRef("d1")));
    }

    @Test
    void resolveThreadOnANonResolvableThreadDegradesToReplyOnly() {
        // Only diff discussions are resolvable; a plain MR-note thread (the summary thread) has no
        // resolvable note. Reporting ALREADY_RESOLVED there would claim a resolve we never performed
        // AND suppress the reply, because the caller reads that status as "a human closed it".
        wireMock.stubFor(get(urlEqualTo("/projects/ws%2Frepo/merge_requests/1/discussions/d1"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"d1\",\"notes\":[{\"id\":5,\"resolvable\":false}]}")));

        assertEquals(ThreadResolution.UNSUPPORTED,
                new GitLabCommentSink(client).resolveThread(repo, 1L, new ThreadRef("d1")));
        assertTrue(wireMock.findAll(com.github.tomakehurst.wiremock.client.WireMock
                .putRequestedFor(urlEqualTo("/projects/ws%2Frepo/merge_requests/1/discussions/d1"))).isEmpty());
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
                .withRequestBody(equalToJson("{\"body\":\"new body\"}"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7}")));
        assertEquals("7", new GitLabCommentSink(client)
                .updateComment(repo, 1L, new ThreadRef("7"), "new body").commentId());
    }
}
