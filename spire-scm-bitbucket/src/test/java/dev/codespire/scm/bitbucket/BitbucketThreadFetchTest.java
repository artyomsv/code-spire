package dev.codespire.scm.bitbucket;

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
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bitbucket ThreadSource: rebuild a comment subtree under the root id, attribute the bot by account_id. */
class BitbucketThreadFetchTest {

    private static WireMockServer server;
    private static BitbucketCloudCommentSink sink;
    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final String COMMENTS = "/repositories/sandbox/demo-repo/pullrequests/7/comments";

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        BitbucketCloudClient client = new BitbucketCloudClient(
                new BitbucketCloudConfig("http://localhost:" + server.port(),
                        "test-bot", "test-app-password", "test-secret"), new ObjectMapper());
        sink = new BitbucketCloudCommentSink(client);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/user")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{ \"account_id\": \"BOT-1\", \"nickname\": \"code-spire\" }")));
    }

    @Test
    void rebuildsSubtreeUnderRootAndAttributesBot() {
        server.stubFor(get(urlPathEqualTo(COMMENTS)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        { "values": [
                          { "id": 100, "content": { "raw": "possible NPE" },
                            "user": { "account_id": "BOT-1" }, "inline": { "path": "src/App.java", "to": 42 } },
                          { "id": 200, "parent": { "id": 100 }, "content": { "raw": "why?" },
                            "user": { "account_id": "HUM-9" } },
                          { "id": 300, "content": { "raw": "unrelated" }, "user": { "account_id": "HUM-9" } } ] }""")));

        ThreadTranscript t = sink.fetchThread(REPO, 7, new ThreadRef("100"));

        assertEquals("src/App.java", t.path());
        assertEquals(42, t.line());
        assertNull(t.commit());
        assertEquals(2, t.messages().size());              // 100 + 200, not 300
        assertTrue(t.messages().get(0).fromBot());          // BOT-1
        assertFalse(t.messages().get(1).fromBot());
        assertEquals("why?", t.messages().get(1).text());
    }

    @Test
    void fromAMidThreadReplyWalksUpToRootAndReturnsTheWholeThread() {
        // A human replied to the bot's OWN answer (id 250), so the fetch ref is mid-thread. The
        // transcript must still be the WHOLE conversation rooted at the finding (100) — parity with
        // GitHub/GitLab — not just the branch under 250.
        server.stubFor(get(urlPathEqualTo(COMMENTS)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        { "values": [
                          { "id": 100, "content": { "raw": "possible NPE" },
                            "user": { "account_id": "BOT-1" }, "inline": { "path": "src/App.java", "to": 42 } },
                          { "id": 200, "parent": { "id": 100 }, "content": { "raw": "why?" },
                            "user": { "account_id": "HUM-9" } },
                          { "id": 250, "parent": { "id": 200 }, "content": { "raw": "here is why" },
                            "user": { "account_id": "BOT-1" } },
                          { "id": 300, "parent": { "id": 250 }, "content": { "raw": "explain again" },
                            "user": { "account_id": "HUM-9" } } ] }""")));

        ThreadTranscript t = sink.fetchThread(REPO, 7, new ThreadRef("250"));

        assertEquals("src/App.java", t.path(), "anchor resolves from the true root (100), not the mid ref");
        assertEquals(42, t.line());
        assertEquals(4, t.messages().size(), "the whole thread (100+200+250+300), not just 250's branch");
        assertTrue(t.messages().get(0).fromBot());          // 100 BOT-1 finding
        assertEquals("explain again", t.messages().get(3).text());
    }

    @Test
    void followsPaginationViaNextAndCollectsFullSubtree() {
        server.stubFor(get(urlEqualTo(COMMENTS + "?pagelen=100&page=1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        { "values": [
                          { "id": 100, "content": { "raw": "possible NPE" },
                            "user": { "account_id": "BOT-1" }, "inline": { "path": "src/App.java", "to": 42 } },
                          { "id": 200, "parent": { "id": 100 }, "content": { "raw": "reply on page1" },
                            "user": { "account_id": "HUM-9" } } ],
                          "next": "http://localhost/anything" }""")));
        server.stubFor(get(urlEqualTo(COMMENTS + "?pagelen=100&page=2")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        { "values": [
                          { "id": 400, "parent": { "id": 200 }, "content": { "raw": "reply on page2" },
                            "user": { "account_id": "HUM-9" } } ] }""")));

        ThreadTranscript t = sink.fetchThread(REPO, 7, new ThreadRef("100"));

        assertEquals("src/App.java", t.path());
        assertEquals(42, t.line());
        assertEquals(3, t.messages().size());               // 100 + 200 (page 1) + 400 (page 2)
        assertEquals("reply on page2", t.messages().get(2).text());
    }

    @Test
    void degradesToUnattributedWhenBotAccountLookupFails() {
        server.stubFor(get(urlPathEqualTo("/user")).willReturn(aResponse().withStatus(503)));
        server.stubFor(get(urlPathEqualTo(COMMENTS)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        { "values": [
                          { "id": 100, "content": { "raw": "possible NPE" },
                            "user": { "account_id": "BOT-1" }, "inline": { "path": "src/App.java", "to": 42 } } ] }""")));

        ThreadTranscript t = sink.fetchThread(REPO, 7, new ThreadRef("100"));

        assertEquals(1, t.messages().size());
        assertFalse(t.messages().get(0).fromBot());        // /user lookup failed -> botAccountId is "" -> no match
    }
}
