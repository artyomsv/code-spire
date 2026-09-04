package dev.codespire.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.port.ScmIngress;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.scm.bitbucket.BitbucketCloudConfig;
import dev.codespire.scm.bitbucket.BitbucketCloudIngress;
import dev.codespire.scm.github.GitHubIngress;
import dev.codespire.scm.gitlab.GitLabIngress;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * One user action, one event shape — across every provider.
 *
 * <p>The defect this guards against is not a wrong value in any single ingress; it is that the
 * three DISAGREED. A slash command typed in an inline thread produced four different outcomes
 * across three SCMs, and no per-provider test could see it because each asserted its own
 * behaviour and passed.
 */
class IngressCommandParityTest {

    private static final String SECRET = "test-webhook-secret";

    private record Case(String provider, List<IntegrationEvent> events) {
    }

    /**
     * Each provider's own payload for {@code text}, typed in an inline thread on src/Foo.java line
     * 44, by octocat, on PR/MR 7 of acme/widgets. Parameterised so a new command is one call rather
     * than a fourth copy of three fixtures — the copies are what let the providers diverge before.
     */
    private static List<Case> inlineCommandOnEveryProvider(String text) {
        return List.of(
                new Case("github", githubIngress().translate(webhook(
                        githubInlineComment(text),
                        Map.of("X-GitHub-Event", "pull_request_review_comment")))),
                new Case("gitlab", gitlabIngress().translate(webhook(
                        gitlabInlineNote(text), Map.of()))),
                new Case("bitbucket", bitbucketIngress().translate(webhook(
                        bitbucketInlineComment(text),
                        Map.of("X-Event-Key", "pullrequest:comment_created")))));
    }

    private static List<Case> unrecognisedSlashOnEveryProvider() {
        String text = "/usr/lib is the wrong path here";
        return List.of(
                new Case("github", githubIngress().translate(webhook(
                        githubInlineComment(text), Map.of("X-GitHub-Event", "pull_request_review_comment")))),
                new Case("gitlab", gitlabIngress().translate(webhook(gitlabInlineNote(text), Map.of()))),
                new Case("bitbucket", bitbucketIngress().translate(webhook(
                        bitbucketInlineComment(text), Map.of("X-Event-Key", "pullrequest:comment_created")))));
    }

    /**
     * {@code /fix} is the M2 command, and it is the one that costs the most to get wrong on one
     * provider: it dispatches a paid agent run that pushes a branch. A provider left out of the
     * shared command set routes it to the conversation path instead, where it becomes an ordinary
     * reply — so the operator sees the bot answer a question nobody asked and no run ever starts.
     *
     * <p>The thread ref is what makes it dispatchable at all: a fix is dispatched against the
     * FINDING the thread belongs to, so a provider that dropped the ref would translate a valid
     * command into one with no target.
     */
    @Test
    void everyProviderTurnsAnInlineFixCommandIntoTheSameCommandEvent() {
        for (Case c : inlineCommandOnEveryProvider("/fix rename the shadowed field")) {
            assertEquals(1, c.events().size(), "provider " + c.provider());
            ManualCommandReceived e = assertInstanceOf(ManualCommandReceived.class, c.events().getFirst(),
                    "provider " + c.provider());
            assertEquals("fix", e.command(), c.provider() + " command");
            assertEquals("rename the shadowed field", e.args(), c.provider() + " args");
            assertEquals(7, e.prId(), c.provider() + " prId");
            assertEquals(new ThreadLocation("src/Foo.java", 44), e.location(), c.provider() + " location");
            assertNotNull(e.threadRef(), c.provider() + " threadRef");
        }
    }

    @Test
    void everyProviderTurnsAnInlineSlashCommandIntoTheSameCommandEvent() {
        for (Case c : inlineCommandOnEveryProvider("/finding major shadows the field")) {
            assertEquals(1, c.events().size(), "provider " + c.provider());
            ManualCommandReceived e = assertInstanceOf(ManualCommandReceived.class, c.events().getFirst(),
                    "provider " + c.provider());
            assertEquals("finding", e.command(), c.provider() + " command");
            assertEquals("major shadows the field", e.args(), c.provider() + " args");
            assertEquals(7, e.prId(), c.provider() + " prId");
            assertEquals(new ThreadLocation("src/Foo.java", 44), e.location(), c.provider() + " location");
            // The ref is opaque and genuinely different per provider (a comment id, a discussion id) --
            // the parity property is that it is CARRIED, not that it matches across providers.
            assertNotNull(e.threadRef(), c.provider() + " threadRef");
        }
    }

    @Test
    void everyProviderTreatsAnUnrecognisedSlashWordAsAComment() {
        for (Case c : unrecognisedSlashOnEveryProvider()) {
            assertEquals(1, c.events().size(), "provider " + c.provider());
            assertInstanceOf(AuthorReplied.class, c.events().getFirst(), "provider " + c.provider());
        }
    }

    @Test
    void theParityCasesCoverEveryProvider() {
        // Guards the guard: a case list that silently lost a provider would make both tests above
        // pass while covering less. Same shape as spire-arch's own "the scan reached every core
        // module" assertion.
        List<String> providers = inlineCommandOnEveryProvider("/finding x").stream()
                .map(Case::provider).sorted().toList();
        assertEquals(List.of("bitbucket", "github", "gitlab"), providers);
    }

    // --- one ingress per provider, wired with the same shared command set the real edges use ---

    private static ScmIngress githubIngress() {
        return new GitHubIngress(SECRET, new ObjectMapper(), WebhookCommands.SUPPORTED);
    }

    private static ScmIngress gitlabIngress() {
        return new GitLabIngress(SECRET, new ObjectMapper(), WebhookCommands.SUPPORTED);
    }

    private static ScmIngress bitbucketIngress() {
        return new BitbucketCloudIngress(
                new BitbucketCloudConfig("https://api.example.invalid/2.0", "test-bot", "test-app-password", SECRET),
                new ObjectMapper(), WebhookCommands.SUPPORTED);
    }

    // --- one fixture per provider, same JSON shapes as Tasks 2-4's own ingress tests ---

    /** GitHub: a reply on a pull_request_review_comment thread; in_reply_to_id is the thread root. */
    private static byte[] githubInlineComment(String body) {
        return """
                {
                  "action": "created",
                  "repository": { "full_name": "acme/widgets" },
                  "pull_request": { "number": 7 },
                  "comment": {
                    "id": 901,
                    "in_reply_to_id": 900,
                    "body": "%s",
                    "path": "src/Foo.java",
                    "line": 44,
                    "user": { "id": 4242, "login": "octocat" }
                  }
                }
                """.formatted(body).getBytes(StandardCharsets.UTF_8);
    }

    /** GitLab: a DiffNote carrying both its discussion id and its diff position. */
    private static byte[] gitlabInlineNote(String note) {
        return """
                {
                  "object_kind": "note",
                  "project": { "path_with_namespace": "acme/widgets" },
                  "user": { "id": 4242, "username": "octocat", "name": "Octo Cat" },
                  "merge_request": { "iid": 7 },
                  "object_attributes": {
                    "noteable_type": "MergeRequest", "type": "DiffNote",
                    "id": 901, "discussion_id": "disc-900", "note": "%s",
                    "position": { "new_path": "src/Foo.java", "new_line": 44 }
                  }
                }
                """.formatted(note).getBytes(StandardCharsets.UTF_8);
    }

    /** Bitbucket Cloud: a comment carrying both its parent thread and its own inline diff anchor. */
    private static byte[] bitbucketInlineComment(String text) {
        return """
                {
                  "repository": { "full_name": "acme/widgets" },
                  "pullrequest": { "id": 7 },
                  "comment": { "id": 901, "parent": { "id": 900 },
                    "inline": { "path": "src/Foo.java", "to": 44 },
                    "content": { "raw": "%s" },
                    "user": { "account_id": "4242", "nickname": "octocat", "display_name": "Octo Cat" } }
                }
                """.formatted(text).getBytes(StandardCharsets.UTF_8);
    }

    private static RawWebhook webhook(byte[] body, Map<String, String> headers) {
        return new RawWebhook(headers, body);
    }
}
