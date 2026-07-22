package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
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

class GitHubIngressTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BOT_ACCOUNT_ID = "9999";

    private final GitHubIngress ingress = new GitHubIngress(SECRET, new ObjectMapper(), Set.of("review"));

    // --- signature ---

    @Test
    void acceptsValidHmacSignature() throws Exception {
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        assertTrue(ingress.verifySignature(webhook(body, Map.of(
                "X-Hub-Signature-256", "sha256=" + hmac(body),
                "X-GitHub-Event", "pull_request"))));
    }

    @Test
    void rejectsInvalidMissingOrMalformedSignature() {
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        assertFalse(ingress.verifySignature(webhook(body, Map.of(
                "X-Hub-Signature-256", "sha256=" + "00".repeat(32)))));
        assertFalse(ingress.verifySignature(webhook(body, Map.of())));
        assertFalse(ingress.verifySignature(webhook(body, Map.of("X-Hub-Signature-256", "sha256=nothex"))));
        // GitHub's v1 header (SHA-1) must NOT be accepted as a 256 signature.
        assertFalse(ingress.verifySignature(webhook(body, Map.of("X-Hub-Signature", "sha1=abcd"))));
    }

    // --- translation: pull_request ---

    @Test
    void translatesOpenedPullRequest() {
        PullRequestEventReceived e = assertInstanceOf(PullRequestEventReceived.class, ingress.translate(
                webhook(pr("opened", false), Map.of("X-GitHub-Event", "pull_request"))).getFirst());
        assertEquals("artyomsv", e.repo().workspace());
        assertEquals("spire-test", e.repo().slug());
        assertEquals(7, e.prId());
        assertEquals(PrAction.OPENED, e.action());
        assertEquals("Add feature", e.title());
        assertEquals("feature/x", e.sourceBranch());
        assertEquals("main", e.targetBranch());
        assertEquals("abc123def4567890", e.diffRefs().headSha());
        assertEquals("1234", e.author().providerUserId()); // numeric id, for the self-loop guard
        assertEquals("octocat", e.author().username());
        assertEquals("https://github.com/artyomsv/spire-test/pull/7", e.htmlUrl());
    }

    @Test
    void synchronizeIsUpdatedAndReopenedIsOpened() {
        assertEquals(PrAction.UPDATED, ((PullRequestEventReceived) ingress.translate(
                webhook(pr("synchronize", false), Map.of("X-GitHub-Event", "pull_request"))).getFirst()).action());
        assertEquals(PrAction.OPENED, ((PullRequestEventReceived) ingress.translate(
                webhook(pr("reopened", false), Map.of("X-GitHub-Event", "pull_request"))).getFirst()).action());
    }

    @Test
    void translatesMergedAndDeclinedToClosed() {
        PullRequestClosed merged = assertInstanceOf(PullRequestClosed.class, ingress.translate(
                webhook(pr("closed", true), Map.of("X-GitHub-Event", "pull_request"))).getFirst());
        assertEquals(CloseReason.MERGED, merged.reason());

        PullRequestClosed declined = assertInstanceOf(PullRequestClosed.class, ingress.translate(
                webhook(pr("closed", false), Map.of("X-GitHub-Event", "pull_request"))).getFirst());
        assertEquals(CloseReason.DECLINED, declined.reason());
    }

    @Test
    void ignoresUninterestingPullRequestActions() {
        assertTrue(ingress.translate(webhook(pr("edited", false),
                Map.of("X-GitHub-Event", "pull_request"))).isEmpty());
        assertTrue(ingress.translate(webhook(pr("labeled", false),
                Map.of("X-GitHub-Event", "pull_request"))).isEmpty());
    }

    // --- translation: issue_comment ---

    @Test
    void parsesRegisteredSlashCommandOnAPullRequest() {
        ManualCommandReceived e = assertInstanceOf(ManualCommandReceived.class, ingress.translate(
                webhook(issueComment("/review please", true), Map.of("X-GitHub-Event", "issue_comment"))).getFirst());
        assertEquals("review", e.command());
        assertEquals("please", e.args());
        assertEquals(7, e.prId());
        assertEquals(BOT_ACCOUNT_ID, e.author().providerUserId());
    }

    @Test
    void ignoresCommentOnAPlainIssueOrWithoutACommand() {
        // No issue.pull_request node => a plain issue, not a PR.
        assertTrue(ingress.translate(webhook(issueComment("/review", false),
                Map.of("X-GitHub-Event", "issue_comment"))).isEmpty());
        // A non-command comment produces nothing (AuthorReplied is a parked feature).
        assertTrue(ingress.translate(webhook(issueComment("looks good", true),
                Map.of("X-GitHub-Event", "issue_comment"))).isEmpty());
        // An unregistered command is not forwarded.
        assertTrue(ingress.translate(webhook(issueComment("/deploy now", true),
                Map.of("X-GitHub-Event", "issue_comment"))).isEmpty());
    }

    @Test
    void unknownEventYieldsNothing() {
        assertTrue(ingress.translate(webhook(pr("opened", false), Map.of("X-GitHub-Event", "ping"))).isEmpty());
        assertTrue(ingress.translate(webhook(pr("opened", false), Map.of())).isEmpty());
    }

    // --- translation: draft-PR policy ---

    @Test
    void draftPrOpenedIsSkippedByDefault() {
        assertTrue(ingress.translate(webhook(pr("opened", false, true), "pull_request")).isEmpty());
    }

    @Test
    void draftPrSynchronizeIsSkippedByDefault() {
        assertTrue(ingress.translate(webhook(pr("synchronize", false, true), "pull_request")).isEmpty());
    }

    @Test
    void readyForReviewTriggersAnOpenedEvent() {
        List<IntegrationEvent> events = ingress.translate(webhook(pr("ready_for_review", false, false), "pull_request"));
        assertEquals(1, events.size());
        assertEquals(IntegrationEvent.PrAction.OPENED,
                ((IntegrationEvent.PullRequestEventReceived) events.getFirst()).action());
    }

    @Test
    void draftClosedStillCancels() {
        List<IntegrationEvent> events = ingress.translate(webhook(pr("closed", false, true), "pull_request"));
        assertEquals(1, events.size());
        assertInstanceOf(IntegrationEvent.PullRequestClosed.class, events.getFirst());
    }

    @Test
    void reviewDraftsTrueRestoresTodaysBehavior() {
        GitHubIngress permissive = new GitHubIngress(SECRET, new ObjectMapper(), Set.of("review"), true);
        assertEquals(1, permissive.translate(webhook(pr("opened", false, true), "pull_request")).size());
        assertTrue(permissive.translate(webhook(pr("ready_for_review", false, false), "pull_request")).isEmpty());
    }

    // --- fixtures ---

    private static byte[] pr(String action, boolean merged) {
        return """
                {
                  "action": "%s",
                  "repository": { "full_name": "artyomsv/spire-test" },
                  "pull_request": {
                    "number": 7,
                    "title": "Add feature",
                    "body": "Adds the feature.",
                    "merged": %s,
                    "head": { "ref": "feature/x", "sha": "abc123def4567890" },
                    "base": { "ref": "main" },
                    "user": { "id": 1234, "login": "octocat" },
                    "html_url": "https://github.com/artyomsv/spire-test/pull/7"
                  }
                }
                """.formatted(action, merged).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pr(String action, boolean merged, boolean draft) {
        return """
                {
                  "action": "%s",
                  "repository": { "full_name": "artyomsv/spire-test" },
                  "pull_request": {
                    "number": 7,
                    "title": "Add feature",
                    "body": "Adds the feature.",
                    "merged": %s,
                    "draft": %s,
                    "head": { "ref": "feature/x", "sha": "abc123def4567890" },
                    "base": { "ref": "main" },
                    "user": { "id": 1234, "login": "octocat" },
                    "html_url": "https://github.com/artyomsv/spire-test/pull/7"
                  }
                }
                """.formatted(action, merged, draft).getBytes(StandardCharsets.UTF_8);
    }

    private static RawWebhook webhook(byte[] body, String event) {
        return new RawWebhook(Map.of("X-GitHub-Event", event), body);
    }

    private static byte[] issueComment(String text, boolean onPullRequest) {
        String prNode = onPullRequest ? ", \"pull_request\": { \"url\": \"https://api.github.com/…/pulls/7\" }" : "";
        return """
                {
                  "action": "created",
                  "repository": { "full_name": "artyomsv/spire-test" },
                  "issue": { "number": 7%s },
                  "comment": {
                    "body": "%s",
                    "user": { "id": %s, "login": "octocat" }
                  }
                }
                """.formatted(prNode, text, BOT_ACCOUNT_ID).getBytes(StandardCharsets.UTF_8);
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
