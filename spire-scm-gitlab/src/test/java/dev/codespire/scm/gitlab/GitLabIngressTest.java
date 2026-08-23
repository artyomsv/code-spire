package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CloseReason;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitLabIngressTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BOT_ACCOUNT_ID = "9999";

    private final GitLabIngress ingress = new GitLabIngress(SECRET, new ObjectMapper(), Set.of("review", "finding"));

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
        assertEquals("abc123def4567890", e.headCommit());
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

    // --- translation: draft/WIP policy ---

    @Test
    void draftMrOpenIsSkippedByDefault() {
        assertTrue(ingress.translate(webhook(mrDraft("open", true))).isEmpty());
    }

    @Test
    void nonDraftMrOpenIsReviewed() {
        assertEquals(1, ingress.translate(webhook(mrDraft("open", false))).size());
    }

    @Test
    void draftToReadyFlipEmitsOpened() {
        var events = ingress.translate(webhook(mrReadyFlip()));
        assertEquals(1, events.size());
        assertEquals(IntegrationEvent.PrAction.OPENED,
                ((IntegrationEvent.PullRequestEventReceived) events.getFirst()).action());
    }

    @Test
    void reviewDraftsTrueReviewsDraftsImmediately() {
        GitLabIngress permissive = new GitLabIngress(SECRET, new ObjectMapper(), Set.of("review"), true);
        assertEquals(1, permissive.translate(webhook(mrDraft("open", true))).size());
    }

    @Test
    void draftMrUpdateWithPushStaysSkipped() {
        // A push to a still-draft MR ("update" action, oldrev present -> a real push,
        // no draft->ready flip in "changes") must stay suppressed: only the ready flip
        // (or reviewDrafts=true) reviews a draft.
        assertTrue(ingress.translate(webhook(mrDraft("update", true, "abc123def4567890"))).isEmpty());
    }

    @Test
    void draftMrReopenIsSkippedByDefault() {
        assertTrue(ingress.translate(webhook(mrDraft("reopen", true))).isEmpty());
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
    void ignoresNoteOnANonMergeRequest() {
        // A note on an Issue/Commit/Snippet is not a MR comment.
        assertTrue(ingress.translate(webhook(note("/review", "Issue"), Map.of())).isEmpty());
    }

    @Test
    void unregisteredCommandOnAMergeRequestNoteBecomesAComment() {
        // An unregistered command is not forwarded as a command -- it falls through and engages
        // the bot as an ordinary note, same as any other body.
        var events = ingress.translate(webhook(note("/deploy now", MR_NOTEABLE), Map.of()));
        assertEquals(1, events.size());
        assertInstanceOf(IntegrationEvent.AuthorReplied.class, events.getFirst());
    }

    @Test
    void slashCommandInADiffNoteKeepsItsDiscussionAndPosition() {
        List<IntegrationEvent> events = ingress.translate(webhook(noteWithPosition(
                "/finding major shadows the field", "disc-900", 901, "src/Foo.java", 44)));
        assertEquals(1, events.size());
        ManualCommandReceived e = assertInstanceOf(ManualCommandReceived.class, events.getFirst());
        assertEquals("finding", e.command());
        assertEquals("major shadows the field", e.args());
        assertEquals(new ThreadRef("disc-900"), e.threadRef());
        assertEquals(new ThreadLocation("src/Foo.java", 44), e.location());
        assertEquals("901", e.commentId());
    }

    @Test
    void anUnrecognisedSlashWordIsAComment() {
        // Was dropped entirely (List.of()), while Bitbucket and GitHub treat the same text as a
        // comment -- one user action, three outcomes.
        List<IntegrationEvent> events = ingress.translate(webhook(noteWithPosition(
                "/usr/lib is the wrong path here", "disc-900", 901, "src/Foo.java", 44)));
        assertEquals(1, events.size());
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertEquals("901", e.commentId());
    }

    @Test
    void threadedReplyEmitsAuthorRepliedKeyedToDiscussion() {
        var events = ingress.translate(webhook(noteWith("looks fine to me", "DiffNote", "DISC42", 900)));
        assertEquals(1, events.size());
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertFalse(e.topLevel());
        assertEquals("DISC42", e.threadRef().value());
        assertEquals("900", e.commentId());
        assertEquals("looks fine to me", e.text());
        assertEquals(7, e.prId());
        assertEquals("42", e.author().providerUserId());   // from the note fixture's user.id
    }

    /**
     * The ingress extracts who was @-mentioned, because only it knows GitLab renders a mention as
     * {@code @username} in the note body. The orchestrator only asks whether the bot is in the list.
     */
    @Test
    void mentionsAreExtractedFromTheNoteInGitLabsOwnSyntax() {
        var events = ingress.translate(webhook(
                noteWith("@code-spire.bot and @dev_one please look", "DiffNote", "DISC43", 902)));
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertEquals(List.of("code-spire.bot", "dev_one"), e.mentions());
    }

    @Test
    void aNoteWithNoMentionsCarriesAnEmptyList() {
        var events = ingress.translate(webhook(noteWith("plain reply", "DiffNote", "DISC44", 903)));
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertEquals(List.of(), e.mentions());
    }

    @Test
    void topLevelNoteEmitsTopLevelAuthorReplied() {
        var events = ingress.translate(webhook(noteWith("what about edge cases?", null, "DISC7", 901)));
        assertEquals(1, events.size());
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertTrue(e.topLevel());
        assertEquals("what about edge cases?", e.text());
    }

    // --- where the thread sits in the diff (drives UI placement and the flagged-line policy) ---

    /** A note with a `position` block sits on a line; a note without one does not. */
    private static byte[] noteAt(String positionJson) {
        return ("""
                {
                  "object_kind": "note",
                  "project": { "path_with_namespace": "sandbox/demo-repo" },
                  "user": { "id": 42, "username": "jdoe", "name": "Jane Doe" },
                  "merge_request": { "iid": 7 },
                  "object_attributes": {
                    "noteable_type": "MergeRequest",
                    "note": "why is this a bug?", "type": "DiffNote",
                    "discussion_id": "DISC90", "id": 910%s
                  }
                }""").formatted(positionJson).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aDiffNoteCarriesItsFileAndLine() {
        var e = (IntegrationEvent.AuthorReplied) ingress.translate(webhook(noteAt(
                ", \"position\": { \"new_path\": \"src/App.java\", \"new_line\": 42 }"))).getFirst();
        assertNotNull(e.location());
        assertEquals("src/App.java", e.location().path());
        assertEquals(42, e.location().line());
    }

    /** A note on a REMOVED line has only `old_line`; losing the location there would be silent. */
    @Test
    void aNoteOnARemovedLineFallsBackToTheOldSide() {
        var e = (IntegrationEvent.AuthorReplied) ingress.translate(webhook(noteAt(
                ", \"position\": { \"old_path\": \"src/App.java\", \"old_line\": 17 }"))).getFirst();
        assertEquals("src/App.java", e.location().path());
        assertEquals(17, e.location().line());
    }

    /** A DiscussionNote (thread not tied to the diff) carries no position — null, not fabricated. */
    @Test
    void aNoteWithNoPositionHasNoLocation() {
        var e = (IntegrationEvent.AuthorReplied) ingress.translate(webhook(noteAt(""))).getFirst();
        assertNull(e.location());
    }

    /** A file-level position has a path but no line — must not throw, and must not half-report. */
    @Test
    void aPositionWithNoLineIsHandledWithoutThrowing() {
        var e = (IntegrationEvent.AuthorReplied) ingress.translate(webhook(noteAt(
                ", \"position\": { \"new_path\": \"src/App.java\" }"))).getFirst();
        assertNull(e.location());
    }

    @Test
    void slashCommandNoteStillEmitsManualCommand() {
        var events = ingress.translate(webhook(noteWith("/review please", null, "DISC7", 902)));
        assertEquals(1, events.size());
        assertInstanceOf(IntegrationEvent.ManualCommandReceived.class, events.getFirst());
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

    private static byte[] mrDraft(String action, boolean draft) {
        return mrDraft(action, draft, null);
    }

    /** @param oldrev present -> the update also carries a push (a real head move); null -> omit it. */
    private static byte[] mrDraft(String action, boolean draft, String oldrev) {
        String oldrevField = oldrev == null ? "" : "\"oldrev\": \"" + oldrev + "\", ";
        return ("""
                { "object_kind": "merge_request",
                  "project": { "path_with_namespace": "sandbox/demo-repo" },
                  "user": { "id": 42, "username": "jdoe", "name": "Jane" },
                  "object_attributes": { %s"iid": 7, "action": "%s", "work_in_progress": %b,
                    "title": "%sAdd feature", "source_branch": "f", "target_branch": "main",
                    "last_commit": { "id": "abc123" }, "url": "http://gl/mr/7" } }""")
                .formatted(oldrevField, action, draft, draft ? "Draft: " : "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] mrReadyFlip() {
        return """
                { "object_kind": "merge_request",
                  "project": { "path_with_namespace": "sandbox/demo-repo" },
                  "user": { "id": 42, "username": "jdoe", "name": "Jane" },
                  "changes": { "draft": { "previous": true, "current": false } },
                  "object_attributes": { "iid": 7, "action": "update", "work_in_progress": false,
                    "title": "Add feature", "source_branch": "f", "target_branch": "main",
                    "last_commit": { "id": "abc123" }, "url": "http://gl/mr/7" } }"""
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    /** Unlike {@link #note}, carries the {@code type}/{@code discussion_id}/{@code id} fields
     *  a threaded reply (DiffNote/DiscussionNote) or top-level note (null type) needs. */
    private static byte[] noteWith(String body, String type, String discussionId, long noteId) {
        String typeField = type == null ? "null" : "\"" + type + "\"";
        return ("""
                {
                  "object_kind": "note",
                  "project": { "path_with_namespace": "sandbox/demo-repo" },
                  "user": { "id": 42, "username": "jdoe", "name": "Jane Doe" },
                  "merge_request": { "iid": 7 },
                  "object_attributes": {
                    "noteable_type": "MergeRequest",
                    "note": "%s", "type": %s, "discussion_id": "%s", "id": %d
                  }
                }""").formatted(body, typeField, discussionId, noteId).getBytes(StandardCharsets.UTF_8);
    }

    /** A DiffNote carrying both a discussion id and a diff position, for slash-command-in-a-thread tests. */
    private static byte[] noteWithPosition(String text, String discussionId, long noteId, String path, int line) {
        return ("""
                {
                  "object_kind": "note",
                  "project": { "path_with_namespace": "sandbox/demo-repo" },
                  "user": { "id": 42, "username": "jdoe", "name": "Jane Doe" },
                  "merge_request": { "iid": 7 },
                  "object_attributes": {
                    "noteable_type": "MergeRequest", "type": "DiffNote",
                    "id": %d, "discussion_id": "%s", "note": "%s",
                    "position": { "new_path": "%s", "new_line": %d }
                  }
                }""").formatted(noteId, discussionId, text, path, line).getBytes(StandardCharsets.UTF_8);
    }

    private static RawWebhook webhook(byte[] body) {
        return webhook(body, Map.of());
    }

    private static RawWebhook webhook(byte[] body, Map<String, String> headers) {
        return new RawWebhook(headers, body);
    }
}
