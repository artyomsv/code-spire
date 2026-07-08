package dev.codespire.scm.gitlab;

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

/** DiffSource + CommentSink against a WireMock GitLab (request shapes per SCM-MAPPING §GitLab). */
class GitLabApiTest {

    private static WireMockServer server;
    private static GitLabDiffSource diffSource;
    private static GitLabCommentSink commentSink;
    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    // GitLab addresses the project by URL-encoded full path.
    private static final String MR = "/projects/sandbox%2Fdemo-repo/merge_requests/42";

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        GitLabClient client = new GitLabClient(
                new GitLabConfig("http://localhost:" + server.port(), "test-token"),
                new ObjectMapper());
        diffSource = new GitLabDiffSource(client);
        commentSink = new GitLabCommentSink(client);
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
    void encodesNestedGroupProjectPath() {
        // group/subgroup/project -> the whole namespace is one URL-encoded segment.
        assertEquals("/projects/group%2Fsubgroup%2Fproject/merge_requests/7",
                GitLabDiffSource.mrPath(new RepoRef("group", "subgroup/project"), 7));
    }

    @Test
    void fetchesMergeRequest() {
        server.stubFor(get(urlEqualTo(MR))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "iid": 42, "title": "Add feature", "description": "desc",
                          "source_branch": "feature/x", "target_branch": "main",
                          "diff_refs": { "base_sha": "base000", "start_sha": "start000", "head_sha": "abc123def456" },
                          "author": { "id": 5150, "username": "jdoe", "name": "Jane Doe" },
                          "web_url": "https://gitlab.com/sandbox/demo-repo/-/merge_requests/42" }
                        """)));

        PullRequest pr = diffSource.fetchPullRequest(REPO, 42);
        assertEquals("Add feature", pr.title());
        assertEquals("feature/x", pr.sourceBranch());
        assertEquals("main", pr.targetBranch());
        assertEquals("abc123def456", pr.diffRefs().headSha());
        assertEquals("base000", pr.diffRefs().baseSha());
        assertEquals("start000", pr.diffRefs().startSha());
        assertEquals("5150", pr.author().providerUserId());
        assertEquals("jdoe", pr.author().username());
    }

    @Test
    void fetchesAndParsesDiffWithAllThreeRefs() {
        server.stubFor(get(urlEqualTo(MR + "/changes"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "diff_refs": { "base_sha": "base000", "start_sha": "start000", "head_sha": "abc123def456" },
                          "overflow": false,
                          "changes": [
                            { "old_path": "src/App.java", "new_path": "src/App.java",
                              "new_file": false, "deleted_file": false, "renamed_file": false,
                              "diff": "@@ -1,2 +1,2 @@\\n keep\\n-old\\n+new\\n" }
                          ] }
                        """)));

        Diff diff = diffSource.fetchDiff(REPO, 42, "abc123def456");
        assertEquals(1, diff.files().size());
        assertEquals("src/App.java", diff.files().getFirst().newPath());
        // GitLab needs all three SHAs to anchor an inline comment.
        assertEquals("base000", diff.refs().baseSha());
        assertEquals("start000", diff.refs().startSha());
        assertEquals("abc123def456", diff.refs().headSha());
    }

    @Test
    void notFoundDiffSurfacesAs404() {
        server.stubFor(get(urlEqualTo(MR + "/changes")).willReturn(aResponse().withStatus(404)));
        GitLabApiException e = assertThrows(GitLabApiException.class,
                () -> diffSource.fetchDiff(REPO, 42, "gone"));
        assertTrue(e.isNotFound()); // force-pushed-away commit -> treat as superseded
    }

    @Test
    void postsSummaryAsNote() {
        server.stubFor(post(urlEqualTo(MR + "/notes"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{ \"id\": 991 }")));
        CommentRef ref = commentSink.postSummary(REPO, 42, "**Summary**");
        assertEquals("991", ref.commentId());
        server.verify(postRequestedFor(urlEqualTo(MR + "/notes"))
                .withRequestBody(equalToJson("{ \"body\": \"**Summary**\" }")));
    }

    @Test
    void postsInlineCommentOnNewSideWithNewLineOnly() {
        stubDiscussionCreated();
        commentSink.postInline(REPO, 42, new DiffRefs("base1", "start1", "head1"),
                new InlineAnchor("src/App.java", "src/App.java", null, 7, Side.NEW), "note");
        server.verify(postRequestedFor(urlEqualTo(MR + "/discussions"))
                .withRequestBody(equalToJson("""
                        { "body": "note", "position": { "position_type": "text",
                          "base_sha": "base1", "start_sha": "start1", "head_sha": "head1",
                          "old_path": "src/App.java", "new_path": "src/App.java", "new_line": 7 } }
                        """)));
    }

    @Test
    void postsInlineCommentOnOldSideWithOldLineOnly() {
        stubDiscussionCreated();
        commentSink.postInline(REPO, 42, new DiffRefs("base1", "start1", "head1"),
                new InlineAnchor("src/App.java", "src/App.java", 5, null, Side.OLD), "removed?");
        server.verify(postRequestedFor(urlEqualTo(MR + "/discussions"))
                .withRequestBody(equalToJson("""
                        { "body": "removed?", "position": { "position_type": "text",
                          "base_sha": "base1", "start_sha": "start1", "head_sha": "head1",
                          "old_path": "src/App.java", "new_path": "src/App.java", "old_line": 5 } }
                        """)));
    }

    @Test
    void postsInlineCommentOnContextWithBothLines() {
        stubDiscussionCreated();
        commentSink.postInline(REPO, 42, new DiffRefs("base1", "start1", "head1"),
                new InlineAnchor("src/App.java", "src/App.java", 4, 6, Side.NEW), "context");
        server.verify(postRequestedFor(urlEqualTo(MR + "/discussions"))
                .withRequestBody(equalToJson("""
                        { "body": "context", "position": { "position_type": "text",
                          "base_sha": "base1", "start_sha": "start1", "head_sha": "head1",
                          "old_path": "src/App.java", "new_path": "src/App.java", "old_line": 4, "new_line": 6 } }
                        """)));
    }

    @Test
    void inlineReturnsNoteIdAndDiscussionThread() {
        stubDiscussionCreated();
        CommentRef ref = commentSink.postInline(REPO, 42, new DiffRefs("b", "s", "h"),
                new InlineAnchor("src/App.java", "src/App.java", null, 7, Side.NEW), "note");
        assertEquals("501", ref.commentId());               // first note id
        assertEquals("abcd1234", ref.thread().value());     // discussion_id, not a comment id
    }

    @Test
    void replyPostsToTheDiscussionNotesEndpoint() {
        server.stubFor(post(urlEqualTo(MR + "/discussions/abcd1234/notes"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{ \"id\": 993 }")));
        commentSink.replyInThread(REPO, 42, new ThreadRef("abcd1234"), "answer");
        server.verify(postRequestedFor(urlEqualTo(MR + "/discussions/abcd1234/notes"))
                .withRequestBody(equalToJson("{ \"body\": \"answer\" }")));
    }

    @Test
    void fetchesPullRequestAuthorWithoutEmail() {
        server.stubFor(get(urlEqualTo(MR))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "iid": 42, "author": { "id": 9001, "username": "jdoe", "name": "Jane Doe" } }
                        """)));
        var author = commentSink.getPullRequestAuthor(REPO, 42);
        assertEquals("9001", author.providerUserId());
        assertEquals("jdoe", author.username());
        assertNull(author.email()); // never carried (SCM-MAPPING §1)
    }

    @Test
    void whoamiResolvesTheTokenOwner() {
        server.stubFor(get(urlEqualTo("/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "id": 40727, "username": "spire-bot", "name": "Code Spire Bot" }
                        """)));
        var me = diffSource.whoami();
        assertEquals("40727", me.providerUserId()); // the stable numeric id -> bot account id
        assertEquals("spire-bot", me.username());
        assertEquals("Code Spire Bot", me.displayName());
        assertNull(me.email());
    }

    @Test
    void whoamiOnBadTokenSurfacesAs401() {
        server.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(401)));
        GitLabApiException e = assertThrows(GitLabApiException.class, () -> diffSource.whoami());
        assertEquals(401, e.status());
    }

    // --- redirect + error handling (security review L5/L7) ---

    @Test
    void followsSameHostRedirectOnGet() {
        server.stubFor(get(urlEqualTo(MR + "/changes"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/moved-diff")));
        server.stubFor(get(urlEqualTo("/moved-diff"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "diff_refs": { "base_sha": "b", "start_sha": "s", "head_sha": "h" },
                          "changes": [ { "old_path": "a.java", "new_path": "a.java",
                            "diff": "@@ -1 +1 @@\\n-old\\n+new\\n" } ] }
                        """)));
        Diff diff = diffSource.fetchDiff(REPO, 42, "abc123def456");
        assertEquals(1, diff.files().size());
    }

    @Test
    void crossHostRedirectToLoopbackIsRefused() {
        // 127.0.0.1 differs from the configured base host (localhost) -> SSRF guard applies
        server.stubFor(get(urlEqualTo(MR + "/changes"))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "http://127.0.0.1:" + server.port() + "/loop")));
        GitLabApiException e = assertThrows(GitLabApiException.class,
                () -> diffSource.fetchDiff(REPO, 42, "abc123def456"));
        assertTrue(e.getMessage().contains("non-public address refused"));
    }

    @Test
    void redirectOnPostIsRefused() {
        // replaying a POST against a Location could double-post the comment
        server.stubFor(post(urlEqualTo(MR + "/notes"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));
        GitLabApiException e = assertThrows(GitLabApiException.class,
                () -> commentSink.postSummary(REPO, 42, "**Summary**"));
        assertEquals(302, e.status());
        assertTrue(e.getMessage().contains("redirect on POST refused"));
    }

    @Test
    void errorResponsesCarryATruncatedBodySnippet() {
        server.stubFor(get(urlEqualTo(MR))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"message\": \"line must be part of the diff\"}")));
        GitLabApiException e = assertThrows(GitLabApiException.class,
                () -> commentSink.getPullRequestAuthor(REPO, 42));
        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains("line must be part of the diff"));
    }

    @Test
    void rateLimitSurfacesAs429() {
        server.stubFor(get(urlEqualTo(MR)).willReturn(aResponse().withStatus(429)));
        GitLabApiException e = assertThrows(GitLabApiException.class,
                () -> commentSink.getPullRequestAuthor(REPO, 42));
        assertTrue(e.isRateLimited());
    }

    @Test
    void malformed2xxWithoutIdIsRejected() {
        server.stubFor(post(urlEqualTo(MR + "/notes"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
        GitLabApiException e = assertThrows(GitLabApiException.class,
                () -> commentSink.postSummary(REPO, 42, "**Summary**"));
        assertTrue(e.getMessage().contains("no id"));
    }

    private void stubDiscussionCreated() {
        server.stubFor(post(urlEqualTo(MR + "/discussions"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "id": "abcd1234", "notes": [ { "id": 501 } ] }
                        """)));
    }
}
