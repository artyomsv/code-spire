package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitLab ThreadSource: read a discussion transcript, fall back to the note tail for a plain-note ref. */
class GitLabThreadFetchTest {

    private static WireMockServer server;
    private static GitLabCommentSink sink;
    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final String MR = "/projects/sandbox%2Fdemo-repo/merge_requests/42";

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        GitLabClient client = new GitLabClient(
                new GitLabConfig("http://localhost:" + server.port(), "test-token"), new ObjectMapper());
        sink = new GitLabCommentSink(client);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
        server.stubFor(get(urlEqualTo("/user")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{ \"id\": 1, \"username\": \"code-spire\", \"name\": \"Code Spire\" }")));
    }

    @Test
    void fetchesDiscussionTranscriptWithAnchorAndBotAttribution() {
        server.stubFor(get(urlEqualTo(MR + "/discussions/DISC1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        { "id": "DISC1", "notes": [
                          { "id": 100, "system": false, "body": "possible NPE",
                            "author": { "username": "code-spire" },
                            "position": { "new_path": "src/App.java", "new_line": 42, "head_sha": "abc123" } },
                          { "id": 200, "system": false, "body": "why?", "author": { "username": "jdoe" } },
                          { "id": 201, "system": true, "body": "changed the description",
                            "author": { "username": "jdoe" } } ] }""")));

        ThreadTranscript t = sink.fetchThread(REPO, 42, new ThreadRef("DISC1"));

        assertEquals("src/App.java", t.path());
        assertEquals(42, t.line());
        assertEquals("abc123", t.commit());
        assertEquals(2, t.messages().size());          // 100 + 200; system note 201 dropped
        assertTrue(t.messages().get(0).fromBot());      // code-spire == token owner
        assertFalse(t.messages().get(1).fromBot());
        assertEquals("why?", t.messages().get(1).text());
    }

    @Test
    void fallsBackToNoteTailWhenRefIsNotADiscussion() {
        // A summary-note ref: GET /discussions/{noteId} 404s, so read the MR notes tail from that id.
        server.stubFor(get(urlEqualTo(MR + "/discussions/555"))
                .willReturn(aResponse().withStatus(404).withBody("{}")));
        server.stubFor(get(urlEqualTo(MR + "/notes?per_page=100&page=1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        [ { "id": 500, "system": false, "body": "earlier note", "author": { "username": "jdoe" } },
                          { "id": 555, "system": false, "body": "summary", "author": { "username": "code-spire" } },
                          { "id": 556, "system": false, "body": "thanks", "author": { "username": "jdoe" } } ]""")));

        ThreadTranscript t = sink.fetchThread(REPO, 42, new ThreadRef("555"));

        assertNull(t.path());
        assertEquals(0, t.line());
        assertNull(t.commit());
        assertEquals(2, t.messages().size());           // from 555 onward: 555 + 556, not 500
        assertTrue(t.messages().get(0).fromBot());
        assertEquals("thanks", t.messages().get(1).text());
    }

    @Test
    void replyFallsBackToPlainNoteWhenDiscussionMissing() {
        server.stubFor(post(urlPathEqualTo(MR + "/discussions/555/notes"))
                .willReturn(aResponse().withStatus(404).withBody("{}")));
        server.stubFor(post(urlPathEqualTo(MR + "/notes")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{ \"id\": 900 }")));

        sink.replyInThread(REPO, 42, new ThreadRef("555"), "here is my answer");

        server.verify(postRequestedFor(urlPathEqualTo(MR + "/notes")));
    }
}
