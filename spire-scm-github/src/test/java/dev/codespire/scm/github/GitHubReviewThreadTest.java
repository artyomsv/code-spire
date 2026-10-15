package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A {@code review:<id>} thread ref (ADR-047 D8): the summary is a review, not an issue comment. */
class GitHubReviewThreadTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RepoRef repo = new RepoRef("TEST-owner", "TEST-repo");

    @Test
    void aReplyToAReviewRefPostsAnIssueComment() throws Exception {
        GitHubClient client = mock(GitHubClient.class);
        when(client.postJson("/repos/TEST-owner/TEST-repo/issues/5/comments", Map.of("body", "TEST answer")))
                .thenReturn(mapper.readTree("{\"id\":777}"));
        CommentRef posted = new GitHubCommentSink(client, "TEST-bot").replyInThread(repo, 5, new ThreadRef("review:501"), "TEST answer");
        assertEquals("777", posted.commentId());
        verify(client, never()).postJson(contains("/replies"), any());
    }

    @Test
    void fetchesAReviewRefAsBodyThenLaterComments() throws Exception {
        GitHubClient client = mock(GitHubClient.class);
        when(client.getJson("/repos/TEST-owner/TEST-repo/pulls/5/reviews/501")).thenReturn(mapper.readTree("""
                {"id":501,"body":"### Code Spire review (`abc`)\\n\\nTEST summary","submitted_at":"2026-10-15T10:00:00Z","user":{"login":"TEST-bot"}}
                """));
        when(client.getJson("/repos/TEST-owner/TEST-repo/issues/5/comments?per_page=100&page=1")).thenReturn(mapper.readTree("""
                [ {"id":1,"body":"before the review","created_at":"2026-10-15T09:59:00Z","user":{"login":"TEST-human"}},
                  {"id":2,"body":"why this?","created_at":"2026-10-15T10:01:00Z","user":{"login":"TEST-human"}} ]
                """));
        ThreadTranscript t = new GitHubCommentSink(client, "TEST-bot").fetchThread(repo, 5, new ThreadRef("review:501"));
        assertNull(t.path());
        assertEquals(2, t.messages().size(), "the body, then only the comments after submitted_at");
        assertTrue(t.messages().get(0).fromBot());
        assertTrue(t.messages().get(0).text().startsWith("### Code Spire review"));
        assertEquals("why this?", t.messages().get(1).text());
        assertFalse(t.messages().get(1).fromBot());
    }

    @Test
    void updatingAReviewRefAsACommentIsRefused() {
        GitHubClient client = mock(GitHubClient.class);
        assertThrows(UnsupportedOperationException.class,
                () -> new GitHubCommentSink(client, "TEST-bot").updateComment(repo, 5, new ThreadRef("review:501"), "x"));
    }
}
