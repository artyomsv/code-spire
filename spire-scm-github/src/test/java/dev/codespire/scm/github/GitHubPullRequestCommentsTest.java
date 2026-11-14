package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.codespire.contract.scm.PullRequestComment;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Step 8b: a pull request's conversation and inline comments, read with their authors' stable ids. */
class GitHubPullRequestCommentsTest {

    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private static final String ISSUE = "/repos/TEST-owner/TEST-repo/issues/5/comments?per_page=100&page=";
    private static final String INLINE = "/repos/TEST-owner/TEST-repo/pulls/5/comments?per_page=100&page=";

    private final ObjectMapper mapper = new ObjectMapper();
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        client = mock(GitHubClient.class);
        when(client.getJson(anyString())).thenReturn(mapper.createArrayNode());
    }

    @Test
    void readsConversationAndInlineCommentsWithAuthorIds() throws Exception {
        when(client.getJson(ISSUE + 1)).thenReturn(mapper.readTree("""
                [ { "id": 1, "body": "see TEST-1", "user": { "id": 101, "login": "TEST-maintainer" } },
                  { "id": 2, "body": "   ", "user": { "id": 102, "login": "TEST-blank" } } ]"""));
        when(client.getJson(INLINE + 1)).thenReturn(mapper.readTree("""
                [ { "id": 3, "body": "and TEST-2", "user": { "id": 103, "login": "TEST-reviewer" } } ]"""));

        List<PullRequestComment> comments = new GitHubDiffSource(client).fetchPullRequestComments(REPO, 5);

        assertEquals(2, comments.size(), comments.toString());
        assertEquals("101", comments.get(0).author().providerUserId());
        assertEquals("TEST-maintainer", comments.get(0).author().username());
        assertEquals("see TEST-1", comments.get(0).body());
        assertEquals("103", comments.get(1).author().providerUserId());
    }

    @Test
    void aFullPageReadsTheNextOne() {
        when(client.getJson(ISSUE + 1)).thenReturn(page(100));
        when(client.getJson(ISSUE + 2)).thenReturn(page(1));

        List<PullRequestComment> comments = new GitHubDiffSource(client).fetchPullRequestComments(REPO, 5);

        assertEquals(101, comments.size());
        verify(client, never()).getJson(ISSUE + 3);
    }

    @Test
    void theWalkStopsAtThePageCap() {
        when(client.getJson(anyString())).thenReturn(page(100));

        List<PullRequestComment> comments = new GitHubDiffSource(client).fetchPullRequestComments(REPO, 5);

        assertEquals(2 * GitHubPullRequestComments.MAX_PAGES * 100, comments.size());
        verify(client, never()).getJson(ISSUE + (GitHubPullRequestComments.MAX_PAGES + 1));
    }

    private JsonNode page(int size) {
        ArrayNode rows = mapper.createArrayNode();
        for (int i = 0; i < size; i++) {
            rows.addObject().put("id", i).put("body", "TEST-" + i)
                    .putObject("user").put("id", 200 + i).put("login", "TEST-user");
        }
        return rows;
    }
}
