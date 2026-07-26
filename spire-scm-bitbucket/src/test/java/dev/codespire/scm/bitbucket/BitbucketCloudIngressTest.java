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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitbucketCloudIngressTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BOT_ACCOUNT_ID = "bot-account-000";

    private static final BitbucketCloudConfig CONFIG = new BitbucketCloudConfig(
            "https://api.example.invalid/2.0", "test-bot", "test-app-password", SECRET);

    private final BitbucketCloudIngress ingress = new BitbucketCloudIngress(
            CONFIG, new ObjectMapper(), Set.of("review"));

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
        assertEquals("abc123def456", e.headCommit()); // as delivered (12-char)
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
    void forwardsBotAuthoredCommentsCarryingTheAuthor() {
        // The self-loop guard (ADR-013) moved to the orchestrator, which holds the
        // registry's bot account id. The ingress no longer drops the bot's own
        // comment — it forwards it with the author so the orchestrator can drop it.
        String payload = comment(BOT_ACCOUNT_ID, "Thanks, fixed!", null);
        AuthorReplied e = assertInstanceOf(AuthorReplied.class, ingress.translate(
                webhook(payload.getBytes(StandardCharsets.UTF_8),
                        Map.of("X-Event-Key", "pullrequest:comment_created"))).getFirst());
        assertEquals(BOT_ACCOUNT_ID, e.author().providerUserId());
    }

    /**
     * Bitbucket writes a mention into RAW comment text as {@code @{account_id}} — the login never
     * appears — so the braced id is what the orchestrator has to be able to compare against the
     * bot's identity. Extracting it here is what keeps that syntax out of the shared saga, which
     * previously carried it alongside the other providers' {@code @login} form.
     */
    @Test
    void mentionsAreExtractedFromRawTextInBitbucketsBracedSyntax() {
        String payload = comment("human-1", "@{TEST-account-0001} can you look? cc @nickname", "77");
        AuthorReplied e = assertInstanceOf(AuthorReplied.class, ingress.translate(
                webhook(payload.getBytes(StandardCharsets.UTF_8),
                        Map.of("X-Event-Key", "pullrequest:comment_created"))).getFirst());
        assertEquals(List.of("TEST-account-0001", "nickname"), e.mentions(),
                "the braced account id is collected, and a plain @name alongside it");
    }

    @Test
    void aCommentWithNoMentionsCarriesAnEmptyList() {
        String payload = comment("human-1", "plain reply, no mentions", "77");
        AuthorReplied e = assertInstanceOf(AuthorReplied.class, ingress.translate(
                webhook(payload.getBytes(StandardCharsets.UTF_8),
                        Map.of("X-Event-Key", "pullrequest:comment_created"))).getFirst());
        assertEquals(List.of(), e.mentions());
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
    void draftPrCreatedIsSkippedByDefault() {
        assertTrue(ingress.translate(webhook(prDraft(true), Map.of("X-Event-Key", "pullrequest:created"))).isEmpty());
    }

    @Test
    void draftClearedOnUpdateIsReviewed() {
        assertEquals(1, ingress.translate(webhook(prDraft(false),
                Map.of("X-Event-Key", "pullrequest:updated"))).size());
    }

    @Test
    void reviewDraftsTrueReviewsBitbucketDrafts() {
        BitbucketCloudIngress permissive = new BitbucketCloudIngress(
                CONFIG, new ObjectMapper(), Set.of("review"), true);   // reuse the test's CONFIG constant
        assertEquals(1, permissive.translate(webhook(prDraft(true),
                Map.of("X-Event-Key", "pullrequest:created"))).size());
    }

    @Test
    void unknownEventKeyYieldsNothing() {
        assertTrue(ingress.translate(webhook(PR_CREATED, Map.of("X-Event-Key", "repo:updated"))).isEmpty());
        assertTrue(ingress.translate(webhook(PR_CREATED, Map.of())).isEmpty());
    }

    @Test
    void plainTopLevelCommentIsTopLevel() {
        var events = ingress.translate(webhook(commentWith("what about nulls?", null, null),
                Map.of("X-Event-Key", "pullrequest:comment_created")));
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertTrue(e.topLevel());
    }

    @Test
    void inlineCommentIsNotTopLevel() {
        var events = ingress.translate(webhook(commentWith("NPE here", null, "src/App.java"),
                Map.of("X-Event-Key", "pullrequest:comment_created")));
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertFalse(e.topLevel());
    }

    @Test
    void replyIsNotTopLevel() {
        var events = ingress.translate(webhook(commentWith("agreed", "100", null),
                Map.of("X-Event-Key", "pullrequest:comment_created")));
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertFalse(e.topLevel());
        assertEquals("100", e.threadRef().value());
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

    private static byte[] prDraft(boolean draft) {
        return ("""
                { "repository": { "full_name": "sandbox/demo-repo" },
                  "pullrequest": { "id": 7, "draft": %b, "title": "Add feature", "description": "d",
                    "source": { "branch": { "name": "f" }, "commit": { "hash": "abc123" } },
                    "destination": { "branch": { "name": "main" } },
                    "author": { "account_id": "HUM-9", "nickname": "jdoe", "display_name": "Jane" },
                    "links": { "html": { "href": "http://bb/pr/7" } } } }""")
                .formatted(draft).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

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

    private static byte[] commentWith(String body, String parentId, String inlinePath) {
        String parent = parentId == null ? "" : ", \"parent\": { \"id\": " + parentId + " }";
        String inline = inlinePath == null ? "" : ", \"inline\": { \"path\": \"" + inlinePath + "\", \"to\": 5 }";
        return ("""
                {
                  "repository": { "full_name": "sandbox/demo-repo" },
                  "pullrequest": { "id": 7 },
                  "comment": { "id": 900, "content": { "raw": "%s" },
                    "user": { "account_id": "HUM-9", "nickname": "jdoe", "display_name": "Jane" }%s%s }
                }""").formatted(body, parent, inline).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // --- where the thread sits in the diff (drives UI placement and the flagged-line policy) ---

    @Test
    void anInlineCommentCarriesItsFileAndLine() {
        AuthorReplied e = replyFrom(commentInline(
                ", \"inline\": { \"path\": \"src/App.java\", \"to\": 42 }"));
        assertNotNull(e.location());
        assertEquals("src/App.java", e.location().path());
        assertEquals(42, e.location().line());
    }

    /** A comment on a REMOVED line carries only {@code from}; losing the location there is silent. */
    @Test
    void anInlineCommentOnARemovedLineFallsBackToFrom() {
        AuthorReplied e = replyFrom(commentInline(
                ", \"inline\": { \"path\": \"src/App.java\", \"from\": 17 }"));
        assertEquals(17, e.location().line());
    }

    /** A plain PR comment has no {@code inline} block at all. */
    @Test
    void aPlainCommentHasNoLocation() {
        assertNull(replyFrom(commentInline("")).location());
    }

    /** A file-level inline block has a path but no line — must not throw, must not half-report. */
    @Test
    void anInlineBlockWithNoLineIsHandledWithoutThrowing() {
        assertNull(replyFrom(commentInline(", \"inline\": { \"path\": \"src/App.java\" }")).location());
    }

    /** A comment whose {@code inline} block is spliced in verbatim, so tests can shape it freely. */
    private static byte[] commentInline(String inlineJson) {
        return ("""
                {
                  "repository": { "full_name": "sandbox/demo-repo" },
                  "pullrequest": { "id": 7 },
                  "comment": { "id": 900, "content": { "raw": "why is this a bug?" },
                    "user": { "account_id": "HUM-9", "nickname": "jdoe", "display_name": "Jane" }%s }
                }""").formatted(inlineJson).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private AuthorReplied replyFrom(byte[] body) {
        return assertInstanceOf(AuthorReplied.class, ingress.translate(
                webhook(body, Map.of("X-Event-Key", "pullrequest:comment_created"))).getFirst());
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
