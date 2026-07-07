package dev.codespire.scm.bitbucket;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DiffSource + CommentSink against a WireMock Bitbucket (request shapes per SCM-MAPPING). */
class BitbucketCloudApiTest {

    private static WireMockServer server;
    private static BitbucketCloudDiffSource diffSource;
    private static BitbucketCloudCommentSink commentSink;
    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        BitbucketCloudClient client = new BitbucketCloudClient(
                new BitbucketCloudConfig("http://localhost:" + server.port(),
                        "test-bot", "test-app-password", "test-secret"),
                new ObjectMapper());
        diffSource = new BitbucketCloudDiffSource(client);
        commentSink = new BitbucketCloudCommentSink(client);
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
        server.stubFor(get(urlEqualTo("/repositories/sandbox/demo-repo/pullrequests/42"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "id": 42, "title": "Add feature", "description": "desc",
                          "source": { "branch": {"name": "feature/x"}, "commit": {"hash": "abc123def456"} },
                          "destination": { "branch": {"name": "main"} },
                          "author": { "account_id": "author-1", "nickname": "jdoe", "display_name": "J. Doe" },
                          "links": { "html": {"href": "https://bitbucket.org/x"} } }
                        """)));

        PullRequest pr = diffSource.fetchPullRequest(REPO, 42);
        assertEquals("Add feature", pr.title());
        assertEquals("abc123def456", pr.diffRefs().headSha());
        assertEquals("author-1", pr.author().providerUserId());
    }

    @Test
    void fetchesAndParsesDiff() {
        server.stubFor(get(urlEqualTo("/repositories/sandbox/demo-repo/pullrequests/42/diff"))
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
        server.stubFor(get(urlEqualTo("/repositories/sandbox/demo-repo/pullrequests/42/diff"))
                .willReturn(aResponse().withStatus(404)));
        BitbucketApiException e = assertThrows(BitbucketApiException.class,
                () -> diffSource.fetchDiff(REPO, 42, "gone"));
        assertTrue(e.isNotFound()); // force-pushed-away commit -> treat as superseded
    }

    @Test
    void postsSummaryComment() {
        stubCommentCreated();
        CommentRef ref = commentSink.postSummary(REPO, 42, "**Summary**");
        assertEquals("991", ref.commentId());
        server.verify(postRequestedFor(urlEqualTo("/repositories/sandbox/demo-repo/pullrequests/42/comments"))
                .withRequestBody(equalToJson("""
                        { "content": { "raw": "**Summary**" } }
                        """)));
    }

    @Test
    void postsInlineCommentOnNewSideWithTo() {
        stubCommentCreated();
        commentSink.postInline(REPO, 42, DiffRefs.headOnly("abc"),
                new InlineAnchor("src/App.java", "src/App.java", null, 7, Side.NEW), "note");
        server.verify(postRequestedFor(urlEqualTo("/repositories/sandbox/demo-repo/pullrequests/42/comments"))
                .withRequestBody(equalToJson("""
                        { "content": { "raw": "note" }, "inline": { "path": "src/App.java", "to": 7 } }
                        """)));
    }

    @Test
    void postsInlineCommentOnOldSideWithFrom() {
        stubCommentCreated();
        commentSink.postInline(REPO, 42, DiffRefs.headOnly("abc"),
                new InlineAnchor("src/App.java", "src/App.java", 5, null, Side.OLD), "removed?");
        server.verify(postRequestedFor(urlEqualTo("/repositories/sandbox/demo-repo/pullrequests/42/comments"))
                .withRequestBody(equalToJson("""
                        { "content": { "raw": "removed?" }, "inline": { "path": "src/App.java", "from": 5 } }
                        """)));
    }

    @Test
    void replyUsesParentIdAndNoInline() {
        stubCommentCreated();
        commentSink.replyInThread(REPO, 42, new ThreadRef("77"), "answer");
        server.verify(postRequestedFor(urlEqualTo("/repositories/sandbox/demo-repo/pullrequests/42/comments"))
                .withRequestBody(equalToJson("""
                        { "content": { "raw": "answer" }, "parent": { "id": 77 } }
                        """)));
    }

    @Test
    void fetchesPullRequestAuthorWithoutEmail() {
        server.stubFor(get(urlEqualTo("/repositories/sandbox/demo-repo/pullrequests/42"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "id": 42,
                          "author": { "account_id": "author-9", "nickname": "jdoe", "display_name": "J. Doe" } }
                        """)));
        var author = commentSink.getPullRequestAuthor(REPO, 42);
        assertEquals("author-9", author.providerUserId());
        assertEquals("jdoe", author.username());
        org.junit.jupiter.api.Assertions.assertNull(author.email()); // never carried (SCM-MAPPING §1)
    }

    @Test
    void whoamiResolvesTheTokenOwner() {
        server.stubFor(get(urlEqualTo("/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "account_id": "712020:abc-def", "username": "spire_bot", "display_name": "Code Spire Bot" }
                        """)));
        var me = diffSource.whoami();
        assertEquals("712020:abc-def", me.providerUserId()); // the account_id -> bot account id
        assertEquals("spire_bot", me.username());
        assertEquals("Code Spire Bot", me.displayName());
    }

    @Test
    void whoamiOnBadTokenSurfacesAs401() {
        server.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(401)));
        BitbucketApiException e = assertThrows(BitbucketApiException.class, () -> diffSource.whoami());
        assertEquals(401, e.status());
    }

    private void stubCommentCreated() {
        server.stubFor(post(urlEqualTo("/repositories/sandbox/demo-repo/pullrequests/42/comments"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"id\": 991 }")));
    }
}
