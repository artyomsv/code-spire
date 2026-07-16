package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent.CloseReason;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.port.RawWebhook;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitLabIngressTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BOT_ACCOUNT_ID = "9999";

    private final GitLabIngress ingress = new GitLabIngress(SECRET, new ObjectMapper(), Set.of("review"));

    // --- token verification (constant-time compare, NOT an HMAC over the body) ---

    @Test
    void acceptsMatchingToken() {
        assertTrue(ingress.verifySignature(webhook(mr("open", null), Map.of("X-Gitlab-Token", SECRET))));
    }

    @Test
    void rejectsWrongMissingOrEmptyToken() {
        assertFalse(ingress.verifySignature(webhook(mr("open", null), Map.of("X-Gitlab-Token", "not-the-secret"))));
        assertFalse(ingress.verifySignature(webhook(mr("open", null), Map.of())));
        assertFalse(ingress.verifySignature(webhook(mr("open", null), Map.of("X-Gitlab-Token", ""))));
        // A token that is a prefix of the secret must not slip through a length check.
        assertFalse(ingress.verifySignature(webhook(mr("open", null), Map.of("X-Gitlab-Token", "test"))));
    }

    @Test
    void blankConfiguredSecretNeverAuthenticates() {
        // Defense in depth: with a blank secret, even a matching empty token must fail —
        // a blank secret is never a valid credential.
        GitLabIngress blank = new GitLabIngress("", new ObjectMapper(), Set.of("review"));
        assertFalse(blank.verifySignature(webhook(mr("open", null), Map.of("X-Gitlab-Token", ""))));
        assertFalse(blank.verifySignature(webhook(mr("open", null), Map.of())));
    }

    // --- translation: merge_request ---

    @Test
    void translatesOpenedMergeRequestOfANestedProject() {
        PullRequestEventReceived e = assertInstanceOf(PullRequestEventReceived.class,
                ingress.translate(webhook(mr("open", null), Map.of())).getFirst());
        assertEquals("acme", e.repo().workspace());
        assertEquals("team/spire-test", e.repo().slug()); // nested namespace preserved
        assertEquals("acme/team/spire-test", e.repo().full());
        assertEquals(7, e.prId());                        // the MR iid, not its global id
        assertEquals(PrAction.OPENED, e.action());
        assertEquals("Add feature", e.title());
        assertEquals("feature/x", e.sourceBranch());
        assertEquals("main", e.targetBranch());
        assertEquals("abc123def4567890", e.diffRefs().headSha());
        assertEquals("1234", e.author().providerUserId()); // numeric id, for the self-loop guard
        assertEquals("octocat", e.author().username());
        assertEquals("https://gitlab.com/acme/team/spire-test/-/merge_requests/7", e.htmlUrl());
        assertEquals("gitlab", e.providerType());
    }

    @Test
    void reopenIsOpenedAndUpdateWithNewCommitsIsUpdated() {
        assertEquals(PrAction.OPENED, ((PullRequestEventReceived) ingress.translate(
                webhook(mr("reopen", null), Map.of())).getFirst()).action());
        // "update" carries oldrev only when the branch head moved (a push).
        assertEquals(PrAction.UPDATED, ((PullRequestEventReceived) ingress.translate(
                webhook(mr("update", "0000000000000000"), Map.of())).getFirst()).action());
    }

    @Test
    void metadataOnlyUpdateIsIgnored() {
        // A label/description edit fires "update" WITHOUT oldrev — the diff is unchanged,
        // so it must not trigger a re-review.
        assertTrue(ingress.translate(webhook(mr("update", null), Map.of())).isEmpty());
    }

    @Test
    void translatesMergedAndClosed() {
        PullRequestClosed merged = assertInstanceOf(PullRequestClosed.class,
                ingress.translate(webhook(mr("merge", null), Map.of())).getFirst());
        assertEquals(CloseReason.MERGED, merged.reason());

        PullRequestClosed declined = assertInstanceOf(PullRequestClosed.class,
                ingress.translate(webhook(mr("close", null), Map.of())).getFirst());
        assertEquals(CloseReason.DECLINED, declined.reason());
    }

    @Test
    void ignoresUninterestingMergeRequestActions() {
        assertTrue(ingress.translate(webhook(mr("approved", null), Map.of())).isEmpty());
        assertTrue(ingress.translate(webhook(mr("unapproved", null), Map.of())).isEmpty());
    }

    // --- translation: note (comment) ---

    @Test
    void parsesRegisteredSlashCommandOnAMergeRequestNote() {
        ManualCommandReceived e = assertInstanceOf(ManualCommandReceived.class,
                ingress.translate(webhook(note("/review please", MR_NOTEABLE), Map.of())).getFirst());
        assertEquals("review", e.command());
        assertEquals("please", e.args());
        assertEquals(7, e.prId()); // merge_request.iid
        assertEquals(BOT_ACCOUNT_ID, e.author().providerUserId());
    }

    @Test
    void ignoresNoteOnANonMergeRequestOrWithoutACommand() {
        // A note on an Issue/Commit/Snippet is not a MR comment.
        assertTrue(ingress.translate(webhook(note("/review", "Issue"), Map.of())).isEmpty());
        // A non-command note produces nothing (AuthorReplied is a parked feature).
        assertTrue(ingress.translate(webhook(note("looks good", MR_NOTEABLE), Map.of())).isEmpty());
        // An unregistered command is not forwarded.
        assertTrue(ingress.translate(webhook(note("/deploy now", MR_NOTEABLE), Map.of())).isEmpty());
    }

    // --- malformed / uninteresting ---

    @Test
    void unknownObjectKindYieldsNothing() {
        assertTrue(ingress.translate(webhook("{\"object_kind\":\"push\"}".getBytes(StandardCharsets.UTF_8),
                Map.of())).isEmpty());
    }

    @Test
    void payloadWithoutAValidProjectPathIsRejected() {
        byte[] body = mrJson("open", null).replace("acme/team/spire-test", "no-namespace")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> ingress.translate(webhook(body, Map.of())));
    }

    // --- fixtures ---

    private static final String MR_NOTEABLE = "MergeRequest";

    /** @param oldrev present -> an "update" that moved the head (a push); null -> omit it. */
    private static byte[] mr(String action, String oldrev) {
        return mrJson(action, oldrev).getBytes(StandardCharsets.UTF_8);
    }

    private static String mrJson(String action, String oldrev) {
        String oldrevField = oldrev == null ? "" : "\"oldrev\": \"" + oldrev + "\",\n    ";
        return """
                {
                  "object_kind": "merge_request",
                  "user": { "id": 1234, "username": "octocat", "name": "Octo Cat" },
                  "project": { "path_with_namespace": "acme/team/spire-test" },
                  "object_attributes": {
                    %s"iid": 7,
                    "id": 999,
                    "action": "%s",
                    "title": "Add feature",
                    "description": "Adds the feature.",
                    "source_branch": "feature/x",
                    "target_branch": "main",
                    "last_commit": { "id": "abc123def4567890" },
                    "url": "https://gitlab.com/acme/team/spire-test/-/merge_requests/7"
                  }
                }
                """.formatted(oldrevField, action);
    }

    private static byte[] note(String text, String noteableType) {
        return """
                {
                  "object_kind": "note",
                  "user": { "id": %s, "username": "octocat", "name": "Octo Cat" },
                  "project": { "path_with_namespace": "acme/team/spire-test" },
                  "object_attributes": {
                    "note": "%s",
                    "noteable_type": "%s"
                  },
                  "merge_request": { "iid": 7 }
                }
                """.formatted(BOT_ACCOUNT_ID, text, noteableType).getBytes(StandardCharsets.UTF_8);
    }

    private static RawWebhook webhook(byte[] body, Map<String, String> headers) {
        return new RawWebhook(headers, body);
    }
}
