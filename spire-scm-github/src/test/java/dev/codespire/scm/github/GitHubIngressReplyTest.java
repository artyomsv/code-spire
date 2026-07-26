package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.port.RawWebhook;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GitHubIngressReplyTest {

    private final GitHubIngress ingress = new GitHubIngress("secret", new ObjectMapper(), Set.of("review"));

    @Test
    void reviewCommentReplyBecomesAuthorReplied() {
        byte[] body = """
                { "action": "created",
                  "repository": { "full_name": "artyomsv/spire-test" },
                  "pull_request": { "number": 5 },
                  "comment": { "id": 200, "in_reply_to_id": 100,
                               "body": "why is this a bug?",
                               "user": { "id": 42, "login": "octocat" } } }
                """.getBytes(StandardCharsets.UTF_8);
        AuthorReplied e = assertInstanceOf(AuthorReplied.class,
                ingress.translate(webhook(body, "pull_request_review_comment")).getFirst());
        assertEquals(5, e.prId());
        assertEquals("100", e.threadRef().value());   // the thread ROOT, not this reply's id
        assertEquals("200", e.commentId());
        assertEquals("why is this a bug?", e.text());
        assertEquals("42", e.author().providerUserId());
        assertEquals("review::artyomsv/spire-test#5", e.reviewId());
    }

    @Test
    void topLevelReviewCommentUsesItsOwnIdAsThreadRoot() {
        byte[] body = """
                { "action": "created",
                  "repository": { "full_name": "artyomsv/spire-test" },
                  "pull_request": { "number": 5 },
                  "comment": { "id": 100, "body": "note", "user": { "id": 42, "login": "octocat" } } }
                """.getBytes(StandardCharsets.UTF_8);
        AuthorReplied e = assertInstanceOf(AuthorReplied.class,
                ingress.translate(webhook(body, "pull_request_review_comment")).getFirst());
        assertEquals("100", e.threadRef().value());
    }

    /**
     * The ingress extracts who was @-mentioned, because only it knows that GitHub renders a mention
     * as {@code @login} in the raw body. The orchestrator then just checks whether the bot is in the
     * list — it never sees this syntax.
     */
    @Test
    void mentionsAreExtractedFromTheBodyInGitHubsOwnSyntax() {
        byte[] body = """
                { "action": "created",
                  "repository": { "full_name": "artyomsv/spire-test" },
                  "pull_request": { "number": 5 },
                  "comment": { "id": 100, "body": "hey @code-spire and @octo-cat, thoughts? me@example.com",
                               "user": { "id": 42, "login": "octocat" } } }
                """.getBytes(StandardCharsets.UTF_8);
        AuthorReplied e = assertInstanceOf(AuthorReplied.class,
                ingress.translate(webhook(body, "pull_request_review_comment")).getFirst());
        // Every @token is collected; which one is the bot is the saga's decision. An email's domain
        // shows up as a token (logins carry no dots, so it stops at "example") — harmless, because it
        // can only matter if it equals the bot's actual login.
        assertEquals(List.of("code-spire", "octo-cat", "example"), e.mentions());
    }

    @Test
    void aBodyWithNoMentionsCarriesAnEmptyList() {
        byte[] body = """
                { "action": "created",
                  "repository": { "full_name": "artyomsv/spire-test" },
                  "pull_request": { "number": 5 },
                  "comment": { "id": 100, "body": "no mentions at all",
                               "user": { "id": 42, "login": "octocat" } } }
                """.getBytes(StandardCharsets.UTF_8);
        AuthorReplied e = assertInstanceOf(AuthorReplied.class,
                ingress.translate(webhook(body, "pull_request_review_comment")).getFirst());
        assertEquals(List.of(), e.mentions());
    }

    private static RawWebhook webhook(byte[] body, String event) {
        return new RawWebhook(Map.of("X-GitHub-Event", event), body);
    }
}
