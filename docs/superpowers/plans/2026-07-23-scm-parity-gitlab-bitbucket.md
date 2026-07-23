# GitLab + Bitbucket Full-Flow Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the GitLab and Bitbucket Cloud SCM adapters to full functional parity with the finalized GitHub adapter so an operator can manually test the complete review loop (webhook → review → conversation → reconciliation) on both platforms.

**Architecture:** Mirror the proven `spire-scm-github` adapter, one workstream at a time. Each adapter gains a `ThreadSource` implementation on its `CommentSink`, `AuthorReplied`/`topLevel` ingress emission, draft-PR skip, and `Retry-After` rate-limit classification. The shared `FollowUpWorker` and `ConversationSaga` are already provider-neutral and are NOT modified — the conversation loop lights up the moment each `CommentSink` also implements `ThreadSource` (`FollowUpWorker.java:91`). The GitHub adapter is not touched.

**Tech Stack:** Java 25, Quarkus 3.36, Gradle Kotlin DSL, JUnit 6 + WireMock 3 (per-adapter test suites — GitLab/Bitbucket modules have WireMock only, no Mockito), Jackson.

## Global Constraints

- Java 25, 4-space indentation, explicit types over `var`, `interface` over `type` only applies to TS (N/A here).
- Never modify `spire-scm-github` — it is the live-proven reference.
- `ThreadRef` is OPAQUE: a `discussion_id` on GitLab, a comment id on Bitbucket. Never interpret its value outside the adapter that produced it.
- The self-loop guard (dropping bot-authored events, ADR-013) runs downstream in the orchestrator — ingress adapters must NOT drop bot events; each event carries the acting user's numeric/`account_id` as `providerUserId`.
- Bot attribution in a transcript is best-effort: a transient `/user` failure degrades to `""` (unattributed), never sinks the reply — mirror `GitHubCommentSink.botLogin()` (`GitHubCommentSink.java:213-221`).
- The prompt fence is the injection defense — reply text is posted as Markdown as-is; never HTML-escape it.
- Reuse the existing provider-neutral config flag `spire.review.draft-prs` (default `false` = skip drafts). Do not invent a per-provider flag.
- No secrets in logs; no synthetic data in tests that could be mistaken for real values (WireMock fixtures use obvious placeholders like `abc123`, `sandbox/demo-repo`).
- GitLab/Bitbucket adapters only CLASSIFY rate limits (expose `retryAfterSeconds()`); the worker's existing backoff consumes it. Do not add retry loops or `Thread.sleep` in adapters.
- Bitbucket reconciliation stays reply-only (no PR-comment resolve API); Bitbucket inline stays single-anchor (no ranges) — these are non-goals, do not attempt them.

---

## File Structure

**Modified (GitLab):**
- `spire-scm-gitlab/.../GitLabCommentSink.java` — add `ThreadSource`, `fetchThread`, `botUsername`, `replyInThread` 404-fallback, multi-line `line_range` in `postInline`.
- `spire-scm-gitlab/.../GitLabIngress.java` — emit `AuthorReplied` for non-command notes; `reviewDrafts` ctor + WIP/draft→ready policy.
- `spire-scm-gitlab/.../GitLabApiException.java` — carry `retryAfterSeconds`.
- `spire-scm-gitlab/.../GitLabClient.java` — parse `Retry-After` on non-2xx.
- `spire-gateway/.../GitLabWebhookResource.java` — wire `spire.review.draft-prs`.

**Modified (Bitbucket):**
- `spire-scm-bitbucket/.../BitbucketCloudCommentSink.java` — add `ThreadSource`, `fetchThread`, `botAccountId`.
- `spire-scm-bitbucket/.../BitbucketCloudIngress.java` — `topLevel` flag; `reviewDrafts` ctor + draft skip.
- `spire-scm-bitbucket/.../BitbucketApiException.java` — carry `retryAfterSeconds`.
- `spire-scm-bitbucket/.../BitbucketCloudClient.java` — parse `Retry-After` on non-2xx.
- `spire-gateway/.../BitbucketWebhookResource.java` — wire `spire.review.draft-prs`.

**New tests:** `GitLabThreadFetchTest.java`, `BitbucketThreadFetchTest.java`; additions to `GitLabIngressTest`, `BitbucketCloudIngressTest`, `GitLabApiTest`, `BitbucketCloudApiTest`, `GitLabReconciliationTest`, `BitbucketReconciliationTest`.

**Docs:** `docs/SMOKE-TEST.md` (Mode F + conversation/reconciliation steps + compare verify).

---

## Task 1: GitLab `ThreadSource` (fetchThread + reply fallback)

**Files:**
- Modify: `spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabCommentSink.java`
- Test: `spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabThreadFetchTest.java` (create)

**Interfaces:**
- Consumes: `ThreadSource` (`spire-contract/.../port/ThreadSource.java`): `ScmType type(); ThreadTranscript fetchThread(RepoRef, long prId, ThreadRef)`. `ThreadTranscript(ThreadRef threadRef, String path, int line, String commit, List<ThreadMessage> messages)`; `ThreadMessage(String author, String text, boolean fromBot)`. `GitLabApiException.isNotFound()` (default `status()==404`). `GitLabDiffSource.mrPath(repo, prId)`. `GitLabClient.getJson(path)`, `postJson(path, body)`.
- Produces: `GitLabCommentSink implements CommentSink, ThreadSource` — the `instanceof ThreadSource` check in `FollowUpWorker.java:91` now passes for GitLab.

**Context:** GitLab models each note inside a discussion. An inline finding's `ThreadRef` is a `discussion_id` (`GET .../discussions/{id}` works). A top-level/summary conversation's `ThreadRef` is a plain NOTE id (the summary is posted via `/notes`), for which `GET .../discussions/{noteId}` 404s — so `fetchThread` and `replyInThread` fall back to the plain-note path, mirroring GitHub's review-comment → issue-comment duality (`GitHubCommentSink.java:229-308`, `:99-113`).

- [ ] **Step 1: Write the failing test**

Create `GitLabThreadFetchTest.java` (WireMock harness — GitLab module has no Mockito):

```java
package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitLab ThreadSource: read a discussion transcript, fall back to the note tail for a plain-note ref. */
class GitLabThreadFetchTest {

    private static WireMockServer server;
    private static GitLabCommentSink sink;
    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final String MR = "/projects/sandbox%2Fdemo-repo/merge_requests/42";

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        GitLabClient client = new GitLabClient(
                new GitLabConfig("http://localhost:" + server.port(), "test-token"), new ObjectMapper());
        sink = new GitLabCommentSink(client);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
        server.stubFor(get(urlEqualTo("/user")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{ \"id\": 1, \"username\": \"code-spire\", \"name\": \"Code Spire\" }")));
    }

    @Test
    void fetchesDiscussionTranscriptWithAnchorAndBotAttribution() {
        server.stubFor(get(urlEqualTo(MR + "/discussions/DISC1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        { "id": "DISC1", "notes": [
                          { "id": 100, "system": false, "body": "possible NPE",
                            "author": { "username": "code-spire" },
                            "position": { "new_path": "src/App.java", "new_line": 42, "head_sha": "abc123" } },
                          { "id": 200, "system": false, "body": "why?", "author": { "username": "jdoe" } },
                          { "id": 201, "system": true, "body": "changed the description",
                            "author": { "username": "jdoe" } } ] }""")));

        ThreadTranscript t = sink.fetchThread(REPO, 42, new ThreadRef("DISC1"));

        assertEquals("src/App.java", t.path());
        assertEquals(42, t.line());
        assertEquals("abc123", t.commit());
        assertEquals(2, t.messages().size());          // 100 + 200; system note 201 dropped
        assertTrue(t.messages().get(0).fromBot());      // code-spire == token owner
        assertFalse(t.messages().get(1).fromBot());
        assertEquals("why?", t.messages().get(1).text());
    }

    @Test
    void fallsBackToNoteTailWhenRefIsNotADiscussion() {
        // A summary-note ref: GET /discussions/{noteId} 404s, so read the MR notes tail from that id.
        server.stubFor(get(urlEqualTo(MR + "/discussions/555"))
                .willReturn(aResponse().withStatus(404).withBody("{}")));
        server.stubFor(get(urlEqualTo(MR + "/notes?per_page=100&page=1")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        [ { "id": 500, "system": false, "body": "earlier note", "author": { "username": "jdoe" } },
                          { "id": 555, "system": false, "body": "summary", "author": { "username": "code-spire" } },
                          { "id": 556, "system": false, "body": "thanks", "author": { "username": "jdoe" } } ]""")));

        ThreadTranscript t = sink.fetchThread(REPO, 42, new ThreadRef("555"));

        assertNull(t.path());
        assertEquals(0, t.line());
        assertNull(t.commit());
        assertEquals(2, t.messages().size());           // from 555 onward: 555 + 556, not 500
        assertTrue(t.messages().get(0).fromBot());
        assertEquals("thanks", t.messages().get(1).text());
    }

    @Test
    void replyFallsBackToPlainNoteWhenDiscussionMissing() {
        server.stubFor(post(urlPathEqualTo(MR + "/discussions/555/notes"))
                .willReturn(aResponse().withStatus(404).withBody("{}")));
        server.stubFor(post(urlPathEqualTo(MR + "/notes")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{ \"id\": 900 }")));

        sink.replyInThread(REPO, 42, new ThreadRef("555"), "here is my answer");

        server.verify(postRequestedFor(urlPathEqualTo(MR + "/notes")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :spire-scm-gitlab:test --tests "*GitLabThreadFetchTest*"`
Expected: FAIL — `GitLabCommentSink` does not implement `ThreadSource` / `fetchThread` not found (compile error).

- [ ] **Step 3: Implement**

In `GitLabCommentSink.java`: add imports and change the class declaration to also implement `ThreadSource`; add the `fetchThread`, `botUsername`, and note-tail helpers; wrap `replyInThread` with the 404-fallback.

Add imports:
```java
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadTranscript;
import java.util.ArrayList;
import java.util.List;
```

Change declaration:
```java
public class GitLabCommentSink implements CommentSink, ThreadSource {
```

Add a page-cap constant near the top of the class body:
```java
    // Bounds thread re-fetch on a pathological MR (100 notes/page × pages).
    private static final int MAX_THREAD_PAGES = 20;
    private static final System.Logger LOG = System.getLogger(GitLabCommentSink.class.getName());
```

Replace `replyInThread` with the discussion-then-plain-note fallback:
```java
    @Override
    public CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
        String discussionPath = GitLabDiffSource.mrPath(repo, prId) + "/discussions/" + thread.value() + "/notes";
        try {
            JsonNode created = client.postJson(discussionPath, Map.of("body", bodyMd));
            return new CommentRef(requireId(created.path("id"), discussionPath), thread, CommentKind.REPLY);
        } catch (GitLabApiException notADiscussion) {
            if (!notADiscussion.isNotFound()) {
                throw notADiscussion;
            }
            // The ref is a plain note id (a summary conversation), not a discussion_id — reply is a new MR note.
            String notesPath = GitLabDiffSource.mrPath(repo, prId) + "/notes";
            JsonNode created = client.postJson(notesPath, Map.of("body", bodyMd));
            return new CommentRef(requireId(created.path("id"), notesPath), thread, CommentKind.REPLY);
        }
    }
```

Add the `ThreadSource` methods at the end of the class:
```java
    @Override
    public ThreadTranscript fetchThread(RepoRef repo, long prId, ThreadRef thread) {
        String botUsername = botUsername();
        String discussionPath = GitLabDiffSource.mrPath(repo, prId) + "/discussions/" + thread.value();
        try {
            return discussionTranscript(client.getJson(discussionPath), thread, botUsername);
        } catch (GitLabApiException notADiscussion) {
            if (!notADiscussion.isNotFound()) {
                throw notADiscussion;
            }
            return noteTailTranscript(repo, prId, thread, botUsername);
        }
    }

    /** A discussion carries its notes and (for a diff discussion) the code anchor on the first note's position. */
    private static ThreadTranscript discussionTranscript(JsonNode discussion, ThreadRef thread, String botUsername) {
        String path = null;
        int line = 0;
        String commit = null;
        List<ThreadMessage> messages = new ArrayList<>();
        for (JsonNode note : discussion.path("notes")) {
            if (note.path("system").asBoolean(false)) {
                continue; // state-change notes are not conversation turns
            }
            JsonNode position = note.path("position");
            if (path == null && position.isObject()) {
                path = position.path("new_path").asText(null);
                line = position.path("new_line").asInt(0);
                commit = nullIfBlank(position.path("head_sha").asText(""));
            }
            messages.add(toMessage(note, botUsername));
        }
        return new ThreadTranscript(thread, path, line, commit, messages);
    }

    /** Fallback for a plain-note ref (a summary conversation): the MR notes tail from the ref onward, no anchor. */
    private ThreadTranscript noteTailTranscript(RepoRef repo, long prId, ThreadRef thread, String botUsername) {
        String root = thread.value();
        List<ThreadMessage> messages = new ArrayList<>();
        boolean pastRoot = false;
        for (int page = 1; page <= MAX_THREAD_PAGES; page++) {
            JsonNode notes = client.getJson(GitLabDiffSource.mrPath(repo, prId) + "/notes?per_page=100&page=" + page);
            int count = 0;
            for (JsonNode note : notes) {
                count++;
                if (!pastRoot) {
                    if (!root.equals(note.path("id").asText())) {
                        continue;
                    }
                    pastRoot = true;
                }
                if (note.path("system").asBoolean(false)) {
                    continue;
                }
                messages.add(toMessage(note, botUsername));
            }
            if (count < 100) {
                break;
            }
            if (page == MAX_THREAD_PAGES) {
                LOG.log(System.Logger.Level.WARNING,
                        "thread " + root + " transcript may be truncated after " + MAX_THREAD_PAGES + " pages");
            }
        }
        return new ThreadTranscript(thread, null, 0, null, messages);
    }

    private static ThreadMessage toMessage(JsonNode note, String botUsername) {
        String username = note.path("author").path("username").asText("");
        return new ThreadMessage(username, note.path("body").asText("").trim(),
                !username.isEmpty() && username.equals(botUsername));
    }

    /** Best-effort token-owner username to label the bot's own turns; a transient failure degrades to "". */
    private String botUsername() {
        try {
            return client.getJson("/user").path("username").asText("");
        } catch (RuntimeException transientFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "botUsername lookup failed — messages will not be attributed to the bot", transientFailure);
            return "";
        }
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :spire-scm-gitlab:test --tests "*GitLabThreadFetchTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full GitLab suite (no regressions)**

Run: `./gradlew :spire-scm-gitlab:test`
Expected: PASS (existing suites + new).

- [ ] **Step 6: Commit**

```bash
git add spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabCommentSink.java \
        spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabThreadFetchTest.java
git commit -m "Implement GitLab ThreadSource with note-tail reply fallback"
```

---

## Task 2: GitLab ingress emits `AuthorReplied`

**Files:**
- Modify: `spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabIngress.java`
- Test: `spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabIngressTest.java`

**Interfaces:**
- Consumes: `IntegrationEvent.AuthorReplied(RepoRef, long prId, String reviewId, ThreadRef, String commentId, String text, Author, boolean topLevel)` (8-arg canonical; `spire-contract/.../event/IntegrationEvent.java:79`). `ReviewIds.reviewId(RepoRef, long)`. GitLab Note webhook: `object_attributes.{note, discussion_id, id, type, noteable_type}`, MR iid at `merge_request.iid`, actor at `user`.
- Produces: non-command MR notes now emit `AuthorReplied` (threaded reply → `topLevel=false` keyed to `discussion_id`; individual top-level note → `topLevel=true`). The `ConversationSaga` derives `mentioned` from `text` — the ingress does NOT compute it.

**Context:** Today `note()` returns `List.of()` for any non-`/command` note (`GitLabIngress.java:133-135`, "parked roadmap item"). Mirror GitHub's `issueComment`/`reviewCommentReply` split (`GitHubIngress.java:174-219`). GitLab note `type`: `DiffNote`/`DiscussionNote` = threaded (resolvable/reply); absent/`null` = individual top-level comment.

- [ ] **Step 1: Write the failing test**

Add to `GitLabIngressTest.java` (constants `SECRET`, `ingress`, and the `webhook(...)` helper already exist — reuse them; check the existing note fixtures for the exact envelope shape and follow it). Add:

```java
    @Test
    void threadedReplyEmitsAuthorRepliedKeyedToDiscussion() {
        var events = ingress.translate(webhook(note("looks fine to me", "DiffNote", "DISC42", 900)));
        assertEquals(1, events.size());
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertFalse(e.topLevel());
        assertEquals("DISC42", e.threadRef().value());
        assertEquals("900", e.commentId());
        assertEquals("looks fine to me", e.text());
        assertEquals(7, e.prId());
        assertEquals("42", e.author().providerUserId());   // from the note fixture's user.id
    }

    @Test
    void topLevelNoteEmitsTopLevelAuthorReplied() {
        var events = ingress.translate(webhook(note("what about edge cases?", null, "DISC7", 901)));
        assertEquals(1, events.size());
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertTrue(e.topLevel());
        assertEquals("what about edge cases?", e.text());
    }

    @Test
    void slashCommandNoteStillEmitsManualCommand() {
        var events = ingress.translate(webhook(note("/review please", null, "DISC7", 902)));
        assertEquals(1, events.size());
        assertInstanceOf(IntegrationEvent.ManualCommandReceived.class, events.getFirst());
    }
```

Add a `note(...)` fixture helper (model on the existing note fixture in this file — reproduce its `object_kind`/`project`/`user`/`merge_request` envelope, filling `object_attributes.{note,type,discussion_id,id,noteable_type}`):

```java
    private static byte[] note(String body, String type, String discussionId, long noteId) {
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
                }""").formatted(body, typeField, discussionId, noteId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
```

(If the existing file already has a `note(...)` helper with a different arity, rename this one `noteWith(...)` and point the new tests at it — do not break existing tests.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-scm-gitlab:test --tests "*GitLabIngressTest*"`
Expected: FAIL — `ClassCastException`/empty list: non-command notes still return `List.of()`.

- [ ] **Step 3: Implement**

In `GitLabIngress.java` add imports:
```java
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.ThreadRef;
```

Replace the non-command early-return in `note(...)` (`GitLabIngress.java:132-135`) so a non-`/` note emits `AuthorReplied` instead of nothing:

```java
        String text = attrs.path("note").asText("").trim();
        long iid = payload.path("merge_request").path("iid").asLong();
        RepoRef repo = repo(payload);
        if (!text.startsWith("/")) {
            String noteType = attrs.path("type").asText(null);       // DiffNote/DiscussionNote => threaded; null => top-level
            boolean topLevel = noteType == null || noteType.isBlank();
            String discussionId = attrs.path("discussion_id").asText("");
            String noteId = attrs.path("id").asText("");
            return List.of(new AuthorReplied(repo, iid, ReviewIds.reviewId(repo, iid),
                    new ThreadRef(discussionId), noteId, text, author(payload.path("user")), topLevel));
        }
        String[] parts = text.substring(1).split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        if (!commands.contains(command)) {
            return List.of();
        }
        return List.of(new ManualCommandReceived(repo, iid, command,
                parts.length > 1 ? parts[1] : "", author(payload.path("user"))));
```

(Delete the now-duplicated `long iid = payload.path("merge_request").path("iid").asLong();` that previously sat only in the command branch, and the old `if (!text.startsWith("/")) { return List.of(); }` guard.)

Update the `note()` Javadoc: it now emits `AuthorReplied` for non-command MR notes (threaded vs top-level), no longer a parked item.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :spire-scm-gitlab:test --tests "*GitLabIngressTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabIngress.java \
        spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabIngressTest.java
git commit -m "Emit AuthorReplied for GitLab MR note replies"
```

---

## Task 3: Bitbucket `ThreadSource` (fetchThread)

**Files:**
- Modify: `spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudCommentSink.java`
- Test: `spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketThreadFetchTest.java` (create)

**Interfaces:**
- Consumes: `ThreadSource`; `BitbucketCloudClient.getJson(path)`. Bitbucket PR comments: `GET .../pullrequests/{id}/comments?pagelen=100&page=N` → `{ values: [ { id, parent:{id}, content:{raw}, user:{account_id,nickname}, inline:{path,to|from} } ], next }`.
- Produces: `BitbucketCloudCommentSink implements CommentSink, ThreadSource` — `FollowUpWorker.java:91` passes for Bitbucket.

**Context:** Bitbucket has no discussion object; a "thread" is the subtree of comments under a root id linked by `parent.id`. `commit()` is null (inline comments carry no sha; the worker resolves the PR head — `FollowUpWorker.java:177`). Bot attribution by `account_id`.

- [ ] **Step 1: Write the failing test**

Create `BitbucketThreadFetchTest.java`:

```java
package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bitbucket ThreadSource: rebuild a comment subtree under the root id, attribute the bot by account_id. */
class BitbucketThreadFetchTest {

    private static WireMockServer server;
    private static BitbucketCloudCommentSink sink;
    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final String COMMENTS = "/repositories/sandbox/demo-repo/pullrequests/7/comments";

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        BitbucketCloudClient client = new BitbucketCloudClient(
                new BitbucketCloudConfig("http://localhost:" + server.port(),
                        "test-bot", "test-app-password", "test-secret"), new ObjectMapper());
        sink = new BitbucketCloudCommentSink(client);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/user")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{ \"account_id\": \"BOT-1\", \"nickname\": \"code-spire\" }")));
    }

    @Test
    void rebuildsSubtreeUnderRootAndAttributesBot() {
        server.stubFor(get(urlPathEqualTo(COMMENTS)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        { "values": [
                          { "id": 100, "content": { "raw": "possible NPE" },
                            "user": { "account_id": "BOT-1" }, "inline": { "path": "src/App.java", "to": 42 } },
                          { "id": 200, "parent": { "id": 100 }, "content": { "raw": "why?" },
                            "user": { "account_id": "HUM-9" } },
                          { "id": 300, "content": { "raw": "unrelated" }, "user": { "account_id": "HUM-9" } } ] }""")));

        ThreadTranscript t = sink.fetchThread(REPO, 7, new ThreadRef("100"));

        assertEquals("src/App.java", t.path());
        assertEquals(42, t.line());
        assertNull(t.commit());
        assertEquals(2, t.messages().size());              // 100 + 200, not 300
        assertTrue(t.messages().get(0).fromBot());          // BOT-1
        assertFalse(t.messages().get(1).fromBot());
        assertEquals("why?", t.messages().get(1).text());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-scm-bitbucket:test --tests "*BitbucketThreadFetchTest*"`
Expected: FAIL — `fetchThread`/`ThreadSource` not present (compile error).

- [ ] **Step 3: Implement**

In `BitbucketCloudCommentSink.java` add imports and `ThreadSource`, plus the fetch:

```java
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadTranscript;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```

Declaration:
```java
public class BitbucketCloudCommentSink implements CommentSink, ThreadSource {
```

Add near the top of the class body:
```java
    private static final int MAX_THREAD_PAGES = 20;
    private static final System.Logger LOG = System.getLogger(BitbucketCloudCommentSink.class.getName());
```

Add at the end of the class:
```java
    @Override
    public ThreadTranscript fetchThread(RepoRef repo, long prId, ThreadRef thread) {
        String botAccountId = botAccountId();
        List<JsonNode> all = new ArrayList<>();
        for (int page = 1; page <= MAX_THREAD_PAGES; page++) {
            JsonNode body = client.getJson(commentsPath(repo, prId) + "?pagelen=100&page=" + page);
            JsonNode values = body.path("values");
            values.forEach(all::add);
            if (body.path("next").isMissingNode() || body.path("next").asText("").isBlank()) {
                break;
            }
            if (page == MAX_THREAD_PAGES) {
                LOG.log(System.Logger.Level.WARNING,
                        "thread " + thread.value() + " transcript may be truncated after " + MAX_THREAD_PAGES + " pages");
            }
        }
        return subtree(all, thread, botAccountId);
    }

    /** Collect the root plus every descendant (linked by parent.id), preserving list order; anchor from the root. */
    private static ThreadTranscript subtree(List<JsonNode> all, ThreadRef thread, String botAccountId) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode c : all) {
            byId.put(c.path("id").asText(), c);
        }
        String root = thread.value();
        String path = null;
        int line = 0;
        List<ThreadMessage> messages = new ArrayList<>();
        for (JsonNode c : all) {
            if (!inThread(c, root, byId)) {
                continue;
            }
            if (root.equals(c.path("id").asText()) && c.path("inline").isObject()) {
                path = c.path("inline").path("path").asText(null);
                line = c.path("inline").path("to").asInt(c.path("inline").path("from").asInt(0));
            }
            messages.add(toMessage(c, botAccountId));
        }
        return new ThreadTranscript(thread, path, line, null, messages);
    }

    /** True if the comment is the root or chains up to it through parent.id (bounded by the map size). */
    private static boolean inThread(JsonNode comment, String root, Map<String, JsonNode> byId) {
        String id = comment.path("id").asText();
        for (int hop = 0; hop <= byId.size(); hop++) {
            if (root.equals(id)) {
                return true;
            }
            JsonNode ancestor = byId.get(id);
            if (ancestor == null) {
                return false;           // parent paged out of the fetched set — treat as a different thread
            }
            JsonNode parent = ancestor.path("parent").path("id");
            if (parent.isMissingNode() || parent.isNull()) {
                return false;
            }
            id = parent.asText();
        }
        return false;
    }

    private static ThreadMessage toMessage(JsonNode c, String botAccountId) {
        String accountId = c.path("user").path("account_id").asText("");
        String display = c.path("user").path("nickname").asText(accountId);
        return new ThreadMessage(display, c.path("content").path("raw").asText("").trim(),
                !accountId.isEmpty() && accountId.equals(botAccountId));
    }

    /** Best-effort token-owner account id to label the bot's own turns; a transient failure degrades to "". */
    private String botAccountId() {
        try {
            return client.getJson("/user").path("account_id").asText("");
        } catch (RuntimeException transientFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "botAccountId lookup failed — messages will not be attributed to the bot", transientFailure);
            return "";
        }
    }
```

Note: `comment` is captured in `inThread`'s first iteration via `byId.getOrDefault(id, comment)`; for hops beyond the first, `byId.get(id)` supplies the ancestor. The `getOrDefault(..., comment)` guards the first lookup when the root itself isn't re-keyed.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :spire-scm-bitbucket:test --tests "*BitbucketThreadFetchTest*"`
Expected: PASS.

- [ ] **Step 5: Full Bitbucket suite**

Run: `./gradlew :spire-scm-bitbucket:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudCommentSink.java \
        spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketThreadFetchTest.java
git commit -m "Implement Bitbucket ThreadSource by rebuilding comment subtrees"
```

---

## Task 4: Bitbucket ingress `topLevel` flag

**Files:**
- Modify: `spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudIngress.java`
- Test: `spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketCloudIngressTest.java`

**Interfaces:**
- Consumes: `AuthorReplied(..., boolean topLevel)` 8-arg ctor. Comment payload: `comment.{inline, parent.id, id, content.raw, user}`.
- Produces: a plain top-level PR comment (no `inline`, no `parent`) now sets `topLevel=true`; inline/threaded replies keep `topLevel=false` keyed to the thread root.

**Context:** Today `comment()` always builds `AuthorReplied` via the 7-arg ctor (`topLevel=false`, `BitbucketCloudIngress.java:146-151`). The `ConversationSaga` routes `topLevel=true` to the summary comment (`ConversationSaga.java:129-140`), so a plain PR comment must be flagged.

- [ ] **Step 1: Write the failing test**

Add to `BitbucketCloudIngressTest.java` (reuse the existing `webhook(...)`/comment fixtures; check the current comment fixture and follow its shape):

```java
    @Test
    void plainTopLevelCommentIsTopLevel() {
        var events = ingress.translate(webhook(comment("what about nulls?", null, null),
                Map.of("X-Event-Key", "pullrequest:comment_created")));
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertTrue(e.topLevel());
    }

    @Test
    void inlineCommentIsNotTopLevel() {
        var events = ingress.translate(webhook(comment("NPE here", null, "src/App.java"),
                Map.of("X-Event-Key", "pullrequest:comment_created")));
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertFalse(e.topLevel());
    }

    @Test
    void replyIsNotTopLevel() {
        var events = ingress.translate(webhook(comment("agreed", "100", null),
                Map.of("X-Event-Key", "pullrequest:comment_created")));
        var e = (IntegrationEvent.AuthorReplied) events.getFirst();
        assertFalse(e.topLevel());
        assertEquals("100", e.threadRef().value());
    }
```

Add a `comment(body, parentId, inlinePath)` fixture helper (model on the existing comment fixture — same envelope, but set `comment.parent.id` when `parentId != null` and `comment.inline.path` when `inlinePath != null`):

```java
    private static byte[] comment(String body, String parentId, String inlinePath) {
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
```

(If a `comment(...)` helper already exists with a different signature, name this `commentWith(...)`.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-scm-bitbucket:test --tests "*BitbucketCloudIngressTest*"`
Expected: FAIL — `plainTopLevelCommentIsTopLevel` asserts true but gets false.

- [ ] **Step 3: Implement**

In `BitbucketCloudIngress.java`, replace the tail of `comment(...)` (`:141-151`):

```java
        // Reply threads anchor on the ROOT comment id (SCM-MAPPING §6).
        JsonNode parent = comment.path("parent").path("id");
        boolean hasParent = !(parent.isMissingNode() || parent.isNull());
        boolean inline = comment.path("inline").isObject();
        // A plain top-level PR comment (no parent, not inline) is answered in the summary thread (topLevel).
        boolean topLevel = !hasParent && !inline;
        String threadRef = hasParent ? parent.asText() : comment.path("id").asText();
        return List.of(new AuthorReplied(repo, prId,
                ReviewIds.reviewId(repo, prId),
                new ThreadRef(threadRef),
                comment.path("id").asText(),
                text,
                author,
                topLevel));
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :spire-scm-bitbucket:test --tests "*BitbucketCloudIngressTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudIngress.java \
        spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketCloudIngressTest.java
git commit -m "Flag plain Bitbucket PR comments as top-level replies"
```

---

## Task 5: GitLab draft/WIP skip + gateway wiring

**Files:**
- Modify: `spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabIngress.java`
- Modify: `spire-gateway/src/main/java/dev/codespire/gateway/GitLabWebhookResource.java`
- Test: `spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabIngressTest.java`

**Interfaces:**
- Consumes: config `spire.review.draft-prs` (default `false`). GitLab MR attrs: `work_in_progress`/`draft` (bool), `title`, `action`, `oldrev`; draft→ready flip surfaces in `payload.changes.draft` (`{previous:true,current:false}`) or a `changes.title` that drops a `Draft:`/`WIP:` prefix.
- Produces: `GitLabIngress(secret, mapper, commands, reviewDrafts)` 4-arg ctor (3-arg delegates `false`). Draft MRs are skipped on open/update unless `reviewDrafts`; a draft→ready flip emits `OPENED`.

**Context:** Mirror `GitHubIngress` (`:135-148`, `:60-77`). GitLab has no `ready_for_review` event; the un-draft flip arrives as an `update`, so it is detected from `changes`. Fallback if the flip isn't recognized: the next push (an `update` with `oldrev`) reviews normally — safe.

- [ ] **Step 1: Write the failing test**

Add to `GitLabIngressTest.java`. Add a `mr(...)` fixture that can set `work_in_progress`, `action`, `oldrev`, and an optional `changes` block (model on the existing MR fixture in the file):

```java
    @Test
    void draftMrOpenIsSkippedByDefault() {
        assertTrue(ingress.translate(mrDraft("open", true)).isEmpty());
    }

    @Test
    void nonDraftMrOpenIsReviewed() {
        assertEquals(1, ingress.translate(mrDraft("open", false)).size());
    }

    @Test
    void draftToReadyFlipEmitsOpened() {
        var events = ingress.translate(mrReadyFlip());
        assertEquals(1, events.size());
        assertEquals(IntegrationEvent.PrAction.OPENED,
                ((IntegrationEvent.PullRequestEventReceived) events.getFirst()).action());
    }

    @Test
    void reviewDraftsTrueReviewsDraftsImmediately() {
        GitLabIngress permissive = new GitLabIngress(SECRET, new ObjectMapper(), Set.of("review"), true);
        assertEquals(1, permissive.translate(mrDraft("open", true)).size());
    }
```

Fixtures (adapt the envelope to match the file's existing MR fixture — `object_kind`, `project`, `user`, `object_attributes`):

```java
    private static byte[] mrDraft(String action, boolean draft) {
        return ("""
                { "object_kind": "merge_request",
                  "project": { "path_with_namespace": "sandbox/demo-repo" },
                  "user": { "id": 42, "username": "jdoe", "name": "Jane" },
                  "object_attributes": { "iid": 7, "action": "%s", "work_in_progress": %b,
                    "title": "%sAdd feature", "source_branch": "f", "target_branch": "main",
                    "last_commit": { "id": "abc123" }, "url": "http://gl/mr/7" } }""")
                .formatted(action, draft, draft ? "Draft: " : "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-scm-gitlab:test --tests "*GitLabIngressTest*"`
Expected: FAIL — 4-arg ctor missing / drafts not skipped.

- [ ] **Step 3: Implement**

In `GitLabIngress.java`: add the field + ctor and the draft policy.

```java
    private final boolean reviewDrafts;

    public GitLabIngress(String webhookSecret, ObjectMapper mapper, Set<String> commands) {
        this(webhookSecret, mapper, commands, false);
    }

    public GitLabIngress(String webhookSecret, ObjectMapper mapper, Set<String> commands, boolean reviewDrafts) {
        this.webhookSecret = webhookSecret;
        this.mapper = mapper;
        this.commands = Set.copyOf(commands);
        this.reviewDrafts = reviewDrafts;
    }
```

Replace `mergeRequest(...)` (`:91-102`):

```java
    private List<IntegrationEvent> mergeRequest(JsonNode payload) {
        JsonNode attrs = payload.path("object_attributes");
        boolean skipDraft = isDraft(attrs) && !reviewDrafts;
        return switch (attrs.path("action").asText("")) {
            case "open", "reopen" -> skipDraft ? List.of() : prEvent(payload, attrs, PrAction.OPENED);
            case "update" -> updateEvent(payload, attrs, skipDraft);
            case "close" -> List.of(new PullRequestClosed(repo(payload), iid(attrs), CloseReason.DECLINED));
            case "merge" -> List.of(new PullRequestClosed(repo(payload), iid(attrs), CloseReason.MERGED));
            default -> List.of();
        };
    }

    /** A draft→ready flip reviews even without a new push (GitLab has no ready_for_review event); otherwise
     *  only a push (flagged by oldrev) moves the diff. */
    private List<IntegrationEvent> updateEvent(JsonNode payload, JsonNode attrs, boolean skipDraft) {
        if (becameReady(payload) && !reviewDrafts) {
            return prEvent(payload, attrs, PrAction.OPENED);
        }
        if (skipDraft) {
            return List.of();
        }
        return attrs.has("oldrev") ? prEvent(payload, attrs, PrAction.UPDATED) : List.of();
    }

    private static boolean isDraft(JsonNode attrs) {
        if (attrs.path("work_in_progress").asBoolean(false) || attrs.path("draft").asBoolean(false)) {
            return true;
        }
        String title = attrs.path("title").asText("");
        return title.startsWith("Draft:") || title.startsWith("WIP:");
    }

    /** GitLab signals an un-draft on an update via changes.draft (or changes.work_in_progress) flipping to false. */
    private static boolean becameReady(JsonNode payload) {
        JsonNode changes = payload.path("changes");
        for (String key : new String[]{"draft", "work_in_progress"}) {
            JsonNode change = changes.path(key);
            if (change.path("previous").asBoolean(false) && !change.path("current").asBoolean(true)) {
                return true;
            }
        }
        return false;
    }
```

In `GitLabWebhookResource.java`: add the config field and pass it:

```java
import org.eclipse.microprofile.config.inject.ConfigProperty;
```
```java
    /** Draft-PR policy: default false skips drafts and waits for the draft→ready flip. */
    @ConfigProperty(name = "spire.review.draft-prs", defaultValue = "false")
    boolean reviewDrafts;

    @POST
    public Response receive(@PathParam("key") String key, @Context HttpHeaders headers, byte[] body) {
        IngressFactory ingress = secret -> new GitLabIngress(secret, mapper, COMMANDS, reviewDrafts);
        return edge.handle(PROVIDER, key, ingress, headers, body);
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :spire-scm-gitlab:test --tests "*GitLabIngressTest*" && ./gradlew :spire-gateway:build -x test`
Expected: PASS (ingress tests) + gateway compiles.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabIngress.java \
        spire-gateway/src/main/java/dev/codespire/gateway/GitLabWebhookResource.java \
        spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabIngressTest.java
git commit -m "Skip GitLab draft MRs until ready, mirroring GitHub"
```

---

## Task 6: Bitbucket draft skip + gateway wiring

**Files:**
- Modify: `spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudIngress.java`
- Modify: `spire-gateway/src/main/java/dev/codespire/gateway/BitbucketWebhookResource.java`
- Test: `spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketCloudIngressTest.java`

**Interfaces:**
- Consumes: `spire.review.draft-prs`. Bitbucket PR payload carries `pullrequest.draft` (bool).
- Produces: `BitbucketCloudIngress(config, mapper, commands, reviewDrafts)` 4-arg (3-arg delegates `false`). A draft PR is skipped on `pullrequest:created`/`pullrequest:updated`; an un-draft arrives as `pullrequest:updated` with `draft=false` and is reviewed normally (Bitbucket has no separate ready event).

- [ ] **Step 1: Write the failing test**

Add to `BitbucketCloudIngressTest.java`. Add a `pr(eventAction, draft)` fixture variant carrying `pullrequest.draft` (model on the existing PR fixture):

```java
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
```

```java
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
```

(If the test's ingress is built with an inline config rather than a `CONFIG` constant, extract that config to a `private static final BitbucketCloudConfig CONFIG = ...` field first so the permissive-ingress test can reuse it.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-scm-bitbucket:test --tests "*BitbucketCloudIngressTest*"`
Expected: FAIL — 4-arg ctor missing / drafts not skipped.

- [ ] **Step 3: Implement**

In `BitbucketCloudIngress.java` add the field + ctor:

```java
    private final boolean reviewDrafts;

    public BitbucketCloudIngress(BitbucketCloudConfig config, ObjectMapper mapper, Set<String> commands) {
        this(config, mapper, commands, false);
    }

    public BitbucketCloudIngress(BitbucketCloudConfig config, ObjectMapper mapper,
                                 Set<String> commands, boolean reviewDrafts) {
        this.config = config;
        this.mapper = mapper;
        this.commands = Set.copyOf(commands);
        this.reviewDrafts = reviewDrafts;
    }
```

Change the `translate` PR cases to gate on draft:

```java
            case "pullrequest:created" -> maybeReview(payload, PrAction.OPENED);
            case "pullrequest:updated" -> maybeReview(payload, PrAction.UPDATED);
```

Add:

```java
    /** A draft PR is skipped unless reviewDrafts; an un-draft arrives as a non-draft pullrequest:updated. */
    private List<IntegrationEvent> maybeReview(JsonNode payload, PrAction action) {
        boolean draft = payload.path("pullrequest").path("draft").asBoolean(false);
        if (draft && !reviewDrafts) {
            return List.of();
        }
        return pullRequestEvent(payload, action);
    }
```

In `BitbucketWebhookResource.java` add the config field and pass it:

```java
import org.eclipse.microprofile.config.inject.ConfigProperty;
```
```java
    @ConfigProperty(name = "spire.review.draft-prs", defaultValue = "false")
    boolean reviewDrafts;

    @POST
    public Response receive(@PathParam("key") String key, @Context HttpHeaders headers, byte[] body) {
        IngressFactory ingress = secret -> new BitbucketCloudIngress(
                new BitbucketCloudConfig(API_BASE, "unused-by-gateway", "unused-by-gateway", secret),
                mapper, COMMANDS, reviewDrafts);
        return edge.handle(PROVIDER, key, ingress, headers, body);
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :spire-scm-bitbucket:test --tests "*BitbucketCloudIngressTest*" && ./gradlew :spire-gateway:build -x test`
Expected: PASS + gateway compiles.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudIngress.java \
        spire-gateway/src/main/java/dev/codespire/gateway/BitbucketWebhookResource.java \
        spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketCloudIngressTest.java
git commit -m "Skip Bitbucket draft PRs until marked ready"
```

---

## Task 7: GitLab `Retry-After` rate-limit classification

**Files:**
- Modify: `spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabApiException.java`
- Modify: `spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabClient.java`
- Test: `spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabApiTest.java`

**Interfaces:**
- Consumes: `ScmApiException.retryAfterSeconds()` (default `null`), `isRateLimited()` (default `status==429`). `HttpResponse.headers().firstValue("Retry-After")`.
- Produces: `GitLabApiException` carries `retryAfterSeconds`; a 429 with a `Retry-After` header surfaces it. `isRateLimited()` stays the default (GitLab uses only 429; no 403-secondary shape).

**Context:** Mirror `GitHubApiException` (`:9-48`) and `GitHubClient.failure` (`:193-204`), minus the `rateLimited` flag (GitLab has no 403-secondary limit).

- [ ] **Step 1: Write the failing test**

Add to `GitLabApiTest.java` (imports for `assertNotNull`/`getMessage` as needed):

```java
    @Test
    void rateLimitCarriesRetryAfter() {
        server.stubFor(get(urlEqualTo(MR + "/changes")).willReturn(aResponse()
                .withStatus(429).withHeader("Retry-After", "42").withBody("{}")));
        GitLabApiException e = assertThrows(GitLabApiException.class,
                () -> diffSource.fetchDiff(REPO, 42, "abc123"));
        assertEquals(429, e.status());
        assertTrue(e.isRateLimited());
        assertEquals(42, e.retryAfterSeconds());
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-scm-gitlab:test --tests "*GitLabApiTest*"`
Expected: FAIL — `retryAfterSeconds()` returns null (default).

- [ ] **Step 3: Implement**

Rewrite `GitLabApiException.java` to carry `retryAfterSeconds`:

```java
package dev.codespire.scm.gitlab;

import dev.codespire.contract.scm.ScmApiException;

/** Non-2xx response from the GitLab API. 404 on a diff means the commit was force-pushed away. */
public class GitLabApiException extends RuntimeException implements ScmApiException {

    private final int status;
    private final Integer retryAfterSeconds;

    public GitLabApiException(int status, String method, String path) {
        this(status, method, path, null, null);
    }

    /** {@code detail} is a truncated, secret-free response-body snippet or guard reason. */
    public GitLabApiException(int status, String method, String path, String detail) {
        this(status, method, path, detail, null);
    }

    public GitLabApiException(int status, String method, String path, String detail, Integer retryAfterSeconds) {
        super("GitLab API " + method + " " + path + " failed with HTTP " + status
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

In `GitLabClient.java`, replace the non-2xx throw in `send(...)` (`:82-84`) and add a `failure(...)` helper:

```java
            if (status / 100 != 2) {
                throw failure(status, method, path, response);
            }
```
```java
    private static GitLabApiException failure(int status, String method, String path,
                                              HttpResponse<String> response) {
        Integer retryAfter = response.headers().firstValue("Retry-After")
                .map(GitLabClient::parseSecondsOrNull).orElse(null);
        return new GitLabApiException(status, method, path, bodySnippet(response.body()), retryAfter);
    }

    private static Integer parseSecondsOrNull(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :spire-scm-gitlab:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabApiException.java \
        spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabClient.java \
        spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabApiTest.java
git commit -m "Surface GitLab Retry-After on rate-limited responses"
```

---

## Task 8: Bitbucket `Retry-After` rate-limit classification

**Files:**
- Modify: `spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketApiException.java`
- Modify: `spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudClient.java`
- Test: `spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketCloudApiTest.java`

**Interfaces:** identical shape to Task 7 for Bitbucket. `isRateLimited()` stays default (429).

- [ ] **Step 1: Write the failing test**

Add to `BitbucketCloudApiTest.java`:

```java
    @Test
    void rateLimitCarriesRetryAfter() {
        server.stubFor(get(urlEqualTo("/repositories/sandbox/demo-repo/pullrequests/7/diff"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "17").withBody("rate limited")));
        BitbucketApiException e = assertThrows(BitbucketApiException.class,
                () -> diffSource.fetchDiff(REPO, 7, "abc123"));
        assertEquals(429, e.status());
        assertTrue(e.isRateLimited());
        assertEquals(17, e.retryAfterSeconds());
    }
```

(Confirm the diff path/PR id match the test's `REPO`; adjust the PR number to one the harness uses.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-scm-bitbucket:test --tests "*BitbucketCloudApiTest*"`
Expected: FAIL — `retryAfterSeconds()` null.

- [ ] **Step 3: Implement**

Rewrite `BitbucketApiException.java` to carry `retryAfterSeconds` (same shape as Task 7's GitLab exception, with the Bitbucket message prefix):

```java
package dev.codespire.scm.bitbucket;

import dev.codespire.contract.scm.ScmApiException;

/** Non-2xx response from the Bitbucket API. 404 on a diff means the commit was force-pushed away. */
public class BitbucketApiException extends RuntimeException implements ScmApiException {

    private final int status;
    private final Integer retryAfterSeconds;

    public BitbucketApiException(int status, String method, String path) {
        this(status, method, path, null, null);
    }

    public BitbucketApiException(int status, String method, String path, String detail) {
        this(status, method, path, detail, null);
    }

    public BitbucketApiException(int status, String method, String path, String detail, Integer retryAfterSeconds) {
        super("Bitbucket API " + method + " " + path + " failed with HTTP " + status
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

In `BitbucketCloudClient.java`, replace the non-2xx throw in `send(...)` (`:89-91`) and add the helper:

```java
            if (status / 100 != 2) {
                throw failure(status, method, path, response);
            }
```
```java
    private static BitbucketApiException failure(int status, String method, String path,
                                                 HttpResponse<String> response) {
        Integer retryAfter = response.headers().firstValue("Retry-After")
                .map(BitbucketCloudClient::parseSecondsOrNull).orElse(null);
        return new BitbucketApiException(status, method, path, bodySnippet(response.body()), retryAfter);
    }

    private static Integer parseSecondsOrNull(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :spire-scm-bitbucket:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketApiException.java \
        spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudClient.java \
        spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketCloudApiTest.java
git commit -m "Surface Bitbucket Retry-After on rate-limited responses"
```

---

## Task 9: GitLab NEW-side multi-line inline ranges

**Files:**
- Modify: `spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabCommentSink.java`
- Test: `spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabApiTest.java`

**Interfaces:**
- Consumes: `InlineAnchor.endNewLine()` (non-null only for a NEW-side range, `InlineAnchor.java`), `Side.NEW`.
- Produces: `postInline` sends a GitLab `position.line_range` (start/end on the NEW side) when the anchor is a NEW-side range; single-line/OLD-side anchors are unchanged. A GitLab 4xx rejecting the range is NOT caught here — the worker's existing per-finding isolation folds it into the summary (no adapter change needed; verify the worker catch is provider-neutral).

**Context:** GitLab multi-line diff comments carry `position.line_range.{start,end}`, each `{type:"new", new_line:N}`. Mirror GitHub's start/end range (`GitHubCommentSink.java:76-84`).

Before implementing, VERIFY the fold-to-summary is provider-neutral: read the review-worker post loop (`spire-review-worker/.../ReviewWorker.java`) and confirm a per-finding inline-post failure (`ScmApiException`) is caught and folded into the summary for ANY provider (not GitHub-specific). If it is GitHub-specific, STOP and escalate — that would be an out-of-scope worker change. (Expected: it is generic — Phase-1 delivered "per-finding post isolation".)

- [ ] **Step 1: Write the failing test**

Add to `GitLabApiTest.java`:

```java
    @Test
    void postsMultiLineRangeAsLineRange() {
        server.stubFor(post(urlEqualTo(MR + "/discussions")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{ \"id\": \"DISC1\", \"notes\": [ { \"id\": 100 } ] }")));

        InlineAnchor range = new InlineAnchor("src/App.java", "src/App.java", null, 10, Side.NEW, 14);
        DiffRefs refs = new DiffRefs("base000", "start000", "abc123");
        commentSink.postInline(REPO, 42, refs, range, "range finding");

        server.verify(postRequestedFor(urlEqualTo(MR + "/discussions")).withRequestBody(equalToJson("""
                { "body": "range finding",
                  "position": { "position_type": "text", "base_sha": "base000", "start_sha": "start000",
                    "head_sha": "abc123", "old_path": "src/App.java", "new_path": "src/App.java", "new_line": 10,
                    "line_range": { "start": { "type": "new", "new_line": 10 },
                                    "end": { "type": "new", "new_line": 14 } } } }""", true, true)));
    }
```

(`postInline(RepoRef, long prId, DiffRefs, InlineAnchor, String)` — the same signature the existing single-line inline test in `GitLabApiTest` uses. `equalToJson(..., true, true)` matches ignoring order and extra fields.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-scm-gitlab:test --tests "*GitLabApiTest*"`
Expected: FAIL — no `line_range` in the posted body.

- [ ] **Step 3: Implement**

In `GitLabCommentSink.postInline` (`:46-68`), after the `new_line` block and before building `path`, add the range projection:

```java
        // A NEW-side finding spanning multiple lines within one hunk posts a GitLab line_range.
        if (anchor.side() == Side.NEW && anchor.endNewLine() != null && anchor.endNewLine() > anchor.newLine()) {
            position.put("line_range", Map.of(
                    "start", Map.of("type", "new", "new_line", anchor.newLine()),
                    "end", Map.of("type", "new", "new_line", anchor.endNewLine())));
        }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :spire-scm-gitlab:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabCommentSink.java \
        spire-scm-gitlab/src/test/java/dev/codespire/scm/gitlab/GitLabApiTest.java
git commit -m "Post GitLab multi-line findings as a line_range"
```

---

## Task 10: Runbook (Mode F + conversation/reconciliation) + Bitbucket compare verify

**Files:**
- Modify: `docs/SMOKE-TEST.md`
- Modify: `spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketReconciliationTest.java`

**Interfaces:** documentation + a test-comment hardening. No production code change (the Bitbucket compare direction `{head}..{base}` is verified correct — source=head=additions — against Bitbucket's REST docs; the live check is a runbook gate, not a code flip).

**Context:** `SMOKE-TEST.md` today has Mode A–E; it lacks a GitLab-webhook mode and any conversation/reconciliation steps for GitLab/Bitbucket. Model Mode F on the existing Mode E (GitHub webhook) but with GitLab specifics (`X-Gitlab-Token`, `/webhooks/gitlab/{key}`, Merge Request + Note events).

- [ ] **Step 1: Harden the Bitbucket compare test doc**

In `BitbucketReconciliationTest.java`, at the `fetchCompareDiff` test that pins the URL `.../diff/bbb..aaa` for `(base=aaa, head=bbb)`, add a comment above the assertion:

```java
        // Bitbucket diff spec is {source}..{destination} and additions come from the SOURCE (first token),
        // so {head}..{base} yields the new commit's changes as additions — the reconciliation lens.
        // Verified against Bitbucket's REST docs; confirmed live per SMOKE-TEST.md Mode B step "compare direction".
```

- [ ] **Step 2: Add Mode F and conversation/reconciliation steps to SMOKE-TEST.md**

Add a new **Mode F — real GitLab MR via webhook** section after Mode E, structured like Mode E:
- Prereqs: register a GitLab provider (Mode D step 1); webhook secret is a plain token (GitLab does not sign), set it in Settings → Webhooks (provider `gitlab`) and paste the same value into the GitLab project → Settings → Webhooks → **Secret token**; the payload URL path is `/webhooks/gitlab/{key}`.
- Triggers: **Merge request events** and **Comments (notes)**.
- Tunnel: Tailscale Funnel `tailscale funnel 34081` (same as Mode E).
- Run all three services; open/update an MR → dashboard shows diff → LLM → inline discussions + summary note.
- A `/review` note forces a re-run; any other MR note starts/continues a conversation.

Add a **"Conversation + reconciliation (all real modes)"** subsection covering both GitLab (Mode D/F) and Bitbucket (Mode B):
- **Conversation:** reply under a bot inline finding (or a plain PR/MR comment) → within ~LLM latency the bot answers in-thread. Verify: the reply appears nested under the finding (GitLab discussion / Bitbucket comment thread) or in the summary thread for a top-level comment.
- **Reconciliation:** push a follow-up commit that fixes one finding and leaves another → a re-review runs; GitLab **resolves** the fixed finding's discussion and replies to the still-open one, updating the summary note in place; Bitbucket **replies** to the fixed finding's thread (reply-only — no resolve API) and updates the summary comment in place.
- **Bitbucket compare direction (live gate):** after a Bitbucket re-review, open the worker log or the reconcile prompt and confirm the incremental diff shows the NEW commit's lines as **additions** (`+`), not reversed. If reversed, the remedy is to swap `head + ".." + base` to `base + ".." + head` in `BitbucketCloudDiffSource.fetchCompareDiff` — but the expected result is that it is already correct.

Update **"Known v1 limits"**: draft/WIP skip now applies to GitLab and Bitbucket too (not just GitHub); Bitbucket reconciliation is reply-only (no thread-resolve API); Bitbucket inline comments are single-line (no multi-line ranges).

- [ ] **Step 3: Verify the doc renders and the test still passes**

Run: `./gradlew :spire-scm-bitbucket:test --tests "*BitbucketReconciliationTest*"`
Expected: PASS. Manually skim `docs/SMOKE-TEST.md` for Mode F + the conversation/reconciliation subsection.

- [ ] **Step 4: Commit**

```bash
git add docs/SMOKE-TEST.md \
        spire-scm-bitbucket/src/test/java/dev/codespire/scm/bitbucket/BitbucketReconciliationTest.java
git commit -m "Document GitLab webhook, conversation, and reconciliation runbook modes"
```

---

## Final verification (after all tasks)

- [ ] Run the full adapter + gateway suites:
  `./gradlew :spire-scm-gitlab:test :spire-scm-bitbucket:test :spire-gateway:test :spire-scm-github:test`
  Expected: all green; the GitHub suite unchanged (adapter untouched). Note pre-existing flakes (`OrchestratorChoreographyTest`, `GitHubWebhookTest`, `GitLabWebhookTest` — Kafka races) may need a re-run; they are not caused by this work.
- [ ] Confirm no change to `spire-scm-github/src/main`.
- [ ] Confirm `FollowUpWorker.java`, `ConversationSaga.java`, `ReviewWorker.java` were NOT modified (conversation lights up purely via the new `ThreadSource` implementations).

---

## Success criteria (from the spec)

1. A reply in a GitLab MR discussion and in a Bitbucket PR thread each gets an in-thread bot answer.
2. A follow-up commit runs reconciliation on both (GitLab resolves + updates note; Bitbucket replies + updates comment).
3. Draft MRs/PRs are skipped until ready on both (unless `SPIRE_REVIEW_DRAFT_PRS`).
4. GitLab webhook auto-registers MRs (Mode F).
5. The Bitbucket compare direction is confirmed correct against a live workspace (runbook gate).
6. All new WireMock suites green; existing suites unaffected; the GitHub adapter untouched.
