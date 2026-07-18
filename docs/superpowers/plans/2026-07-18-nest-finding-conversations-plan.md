# Nest Finding-Linked Conversations Under Their Findings — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the review detail page, render a finding's conversation *inside* that finding (collapsible, with a turn-count badge), and route conversations not tied to a finding (summary replies, `@`-mentions) to a separate "General discussion" section.

**Architecture:** Read-model + UI only — the aggregate is untouched (ADR-010 holds). The finding↔thread link already exists in the events (`PostedInline(commentId, path, line)`; `AuthorReplied`/`FollowUpGenerated` carry `threadRef`); we project it. Two additive Flyway migrations record `(path, line, is_summary)` on `review_thread` and `thread_ref` on `review_event`. `loadDetail` joins findings (`path:line`) to threads and classifies each conversation turn (`finding` | `summary` | `mention`). The UI filters events by `threadRef`.

Spec: [`docs/superpowers/specs/2026-07-18-nest-finding-conversations-design.md`](../specs/2026-07-18-nest-finding-conversations-design.md).

**Tech Stack:** Java 25 / Quarkus 3.36, hand-rolled JDBC + Flyway (orchestrator read model), Tink (findings encrypted at rest — untouched here). React 19 / Vite / TypeScript (spire-ui). No new dependencies; `spire-contract` needs **no change** (`PostedInline` already carries `path`/`line`; events already carry `threadRef`).

## Global Constraints

- **Display-only — do NOT change conversation scope/policy.** In particular, `markSummaryThread` sets `is_summary = TRUE` and **must leave `is_ours` at its default `FALSE`** — flipping it would make the bot start answering summary replies (a behaviour change). Only `markFindingThread` sets `is_ours = TRUE` (matching today's `markOurThread`).
- **Match findings to threads by stable `path:line`** (finding `loc = path + ":" + range.startLine()`), never by raw commentId. First finding wins on a `path:line` collision (documented tie-break).
- **Graceful degradation, never drop a turn.** A turn whose `thread_ref` is NULL (pre-migration rows, e.g. PR #6) or matches no current finding classifies as `mention` → General discussion.
- **Additive migrations only.** New columns are nullable / defaulted; existing rows read back cleanly.
- **4-space indent for Java, 2-space for TS.** TS object shapes use `interface`. Java: explicit types, records for DTOs, guard clauses, methods ≤30 lines.
- **Money/tokens conventions unchanged.** This feature touches neither.
- **Test execution environments:**
  - Backend `@QuarkusTest` suites (Testcontainers/DevServices Postgres) run under `./gradlew build` on a Docker host. In the JDK-25 throwaway container they are **compile-verified only** — `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd)":/workspace -w /workspace -v spire-gradle-test:/root/.gradle eclipse-temurin:25-jdk ./gradlew :spire-orchestrator:compileTestJava --offline -Dquarkus.devservices.enabled=false -Dquarkus.datasource.devservices.enabled=false`.
  - UI: `docker exec -e NODE_OPTIONS= spire-ui-dev npx tsc --noEmit` and `docker exec -e NODE_OPTIONS= spire-ui-dev npx vitest run`.

## API contract produced by this plan (types the tasks agree on)

- `ReviewDetail.FindingView(String sev, String loc, String msg, String threadRef)` — `threadRef` nullable; the SCM thread the finding owns.
- `ReviewDetail.EventView(String ts, String at, String lane, String type, String det, String threadRef, String threadKind)` — `threadRef`/`threadKind` nullable; `threadKind ∈ {"finding","summary","mention"}` for conversation turns, null otherwise.
- Frontend `Finding` gains `threadRef?: string`; `ReviewEvent` gains `threadRef?: string` and `threadKind?: 'finding' | 'summary' | 'mention'`.
- **Note (deviation from spec §4.3):** `conversationTurns` is **not** added to `FindingView` — the badge count is derived on the frontend from the events already filtered by `threadRef` (DRY; the events list is the single source).

---

### Task 1: Record finding/summary thread metadata at `CommentsPosted`

After a review posts its comments, `review_thread` carries each finding thread's `(path, line)` and flags the summary thread — the raw material for the finding↔thread join.

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V17__review_thread_finding_link.sql`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewThreadView.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java:152-155` (the `CommentsPosted` thread-marking loop)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewThreadLinkTest.java`

**Interfaces:**
- Consumes: `CommentsPosted.PostedInline(commentId, path, line)` + `summaryCommentId` (already emitted).
- Produces: `ReviewThreadView.markFindingThread(String reviewId, ThreadRef thread, String path, int line)` and `markSummaryThread(String reviewId, ThreadRef thread)`; `review_thread.{path, line, is_summary}` columns.

- [ ] **Step 1: Write the failing test**

`ReviewThreadLinkTest.java`:
```java
package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.scm.ThreadRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReviewThreadLinkTest {

    @Inject
    ReviewThreadView threads;

    @Inject
    DataSource dataSource;

    @Test
    void findingThreadStoresPathAndLineAndOwnership() throws Exception {
        String id = "review::acme/web#7001";
        threads.markFindingThread(id, new ThreadRef("c1"), "src/App.java", 9);

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT path, line, is_ours, is_summary FROM review_thread "
                             + "WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, id);
            ps.setString(2, "c1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("src/App.java", rs.getString("path"));
                assertEquals(9, rs.getInt("line"));
                assertTrue(rs.getBoolean("is_ours"), "a finding thread is one of ours (scope A)");
                assertFalse(rs.getBoolean("is_summary"));
            }
        }
    }

    @Test
    void summaryThreadIsFlaggedButNotMarkedOurs() throws Exception {
        String id = "review::acme/web#7002";
        threads.markSummaryThread(id, new ThreadRef("sum1"));

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_ours, is_summary FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, id);
            ps.setString(2, "sum1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("is_summary"));
                assertFalse(rs.getBoolean("is_ours"),
                        "display-only: the summary thread must NOT become 'ours' (no scope change)");
            }
        }
    }
}
```

- [ ] **Step 2: Run the test, verify it fails to compile**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd)":/workspace -w /workspace -v spire-gradle-test:/root/.gradle eclipse-temurin:25-jdk ./gradlew :spire-orchestrator:compileTestJava --offline -Dquarkus.devservices.enabled=false -Dquarkus.datasource.devservices.enabled=false`
Expected: FAIL — `markFindingThread` / `markSummaryThread` not defined.

- [ ] **Step 3: Add the migration**

`V17__review_thread_finding_link.sql`:
```sql
-- Link finding threads to their (path, line) and flag the summary thread, so the review
-- detail can nest a conversation under its finding and route the rest to General discussion.
-- Additive: existing rows keep NULL path/line and is_summary = FALSE.
ALTER TABLE review_thread ADD COLUMN path       TEXT;
ALTER TABLE review_thread ADD COLUMN line       INT;
ALTER TABLE review_thread ADD COLUMN is_summary BOOLEAN NOT NULL DEFAULT FALSE;
```

- [ ] **Step 4: Add the two mark methods to `ReviewThreadView`**

**Add** `markFindingThread` (sets `is_ours = TRUE` like `markOurThread`, plus `path`/`line`) and `markSummaryThread`. **Keep `markOurThread`** unchanged — it is still called by `ReviewThreadViewIT` (removing it breaks that test). Leave `bumpTurn`, `turnCount`, `isOurThread` unchanged too.
```java
public void markFindingThread(String reviewId, ThreadRef thread, String path, int line) {
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO review_thread (review_id, thread_ref, is_ours, path, line)
                 VALUES (?, ?, TRUE, ?, ?)
                 ON CONFLICT (review_id, thread_ref)
                 DO UPDATE SET is_ours = TRUE, path = EXCLUDED.path, line = EXCLUDED.line
                 """)) {
        ps.setString(1, reviewId);
        ps.setString(2, thread.value());
        ps.setString(3, path);
        ps.setInt(4, line);
        ps.executeUpdate();
    } catch (SQLException e) {
        throw new IllegalStateException("Failed to mark finding thread", e);
    }
}

/** Flag the summary comment's thread. Display-only: does NOT set is_ours, so conversation
 *  scope (which threads the bot answers) is unchanged. */
public void markSummaryThread(String reviewId, ThreadRef thread) {
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement("""
                 INSERT INTO review_thread (review_id, thread_ref, is_summary) VALUES (?, ?, TRUE)
                 ON CONFLICT (review_id, thread_ref) DO UPDATE SET is_summary = TRUE
                 """)) {
        ps.setString(1, reviewId);
        ps.setString(2, thread.value());
        ps.executeUpdate();
    } catch (SQLException e) {
        throw new IllegalStateException("Failed to mark summary thread", e);
    }
}
```

- [ ] **Step 5: Wire it in `ResultSaga.CommentsPosted`**

Replace the existing loop at `ResultSaga.java:152-155`:
```java
// Scope A: every inline finding's comment id is a thread we own — a reply there engages the bot.
for (CommentsPosted.PostedInline inline : e.inline()) {
    threads.markOurThread(e.reviewId(), new ThreadRef(inline.commentId()));
}
```
with:
```java
// Scope A: every inline finding's comment id is a thread we own — a reply there engages the bot.
// Record its (path, line) too so the review detail can nest that finding's conversation.
// (The partial-retry reconstruction branch in the worker emits (anchorKey, 0); such a row simply
//  won't match a finding loc and its thread falls to General discussion — never lost.)
for (CommentsPosted.PostedInline inline : e.inline()) {
    threads.markFindingThread(e.reviewId(), new ThreadRef(inline.commentId()), inline.path(), inline.line());
}
// Flag the summary thread so its replies classify as "general" (not a finding). is_ours unchanged.
threads.markSummaryThread(e.reviewId(), new ThreadRef(e.summaryCommentId()));
```

- [ ] **Step 6: Verify the tests pass (on a Docker host) and compile in the container**

Run (Docker host): `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.readmodel.ReviewThreadLinkTest'`
Expected: PASS (2 tests).
Fallback (throwaway container): the `compileTestJava` command from Step 2 — Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**
```bash
git add spire-orchestrator/src/main/resources/db/migration/V17__review_thread_finding_link.sql \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewThreadView.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewThreadLinkTest.java
git commit -m "Record finding (path,line) and flag the summary thread"
```

---

### Task 2: Persist `thread_ref` on conversation event appends

Each `AuthorReplied` / `FollowUpGenerated` timeline row records which SCM thread it belongs to, so the detail projection can group turns by thread.

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V18__review_event_thread_ref.sql`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java` (`appendEvent` — add a 5-arg overload)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/IntegrationSaga.java:99-100` (`AuthorReplied` append)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java:158` (`FollowUpGenerated` append)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewEventThreadRefTest.java`

**Interfaces:**
- Consumes: `AuthorReplied.threadRef()`, `FollowUpGenerated.threadRef()` (both `ThreadRef`, already present).
- Produces: `ReviewProjection.appendEvent(reviewId, lane, type, detail, String threadRef)`; `review_event.thread_ref` column readable by `loadEvents` (Task 3).

- [ ] **Step 1: Write the failing test**

`ReviewEventThreadRefTest.java`:
```java
package dev.codespire.orchestrator.readmodel;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReviewEventThreadRefTest {

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void conversationAppendPersistsThreadRefAndPlainAppendLeavesItNull() throws Exception {
        String id = "review::acme/web#7101";
        projection.appendEvent(id, "integration", "AuthorReplied", "@bob: why?", "c1");
        projection.appendEvent(id, "integration", "PullRequestEventReceived", "opened"); // 4-arg → null

        assertEquals("c1", threadRefOf(id, "AuthorReplied"));
        assertNull(threadRefOf(id, "PullRequestEventReceived"));
    }

    private String threadRefOf(String reviewId, String type) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT thread_ref FROM review_event WHERE review_id = ? AND type = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString("thread_ref");
            }
        }
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: the `compileTestJava` command (Task 1 Step 2). Expected: FAIL — no 5-arg `appendEvent`.

- [ ] **Step 3: Add the migration**

`V18__review_event_thread_ref.sql`:
```sql
-- Which SCM thread a conversation turn (AuthorReplied / FollowUpGenerated) belongs to.
-- NULL for non-conversation events and for turns recorded before this column existed.
ALTER TABLE review_event ADD COLUMN thread_ref TEXT;
```

- [ ] **Step 4: Add the `appendEvent` overload**

In `ReviewProjection.java`, keep the existing 4-arg method as a delegator and add the 5-arg body:
```java
public void appendEvent(String reviewId, String lane, String type, String detail) {
    appendEvent(reviewId, lane, type, detail, null);
}

/** As above, tagging the row with the SCM {@code threadRef} it belongs to (null for
 *  non-conversation events) so the detail projection can group turns by thread. */
public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
    String sql = """
            INSERT INTO review_event (review_id, seq, lane, type, detail, thread_ref)
            SELECT ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?, ? FROM review_event WHERE review_id = ?
            """;
    for (int attempt = 1; attempt <= SEQ_RETRY_LIMIT; attempt++) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            ps.setString(2, lane);
            ps.setString(3, type);
            ps.setString(4, detail == null ? "" : detail);
            ps.setString(5, threadRef);
            ps.setString(6, reviewId);
            ps.executeUpdate();
            return;
        } catch (SQLException e) {
            if (!UNIQUE_VIOLATION.equals(e.getSQLState()) || attempt == SEQ_RETRY_LIMIT) {
                throw new IllegalStateException("review_event write failed for " + reviewId, e);
            }
            // lost the seq race to a concurrent consumer — recompute MAX and retry
        }
    }
}
```

- [ ] **Step 5: Pass `threadRef` from the two conversation appends**

`IntegrationSaga.java:99-100` — the `AuthorReplied` append:
```java
projection.appendEvent(e.reviewId(), "integration", "AuthorReplied",
        "@" + author + ": " + Previews.of(e.text()), e.threadRef().value());
```

`ResultSaga.java:158` — the `FollowUpGenerated` append:
```java
projection.appendEvent(e.reviewId(), "result", "FollowUpGenerated",
        Previews.of(e.answerText()), e.threadRef().value());
```

- [ ] **Step 6: Verify**

Run (Docker host): `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.readmodel.ReviewEventThreadRefTest'` → PASS.
Fallback: `compileTestJava` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**
```bash
git add spire-orchestrator/src/main/resources/db/migration/V18__review_event_thread_ref.sql \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/IntegrationSaga.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewEventThreadRefTest.java
git commit -m "Tag conversation timeline events with their thread ref"
```

---

### Task 3: Project the linkage on the review detail

`loadDetail` attaches each finding's `threadRef` and classifies each conversation turn (`finding` | `summary` | `mention`), so the UI is a dumb renderer.

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewDetail.java` (`FindingView`, `EventView`)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java` (`toView`, `loadDetail`, `loadEvents`, `toDetail`, + a thread-index loader)
- Test: add cases to `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewProjectionTest.java`

**Interfaces:**
- Consumes: `review_thread.{path,line,is_summary}` (Task 1), `review_event.thread_ref` (Task 2), finding `loc = path + ":" + startLine` (existing `toView`).
- Produces: the API contract at the top of this plan — `FindingView.threadRef`, `EventView.threadRef`/`threadKind`.

- [ ] **Step 1: Write the failing test**

Add to `ReviewProjectionTest.java`:
```java
@Test
void detailLinksFindingConversationsAndClassifiesTurns() {
    long pr = 4108L;
    String id = ReviewIds.reviewId(REPO, pr);
    projection.registerHeader(id, REPO, pr, "Fib", "jlee", "acc-9",
            "fix", "main", "beef999", "https://x/pr/4108", "github", "reviewing",
            ReviewProjection.STAGE_DIFF);

    var result = new ReviewResult(
            List.of(new Finding("src/App.java", new LineRange(9, 9), Severity.BLOCKER, "no compile", null)),
            "One blocker.", new ModelUsage("gpt-x", 10, 5, 100));
    projection.recordOutcome(id, result, ReviewProjection.STAGE_COMMENTS);

    // finding thread c1 (matches src/App.java:9), summary thread sum1
    threads.markFindingThread(id, new ThreadRef("c1"), "src/App.java", 9);
    threads.markSummaryThread(id, new ThreadRef("sum1"));

    projection.appendEvent(id, "integration", "AuthorReplied", "@jlee: why?", "c1");
    projection.appendEvent(id, "result", "FollowUpGenerated", "Because …", "c1");
    projection.appendEvent(id, "integration", "AuthorReplied", "@jlee: overall?", "sum1");
    projection.appendEvent(id, "integration", "AuthorReplied", "@jlee: @bot look here", "m1");
    projection.appendEvent(id, "result", "FollowUpGenerated", "legacy turn"); // 4-arg → null thread_ref

    ReviewDetail d = projection.loadDetail("acme", "web", pr).orElseThrow();

    assertEquals("c1", d.findingsList().get(0).threadRef(), "finding adopts its thread by path:line");
    assertEquals("finding", kindOf(d, "c1"));
    assertEquals("summary", kindOf(d, "sum1"));
    assertEquals("mention", kindOf(d, "m1"), "an @-mention thread on no finding is general");
    assertTrue(d.events().stream().anyMatch(e -> e.threadRef() == null && e.threadKind() == null),
            "a pre-migration turn (null thread_ref) has no kind and falls to General");
}

private static String kindOf(ReviewDetail d, String threadRef) {
    return d.events().stream().filter(e -> threadRef.equals(e.threadRef()))
            .map(ReviewDetail.EventView::threadKind).findFirst().orElseThrow();
}
```
Add imports as needed: `dev.codespire.contract.scm.ThreadRef`, and inject `ReviewThreadView threads;` into the test class (alongside the existing `projection`/`dataSource`).

- [ ] **Step 2: Run it, verify it fails**

Run: `compileTestJava`. Expected: FAIL — `FindingView.threadRef()` / `EventView.threadKind()` not defined.

- [ ] **Step 3: Extend the DTOs**

In `ReviewDetail.java`:
```java
/** A finding as the UI renders it: severity slug, "path:line" location, message, and the SCM
 *  thread it owns ({@code threadRef}, null when it has no conversation / predates thread linking). */
public record FindingView(String sev, String loc, String msg, String threadRef) {
}
```
```java
/**
 * One line of the review's scoped event stream. {@code ts}/{@code at}: see above. {@code threadRef}
 * is the SCM thread a conversation turn belongs to (null otherwise); {@code threadKind} classifies it
 * as "finding" | "summary" | "mention" for the UI (null for non-conversation turns).
 */
public record EventView(String ts, String at, String lane, String type, String det,
                        String threadRef, String threadKind) {
}
```

- [ ] **Step 4: `toView` passes null threadRef (unknown at store time)**

In `ReviewProjection.java`:
```java
private ReviewDetail.FindingView toView(Finding f) {
    String loc = f.range() == null ? f.path() : f.path() + ":" + f.range().startLine();
    return new ReviewDetail.FindingView(severitySlug(f.severity()), loc, f.message(), null);
}
```

- [ ] **Step 5: Load the thread index, enrich findings, classify events in `loadDetail`**

Add a thread-row loader + record near `loadEvents`:
```java
private record ThreadRow(String threadRef, String path, Integer line, boolean isSummary) {}

private List<ThreadRow> loadThreadRows(Connection c, String reviewId) throws SQLException {
    List<ThreadRow> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(
            "SELECT thread_ref, path, line, is_summary FROM review_thread WHERE review_id = ?")) {
        ps.setString(1, reviewId);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ThreadRow(rs.getString("thread_ref"), rs.getString("path"),
                        (Integer) rs.getObject("line"), rs.getBoolean("is_summary")));
            }
        }
    }
    return out;
}
```

Rewrite `loadDetail` to build the index, enrich the findings, and pass a classifier to `loadEvents`:
```java
public Optional<ReviewDetail> loadDetail(String workspace, String slug, long pr) {
    String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
    try (Connection c = dataSource.getConnection()) {
        ReviewRow row = loadRow(c, reviewId);
        if (row == null) {
            return Optional.empty();
        }
        List<ThreadRow> threadRows = loadThreadRows(c, reviewId);

        // loc "path:line" -> threadRef (first wins on a same-line collision).
        Map<String, String> threadByLoc = new HashMap<>();
        Set<String> summaryRefs = new HashSet<>();
        for (ThreadRow t : threadRows) {
            if (t.isSummary()) {
                summaryRefs.add(t.threadRef());
            }
            if (t.path() != null && t.line() != null) {
                threadByLoc.putIfAbsent(t.path() + ":" + t.line(), t.threadRef());
            }
        }

        // Attach each finding's thread, and collect the threadRefs a CURRENT finding claims.
        List<ReviewDetail.FindingView> findings = new ArrayList<>();
        Set<String> findingRefs = new HashSet<>();
        for (ReviewDetail.FindingView f : parseFindings(row.findingsJson, row.id)) {
            String ref = threadByLoc.get(f.loc());
            if (ref != null) {
                findingRefs.add(ref);
            }
            findings.add(new ReviewDetail.FindingView(f.sev(), f.loc(), f.msg(), ref));
        }

        java.util.function.Function<String, String> classifier = threadRef -> {
            if (threadRef == null) {
                return null;
            }
            if (findingRefs.contains(threadRef)) {
                return "finding";
            }
            return summaryRefs.contains(threadRef) ? "summary" : "mention";
        };

        return Optional.of(toDetail(row, loadEvents(c, reviewId, row.createdAt, classifier),
                llmCalls(reviewId), findings));
    } catch (SQLException e) {
        throw new IllegalStateException("Failed to load review " + reviewId, e);
    }
}
```
Ensure imports: `java.util.HashMap`, `java.util.HashSet`, `java.util.Map`, `java.util.Set` (add any missing).

- [ ] **Step 6: Thread the classifier through `loadEvents` and take findings in `toDetail`**

`loadEvents` gains the classifier and selects `thread_ref`:
```java
private List<ReviewDetail.EventView> loadEvents(Connection c, String reviewId, Instant t0,
        java.util.function.Function<String, String> threadKind) throws SQLException {
    List<ReviewDetail.EventView> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(
            "SELECT lane, type, detail, at, thread_ref FROM review_event WHERE review_id = ? ORDER BY seq")) {
        ps.setString(1, reviewId);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Instant at = rs.getTimestamp("at").toInstant();
                String threadRef = rs.getString("thread_ref");
                out.add(new ReviewDetail.EventView(at.toString(), relative(t0, at), rs.getString("lane"),
                        rs.getString("type"), rs.getString("detail"), threadRef, threadKind.apply(threadRef)));
            }
        }
    }
    return out;
}
```

`toDetail` takes the pre-built findings instead of re-parsing:
```java
private ReviewDetail toDetail(ReviewRow r, List<ReviewDetail.EventView> events,
                              List<ReviewDetail.LlmCall> llmCalls, List<ReviewDetail.FindingView> findings) {
    return new ReviewDetail(r.id, r.workspace, r.slug, r.slug, r.pr, r.title, r.author, r.authorId,
            r.branch, r.base, r.sha, r.htmlUrl, r.providerType, r.status, r.stage, r.findings, blockerCount(r),
            r.updatedAt, r.attempt, computeStages(r.status, r.stage), List.of("", "", "", "", "", ""),
            findings, usageView(r), withReviewCall(r, llmCalls), r.note,
            decryptError(r.errorDetail, r.id), events);
}
```
(`blockerCount(r)` still parses `findingsJson` independently to count criticals — leave it; it doesn't need `threadRef`.)

- [ ] **Step 7: Verify**

Run (Docker host): `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.readmodel.ReviewProjectionTest'` → PASS (existing + new case).
Fallback: `compileTestJava` → BUILD SUCCESSFUL. Then compile the module main too: `./gradlew :spire-orchestrator:compileJava --offline …` → BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**
```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewDetail.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewProjectionTest.java
git commit -m "Project finding-thread links and classify conversation turns"
```

---

### Task 4: Frontend — types, General discussion section, retire the flat card

Add the API fields, extract the exchange-rendering body so it can be reused, and replace the standalone `conversationCard` with a `generalDiscussionCard` that shows only non-finding turns.

**Files:**
- Modify: `spire-ui/src/api.ts` (`Finding`, `ReviewEvent`)
- Modify: `spire-ui/src/render.tsx` (extract `conversationExchangesBody`; add `generalDiscussionCard`; keep helpers)
- Modify: `spire-ui/src/components/ReviewDetail.tsx:6,219` (import + swap `conversationCard` → `generalDiscussionCard`)
- Test: `spire-ui/src/render.conversation.test.tsx`

**Interfaces:**
- Consumes: `ReviewEvent.threadKind` (Task 3).
- Produces: `generalDiscussionCard(r)`; `conversationExchangesBody(turns)` reused by Task 5.

- [ ] **Step 1: Write the failing test**

`render.conversation.test.tsx` (uses `react-dom/server` — already a dependency; no DOM env / no new deps, matching the repo's node test setup):
```tsx
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { generalDiscussionCard } from './render';
import type { ReviewDetail, ReviewEvent } from './api';

function detail(events: ReviewEvent[]): ReviewDetail {
  return { events, findingsList: [] } as unknown as ReviewDetail;
}

const ev = (type: string, det: string, threadKind?: ReviewEvent['threadKind']): ReviewEvent => ({
  ts: '2026-07-18T00:00:00Z', at: '+1s', lane: 'integration', type, det, threadKind,
});

describe('generalDiscussionCard', () => {
  it('renders only non-finding turns', () => {
    const html = renderToStaticMarkup(
      <>{generalDiscussionCard(detail([
        ev('AuthorReplied', '@a: on line 9', 'finding'),   // excluded
        ev('AuthorReplied', '@a: overall?', 'summary'),    // included
      ]))}</>,
    );
    expect(html).toContain('General discussion');
    expect(html).toContain('overall?');
    expect(html).not.toContain('on line 9');
  });

  it('renders nothing when there are only finding turns', () => {
    expect(generalDiscussionCard(detail([ev('AuthorReplied', '@a: x', 'finding')]))).toBeNull();
  });
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `docker exec -e NODE_OPTIONS= spire-ui-dev npx vitest run src/render.conversation.test.tsx`
Expected: FAIL — `generalDiscussionCard` not exported.

- [ ] **Step 3: Add the API fields**

`api.ts`:
```ts
export interface Finding {
  sev: 'critical' | 'warning' | 'suggestion' | 'nit';
  loc: string;
  msg: string;
  threadRef?: string; // the SCM thread this finding owns (present when it has a conversation)
}
```
```ts
export interface ReviewEvent {
  ts: string;
  at: string;
  lane: 'integration' | 'command' | 'domain' | 'result';
  type: string;
  det: string;
  threadRef?: string; // the SCM thread a conversation turn belongs to
  threadKind?: 'finding' | 'summary' | 'mention'; // classification for nesting; absent for non-turns
}
```

- [ ] **Step 4: Extract the exchange body and add `generalDiscussionCard`**

In `render.tsx`, refactor `conversationCard`'s inner `<div className="convo">…</div>` into a reusable function, then define `generalDiscussionCard`. Replace the whole `conversationCard` export with:
```tsx
/** The `<div className="convo">` body: ordered turns grouped into question→answer exchanges.
 *  Reused by the per-finding nested panel (findingsCard) and generalDiscussionCard. */
export function conversationExchangesBody(turns: ReviewEvent[]) {
  const exchanges = toConversationExchanges(turns);
  return (
    <div className="convo">
      {exchanges.map((ex: ConversationExchange, i: number) => (
        <div key={i} className="convo-exchange">
          {ex.question && conversationTurnRow(ex.question, false, 'q')}
          {ex.answer && conversationTurnRow(ex.answer, !!ex.question, 'a')}
        </div>
      ))}
    </div>
  );
}

/** Conversations NOT tied to a finding — summary-comment replies, @-mentions, and orphan bot
 *  answers (threadKind !== 'finding', including null). Hidden when empty. */
export function generalDiscussionCard(r: ReviewDetail) {
  const turns = r.events.filter(
    (e: ReviewEvent) =>
      (e.type === 'AuthorReplied' || e.type === 'FollowUpGenerated') && e.threadKind !== 'finding',
  );
  if (!turns.length) return null;
  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>General discussion</h3>
        <span className="badge">{turns.length}</span>
      </div>
      <div className="body">{conversationExchangesBody(turns)}</div>
    </div>
  );
}
```
Keep `ConversationTurn`, `ConversationExchange`, `toConversationTurn`, `toConversationExchanges`, and `conversationTurnRow` exactly as they are (now shared). Delete the old `conversationCard` function.

- [ ] **Step 5: Swap the card in `ReviewDetail.tsx`**

Line 6 import: replace `conversationCard` with `generalDiscussionCard`.
Line 219: replace `{conversationCard(r)}` with `{generalDiscussionCard(r)}`.

- [ ] **Step 6: Verify**

Run:
```
docker exec -e NODE_OPTIONS= spire-ui-dev npx tsc --noEmit
docker exec -e NODE_OPTIONS= spire-ui-dev npx vitest run
```
Expected: `tsc` exit 0; all suites green incl. `render.conversation.test.tsx`.

- [ ] **Step 7: Commit**
```bash
git add spire-ui/src/api.ts spire-ui/src/render.tsx spire-ui/src/components/ReviewDetail.tsx \
        spire-ui/src/render.conversation.test.tsx
git commit -m "Split conversation into General discussion (non-finding turns)"
```

---

### Task 5: Frontend — nest a finding's conversation under it, collapsible + badge

A finding with a conversation shows a collapsible panel of its exchanges and a `💬 N` badge.

**Files:**
- Modify: `spire-ui/src/render.tsx` (`findingsCard`)
- Modify: `spire-ui/src/index.css` (finding-conversation styles)
- Test: extend `spire-ui/src/render.conversation.test.tsx`

**Interfaces:**
- Consumes: `Finding.threadRef` + `ReviewEvent.threadRef` (Task 3/4), `conversationExchangesBody` (Task 4).
- Produces: nested per-finding conversation UI (terminal — no downstream consumer).

- [ ] **Step 1: Write the failing test**

Append to `render.conversation.test.tsx` (`renderToStaticMarkup`, `ev`, `ReviewEvent`, `ReviewDetail` are already imported from the Task 4 block — add only the `findingsCard` / `Finding` imports):
```tsx
import { findingsCard } from './render';
import type { Finding } from './api';

function detailWith(findingsList: Finding[], events: ReviewEvent[]): ReviewDetail {
  return { status: 'completed', findings: findingsList.length, findingsList, events } as unknown as ReviewDetail;
}

describe('findingsCard nested conversation', () => {
  it('nests a finding thread and shows a turn-count badge', () => {
    const finding = { sev: 'critical', loc: 'src/App.java:9', msg: 'no compile', threadRef: 'c1' } as Finding;
    const html = renderToStaticMarkup(<>{findingsCard(detailWith([finding], [
      { ...ev('AuthorReplied', '@a: why?', 'finding'), threadRef: 'c1' },
      { ...ev('FollowUpGenerated', 'Because …', 'finding'), threadRef: 'c1' },
    ]))}</>);
    expect(html).toContain('💬 2');
    expect(html).toContain('why?');
  });

  it('shows no panel for a finding without a thread', () => {
    const finding = { sev: 'nit', loc: 'src/App.java:1', msg: 'x' } as Finding;
    const html = renderToStaticMarkup(<>{findingsCard(detailWith([finding], []))}</>);
    expect(html).not.toContain('💬');
  });
});
```

- [ ] **Step 2: Run it, verify it fails**

Run: `docker exec -e NODE_OPTIONS= spire-ui-dev npx vitest run src/render.conversation.test.tsx`
Expected: FAIL — no `💬` badge / panel rendered.

- [ ] **Step 3: Render the nested panel in `findingsCard`**

In the findings `.map`, after the `<div className="msg">{f.msg}</div>`, add the collapsible panel. Replace the finding block:
```tsx
{r.findingsList.map((f: Finding, i: number) => {
  const turns = f.threadRef
    ? r.events.filter(
        (e: ReviewEvent) =>
          e.threadRef === f.threadRef && (e.type === 'AuthorReplied' || e.type === 'FollowUpGenerated'),
      )
    : [];
  return (
    <div key={i} className={`finding ${f.sev}`}>
      <div className="stripe"></div>
      <div className="fbody">
        <div className="frow">
          <span className="sev">{f.sev}</span>
          <span className="loc">{f.loc}</span>
        </div>
        <div className="msg">{f.msg}</div>
        {turns.length > 0 && (
          <details className="finding-convo">
            <summary>💬 {turns.length}</summary>
            {conversationExchangesBody(turns)}
          </details>
        )}
      </div>
    </div>
  );
})}
```
The `💬 {turns.length}` summary matches the test's `💬 2` assertion and needs no icon import.

- [ ] **Step 4: Add the CSS**

In `index.css`, near the `.finding` rules, add (reuse existing `--` tokens):
```css
  .finding-convo { margin-top: 10px; }
  .finding-convo > summary {
    cursor: pointer; list-style: none; user-select: none;
    font-family: var(--font-mono); font-size: 11px; color: var(--text-3);
    padding: 3px 0; width: fit-content;
  }
  .finding-convo > summary:hover { color: var(--text-2); }
  .finding-convo[open] > summary { margin-bottom: 6px; }
  .finding-convo .convo { padding-left: 4px; }
```

- [ ] **Step 5: Verify**

Run:
```
docker exec -e NODE_OPTIONS= spire-ui-dev npx tsc --noEmit
docker exec -e NODE_OPTIONS= spire-ui-dev npx vitest run
```
Expected: `tsc` exit 0; all green.

- [ ] **Step 6: Commit**
```bash
git add spire-ui/src/render.tsx spire-ui/src/index.css spire-ui/src/render.conversation.test.tsx
git commit -m "Nest a finding's conversation under it with a reply count"
```

---

## Final verification (after all tasks)

- [ ] **Backend compile (throwaway JDK-25 container):**
  `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd)":/workspace -w /workspace -v spire-gradle-test:/root/.gradle eclipse-temurin:25-jdk ./gradlew :spire-orchestrator:compileJava :spire-orchestrator:compileTestJava --offline -Dquarkus.devservices.enabled=false -Dquarkus.datasource.devservices.enabled=false` → BUILD SUCCESSFUL.
- [ ] **Backend tests (Docker host):** `./gradlew :spire-orchestrator:test` → all green (V17/V18 applied by Flyway).
- [ ] **UI:** `docker exec -e NODE_OPTIONS= spire-ui-dev npx tsc --noEmit` (exit 0) + `docker exec -e NODE_OPTIONS= spire-ui-dev npx vitest run` (all green).
- [ ] **Manual (orchestrator + ui running):** open a review with a finding-thread conversation (e.g. a fresh PR: register → review → reply to a bot inline comment → get an answer). The finding shows `💬 N` and expands to the exchange; a reply to the summary comment appears under **General discussion**; a review with no non-finding threads shows no General discussion card. Old reviews (PR #6, null `thread_ref`) render all turns under General discussion, nothing crashes.

## Notes for the implementer

- **Do not** touch `spire-contract` — the events already carry everything (`PostedInline.path/line`, `*.threadRef`).
- **Do not** flip `is_ours` for the summary thread — that would change what the bot answers (this is display-only).
- The **`PostedInline(id, anchorKey, 0)` reconstruction branch** (worker, partial-retry redelivery) yields a `review_thread` row that won't match a finding `loc`; its thread correctly falls to General discussion. Leaving that branch as-is is intentional for this plan.
- Keep the shared exchange helpers (`toConversationExchanges`, `conversationTurnRow`, `formatEventTime`) as the single rendering path for both the nested panel and General discussion — do not fork them.
