package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubThreadFetchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fetchThreadFiltersToTheThreadAndReadsTheAnchor() throws Exception {
        GitHubClient client = mock(GitHubClient.class);
        when(client.getJson("/user")).thenReturn(mapper.readTree("{\"login\":\"code-spire\"}"));
        when(client.getJson("/repos/artyomsv/spire-test/pulls/5/comments?per_page=100&page=1")).thenReturn(mapper.readTree("""
                [ { "id": 100, "in_reply_to_id": null, "path": "src/App.java", "original_line": 42,
                    "commit_id": "abc123", "body": "possible NPE", "user": { "login": "code-spire" },
                    "created_at": "2026-07-16T10:00:00Z" },
                  { "id": 200, "in_reply_to_id": 100, "body": "why?", "user": { "login": "octocat" },
                    "created_at": "2026-07-16T10:01:00Z" },
                  { "id": 999, "in_reply_to_id": 500, "body": "other thread", "user": { "login": "x" },
                    "created_at": "2026-07-16T10:02:00Z" } ]
                """));

        GitHubCommentSink sink = new GitHubCommentSink(client);
        ThreadTranscript t = sink.fetchThread(new RepoRef("artyomsv", "spire-test"), 5, new ThreadRef("100"));

        assertEquals("src/App.java", t.path());
        assertEquals(42, t.line());
        assertEquals("abc123", t.commit());
        assertEquals(2, t.messages().size());                 // 100 + 200, not 999
        assertTrue(t.messages().get(0).fromBot());            // code-spire == token owner
        assertFalse(t.messages().get(1).fromBot());
        assertEquals("why?", t.messages().get(1).text());
    }

    @Test
    void aTransientFailureResolvingTheBotLoginStillReturnsTheThread() throws Exception {
        GitHubClient client = mock(GitHubClient.class);
        // GitHub 503s on the non-essential /user lookup — the follow-up must still be answerable.
        when(client.getJson("/user")).thenThrow(new GitHubApiException(503, "GET", "/user", "Unicorn"));
        when(client.getJson("/repos/artyomsv/spire-test/pulls/5/comments?per_page=100&page=1")).thenReturn(mapper.readTree("""
                [ { "id": 100, "in_reply_to_id": null, "path": "src/App.java", "original_line": 42,
                    "commit_id": "abc123", "body": "@code-spire what about this?", "user": { "login": "octocat" },
                    "created_at": "2026-07-16T10:00:00Z" } ]
                """));

        GitHubCommentSink sink = new GitHubCommentSink(client);
        ThreadTranscript t = sink.fetchThread(new RepoRef("artyomsv", "spire-test"), 5, new ThreadRef("100"));

        assertEquals("src/App.java", t.path());
        assertEquals("abc123", t.commit());
        assertEquals(1, t.messages().size());
        assertFalse(t.messages().get(0).fromBot());           // login unknown -> best-effort, not the bot
    }
}
