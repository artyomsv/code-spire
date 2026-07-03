package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.event.IntegrationEvent.CloseReason;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.port.RawWebhook;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitbucketCloudIngressTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BOT_ACCOUNT_ID = "bot-account-000";

    private final BitbucketCloudIngress ingress = new BitbucketCloudIngress(
            new BitbucketCloudConfig("https://api.example.invalid/2.0",
                    "test-bot", "test-app-password", SECRET, BOT_ACCOUNT_ID),
            new ObjectMapper(),
            Set.of("review"));

    // --- signature ---

    @Test
    void acceptsValidHmacSignature() throws Exception {
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        assertTrue(ingress.verifySignature(webhook(body, Map.of(
                "X-Hub-Signature", "sha256=" + hmac(body),
                "X-Event-Key", "pullrequest:created"))));
    }

    @Test
    void rejectsInvalidMissingOrMalformedSignature() {
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        assertFalse(ingress.verifySignature(webhook(body, Map.of(
                "X-Hub-Signature", "sha256=" + "00".repeat(32)))));
        assertFalse(ingress.verifySignature(webhook(body, Map.of())));
        assertFalse(ingress.verifySignature(webhook(body, Map.of("X-Hub-Signature", "sha256=nothex"))));
        assertFalse(ingress.verifySignature(webhook(body, Map.of("X-Hub-Signature", "sha1=abcd"))));
    }

    // --- translation ---

    @Test
    void translatesPullRequestCreated() {
        List<IntegrationEvent> events = ingress.translate(webhook(PR_CREATED,
                Map.of("X-Event-Key", "pullrequest:created")));
        assertEquals(1, events.size());
        PullRequestEventReceived e = assertInstanceOf(PullRequestEventReceived.class, events.getFirst());
        assertEquals("sandbox", e.repo().workspace());
        assertEquals("demo-repo", e.repo().slug());
        assertEquals(42, e.prId());
        assertEquals(PrAction.OPENED, e.action());
        assertEquals("Add feature", e.title());
        assertEquals("feature/x", e.sourceBranch());
        assertEquals("main", e.targetBranch());
        assertEquals("abc123def456", e.diffRefs().headSha()); // as delivered (12-char)
        assertEquals("author-account-1", e.author().providerUserId());
        assertEquals("jdoe", e.author().username());
    }

    @Test
    void translatesMergedAndDeclinedToClosed() {
        PullRequestClosed merged = assertInstanceOf(PullRequestClosed.class, ingress.translate(
                webhook(PR_CREATED, Map.of("X-Event-Key", "pullrequest:fulfilled"))).getFirst());
        assertEquals(CloseReason.MERGED, merged.reason());

        PullRequestClosed declined = assertInstanceOf(PullRequestClosed.class, ingress.translate(
                webhook(PR_CREATED, Map.of("X-Event-Key", "pullrequest:rejected"))).getFirst());
        assertEquals(CloseReason.DECLINED, declined.reason());
    }

    @Test
    void dropsBotAuthoredComments() {
        // ADR-013 self-loop guard: the bot's own follow-up must not re-trigger it.
        String payload = comment(BOT_ACCOUNT_ID, "Thanks, fixed!", null);
        assertTrue(ingress.translate(webhook(payload.getBytes(StandardCharsets.UTF_8),
                Map.of("X-Event-Key", "pullrequest:comment_created"))).isEmpty());
    }

    @Test
    void parsesRegisteredSlashCommand() {
        String payload = comment("human-1", "/review please", null);
        ManualCommandReceived e = assertInstanceOf(ManualCommandReceived.class, ingress.translate(
                webhook(payload.getBytes(StandardCharsets.UTF_8),
                        Map.of("X-Event-Key", "pullrequest:comment_created"))).getFirst());
        assertEquals("review", e.command());
        assertEquals("please", e.args());
    }

    @Test
    void unregisteredSlashTextIsATreatedAsReply() {
        String payload = comment("human-1", "/unknown thing", "77");
        assertInstanceOf(AuthorReplied.class, ingress.translate(
                webhook(payload.getBytes(StandardCharsets.UTF_8),
                        Map.of("X-Event-Key", "pullrequest:comment_created"))).getFirst());
    }

    @Test
    void replyThreadsOnRootCommentId() {
        String withParent = comment("human-1", "why?", "77");
        AuthorReplied reply = assertInstanceOf(AuthorReplied.class, ingress.translate(
                webhook(withParent.getBytes(StandardCharsets.UTF_8),
                        Map.of("X-Event-Key", "pullrequest:comment_created"))).getFirst());
        assertEquals("77", reply.threadRef().value()); // parent id, not own id
        assertEquals("991", reply.commentId());

        String topLevel = comment("human-1", "standalone note", null);
        AuthorReplied root = assertInstanceOf(AuthorReplied.class, ingress.translate(
                webhook(topLevel.getBytes(StandardCharsets.UTF_8),
                        Map.of("X-Event-Key", "pullrequest:comment_created"))).getFirst());
        assertEquals("991", root.threadRef().value()); // own id becomes the thread root
    }

    @Test
    void unknownEventKeyYieldsNothing() {
        assertTrue(ingress.translate(webhook(PR_CREATED, Map.of("X-Event-Key", "repo:updated"))).isEmpty());
        assertTrue(ingress.translate(webhook(PR_CREATED, Map.of())).isEmpty());
    }

    // --- fixtures ---

    private static final byte[] PR_CREATED = """
            {
              "repository": { "full_name": "sandbox/demo-repo" },
              "pullrequest": {
                "id": 42,
                "title": "Add feature",
                "description": "Adds the feature.",
                "source": { "branch": { "name": "feature/x" }, "commit": { "hash": "abc123def456" } },
                "destination": { "branch": { "name": "main" } },
                "author": { "account_id": "author-account-1", "nickname": "jdoe", "display_name": "J. Doe" },
                "links": { "html": { "href": "https://bitbucket.org/sandbox/demo-repo/pull-requests/42" } }
              }
            }
            """.getBytes(StandardCharsets.UTF_8);

    private static String comment(String accountId, String text, String parentId) {
        String parent = parentId == null ? "" : ", \"parent\": { \"id\": " + parentId + " }";
        return """
                {
                  "repository": { "full_name": "sandbox/demo-repo" },
                  "pullrequest": { "id": 42 },
                  "comment": {
                    "id": 991,
                    "content": { "raw": "%s" },
                    "user": { "account_id": "%s", "nickname": "nick", "display_name": "Nick" }%s
                  }
                }
                """.formatted(text, accountId, parent);
    }

    private static RawWebhook webhook(byte[] body, Map<String, String> headers) {
        return new RawWebhook(headers, body);
    }

    private static String hmac(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
