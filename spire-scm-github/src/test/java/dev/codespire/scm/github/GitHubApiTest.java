package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.Side;
import dev.codespire.contract.scm.ThreadRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DiffSource + CommentSink against a WireMock GitHub (request shapes per SCM-MAPPING §GitHub). */
class GitHubApiTest {

    private static WireMockServer server;
    private static GitHubDiffSource diffSource;
    private static GitHubCommentSink commentSink;
    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        GitHubClient client = new GitHubClient(
                new GitHubConfig("http://localhost:" + server.port(), "test-token", "test-secret", "bot-000"),
                new ObjectMapper());
        diffSource = new GitHubDiffSource(client);
        commentSink = new GitHubCommentSink(client);
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
    void fetchesPullRequest() {
        server.stubFor(get(urlEqualTo("/repos/sandbox/demo-repo/pulls/42"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "number": 42, "title": "Add feature", "body": "desc",
                          "head": { "ref": "feature/x", "sha": "abc123def456" },
                          "base": { "ref": "main", "sha": "base000" },
                          "user": { "id": 5150, "login": "jdoe" },
                          "html_url": "https://github.com/sandbox/demo-repo/pull/42" }
                        """)));

        PullRequest pr = diffSource.fetchPullRequest(REPO, 42);
        assertEquals("Add feature", pr.title());
        assertEquals("feature/x", pr.sourceBranch());
        assertEquals("main", pr.targetBranch());
        assertEquals("abc123def456", pr.diffRefs().headSha());
        assertEquals("5150", pr.author().providerUserId());
        assertEquals("jdoe", pr.author().username());
    }

    @Test
    void fetchesAndParsesDiff() {
        server.stubFor(get(urlEqualTo("/repos/sandbox/demo-repo/pulls/42"))
                .willReturn(aResponse().withHeader("Content-Type", "text/plain").withBody("""
                        diff --git a/src/App.java b/src/App.java
                        --- a/src/App.java
                        +++ b/src/App.java
                        @@ -1,2 +1,2 @@
                         keep
                        -old
                        +new
                        """)));

        Diff diff = diffSource.fetchDiff(REPO, 42, "abc123def456");
        assertEquals(1, diff.files().size());
        assertEquals("src/App.java", diff.files().getFirst().newPath());
        assertEquals("abc123def456", diff.refs().headSha());
    }

    @Test
    void notFoundDiffSurfacesAs404() {
        server.stubFor(get(urlEqualTo("/repos/sandbox/demo-repo/pulls/42"))
                .willReturn(aResponse().withStatus(404)));
        GitHubApiException e = assertThrows(GitHubApiException.class,
                () -> diffSource.fetchDiff(REPO, 42, "gone"));
        assertTrue(e.isNotFound()); // force-pushed-away commit -> treat as superseded
    }

    @Test
    void postsSummaryAsIssueComment() {
        server.stubFor(post(urlEqualTo("/repos/sandbox/demo-repo/issues/42/comments"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{ \"id\": 991 }")));
        CommentRef ref = commentSink.postSummary(REPO, 42, "**Summary**");
        assertEquals("991", ref.commentId());
        server.verify(postRequestedFor(urlEqualTo("/repos/sandbox/demo-repo/issues/42/comments"))
                .withRequestBody(equalToJson("{ \"body\": \"**Summary**\" }")));
    }

    @Test
    void postsInlineCommentOnNewSideAsRight() {
        stubReviewCommentCreated();
        commentSink.postInline(REPO, 42, DiffRefs.headOnly("abc"),
                new InlineAnchor("src/App.java", "src/App.java", null, 7, Side.NEW), "note");
        server.verify(postRequestedFor(urlEqualTo("/repos/sandbox/demo-repo/pulls/42/comments"))
                .withRequestBody(equalToJson("""
                        { "body": "note", "commit_id": "abc", "path": "src/App.java", "line": 7, "side": "RIGHT" }
                        """)));
    }

    @Test
    void postsInlineCommentOnOldSideAsLeft() {
        stubReviewCommentCreated();
        commentSink.postInline(REPO, 42, DiffRefs.headOnly("abc"),
                new InlineAnchor("src/App.java", "src/App.java", 5, null, Side.OLD), "removed?");
        server.verify(postRequestedFor(urlEqualTo("/repos/sandbox/demo-repo/pulls/42/comments"))
                .withRequestBody(equalToJson("""
                        { "body": "removed?", "commit_id": "abc", "path": "src/App.java", "line": 5, "side": "LEFT" }
                        """)));
    }

    @Test
    void replyPostsToTheRepliesEndpoint() {
        server.stubFor(post(urlEqualTo("/repos/sandbox/demo-repo/pulls/42/comments/77/replies"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{ \"id\": 993 }")));
        commentSink.replyInThread(REPO, 42, new ThreadRef("77"), "answer");
        server.verify(postRequestedFor(urlEqualTo("/repos/sandbox/demo-repo/pulls/42/comments/77/replies"))
                .withRequestBody(equalToJson("{ \"body\": \"answer\" }")));
    }

    @Test
    void fetchesPullRequestAuthorWithoutEmail() {
        server.stubFor(get(urlEqualTo("/repos/sandbox/demo-repo/pulls/42"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "number": 42, "user": { "id": 9001, "login": "jdoe" } }
                        """)));
        var author = commentSink.getPullRequestAuthor(REPO, 42);
        assertEquals("9001", author.providerUserId());
        assertEquals("jdoe", author.username());
        assertNull(author.email()); // never carried (SCM-MAPPING §1)
    }

    private void stubReviewCommentCreated() {
        server.stubFor(post(urlEqualTo("/repos/sandbox/demo-repo/pulls/42/comments"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{ \"id\": 992 }")));
    }
}
