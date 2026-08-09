# Review Archival Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deleting a review archives it instead of destroying it, so recorded LLM usage and cost are never lost.

**Architecture:** A nullable `archived_at` on `review_status` marks a review archived; nothing is deleted. The PR is *retired* — four integration events plus the re-run and manual-register endpoints refuse to act on an archived review, and inbound conversational events get a one-time "this review is archived" notice modelled on the existing turn-cap notice. `llm_charge.archived_at` exists but is written only by a future purge, so an archived review keeps its own cost visible while a purged review's orphans stay out of the PR that later inherits its id.

**Tech Stack:** Java 25, Quarkus 3.38.1, Gradle Kotlin DSL, Postgres + Flyway, Kafka (Redpanda), React 19 + Vite + vitest.

**Spec:** `docs/superpowers/specs/2026-08-09-review-archival-design.md` — read it before starting.

**Branch:** `feat/review-archival`, stacked on the unmerged `feat/llm-cost-accounting`. Do not rebase.

## Global Constraints

- Money in millicents. Never `double`/`float`/`BigDecimal` for money.
- **No synthetic data that could pass for real.** Fixtures use `TEST-`/`CANARY-` prefixes.
- `spire-contract` is framework-free: JDK plus `jackson-annotations` only.
- 4-space Java indent, 2-space TS. Explicit types over `var`. `interface` over `type` for TS object shapes.
- Max 3 Java method params, methods ≤30 lines, classes ≤300, React components ≤250 lines / ≤8 props / ≤8 `useState`.
- **Never mention AI/agentic authoring in commit messages.** Subject imperative ≤72 chars; **wrap body lines at 72**.
- lucide-react icons only, never emoji.
- Verify with `./gradlew testFast` and `./gradlew :spire-orchestrator:test`. **Never `./gradlew assemble`** — it fails here for a known reason (JDK 21 `JAVA_HOME` vs toolchain 25); CI covers it.

## Verified API facts — use these exact names

A review of the first draft found five invented signatures. These are the real ones:

| Use | Not |
|---|---|
| `CostSummary.knownCostMillicents()` | `totalMillicents()` |
| `ReviewSummary.pr()` | `prId()` |
| `loadDetail(String workspace, String slug, long pr)` | `loadDetail(reviewId)` |
| `updateStatus(id, "completed", STAGE_DONE)` | `markCompleted(...)` |
| `new ObjectMapper()` in contract wire tests | `WireMapper.create()` |

`ReviewProjection.update(...)` returns **void** (`:1702`), so any statement needing an affected-row count must use the `Connection`/`PreparedStatement` form. Verified present and usable as assumed: `broadcast`, `broadcastRemoval`, `scheduleRetry` (4-arg), `setAnswering`, `claimDueRetries`, `LOG`, `ReviewDetail.status()/prState()/answering()`, `ReviewSummary.model()/unpricedCalls()`.

## Two architecture decisions the first draft left open

**Archive broadcasts a removal, not a row update.** An archived review leaves the live list, and archived reviews are *frozen*, so they need no live updates. `broadcastRemoval` therefore stays in use, `ReviewsSocket` keeps feeding live rows only, and the Show-archived view is a plain REST fetch. This avoids pushing archived rows through a socket whose `onOpen` snapshot **replaces** the client list (`useLiveReviews.ts:85-88`) — which would otherwise drop archived rows on every reconnect, and ADR-022's 5-minute cookie expiry makes reconnects routine.

**The notice fires on three events, not four.** All four gate, but `PullRequestClosed` does **not** notify: it is not a human asking a question, and the notice fires once *ever*, so spending it on a close would leave the person who later asks a real question with silence.

## File Structure

| File | Responsibility |
|---|---|
| `spire-orchestrator/src/main/resources/db/migration/V32__review_archival.sql` | **Create.** Both columns, partial index. |
| `.../orchestrator/readmodel/ArchiveOutcome.java` | **Create.** Four-valued archive result. |
| `.../orchestrator/readmodel/ReviewProjection.java` | **Modify.** Archive/unarchive/`archived`; ledger filters; `archivedAt` on both view records. |
| `.../orchestrator/readmodel/ReviewSummary.java`, `ReviewDetail.java` | **Modify.** Add `archivedAt`. |
| `.../orchestrator/web/ReviewsResource.java`, `ReviewsSocket.java` | **Modify.** Archive/unarchive endpoints, `includeArchived`. |
| `.../orchestrator/attention/AttentionQueries.java`, `CostAttentionRow.java` | **Modify.** Exclude archived/purged. |
| `.../orchestrator/pipeline/IntegrationSaga.java`, `ReviewRerunService.java`, `ResultSaga.java` | **Modify.** Gates and the result event. |
| `.../orchestrator/ingress/ManualRegisterResource.java` | **Modify.** 409 on archived. |
| `spire-contract/.../command/ActionCommand.java`, `event/IntegrationEvent.java`, `event/EventKeys.java` | **Modify.** `NotifyArchived`, `ArchivedNotified`, `ARCHIVED_SLOT`. |
| `spire-review-worker/.../pipeline/FollowUpWorker.java`, `CommandDispatcher.java` | **Modify.** The notice handler. |
| `spire-ui/src/api.ts`, `useLiveReviews.ts`, `App.tsx`, `components/ReviewsList.tsx`, `components/ReviewDetail.tsx` | **Modify.** Show-archived, Archive/Unarchive. |
| `.../orchestrator/readmodel/ReviewFixtures.java` | **Create (Task 2).** Shared test fixtures — five later tasks depend on them. |

---

### Task 1: V32 migration

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V32__review_archival.sql`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewArchivalSchemaIT.java`

**Interfaces:** Produces `review_status.archived_at`, `llm_charge.archived_at`, both `TIMESTAMPTZ NULL`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.orchestrator.readmodel;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** V32 adds the archival marker to the review row and to the ledger. */
@QuarkusTest
class ReviewArchivalSchemaIT {

    @Inject
    DataSource dataSource;

    private boolean hasColumn(String table, String column) throws SQLException {
        String sql = """
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'orchestrator' AND table_name = ? AND column_name = ?
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    void reviewStatusAndTheLedgerBothCarryAnArchivalMarker() throws SQLException {
        assertTrue(hasColumn("review_status", "archived_at"), "review_status.archived_at");
        assertTrue(hasColumn("llm_charge", "archived_at"), "llm_charge.archived_at");
    }
}
```

- [ ] **Step 2: Run it, confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests "*ReviewArchivalSchemaIT*"`
Expected: FAIL — `review_status.archived_at ==> expected: <true> but was: <false>`

- [ ] **Step 3: Write the migration**

```sql
-- Deleting a review used to destroy its charge ledger, so real paid usage vanished with a row
-- removed for being clutter. Archival replaces that: nothing is deleted, NULL archived_at = live.
--
-- review_status.archived_at is written by archiving. llm_charge.archived_at is NOT: it is written
-- only by a future purge, in the same transaction that hard-deletes the review row. Stamping the
-- ledger at archive time would hide an archived review's cost from its OWN detail page, because the
-- per-review cost reads key on review_id alone and are the same reads that serve that page.

ALTER TABLE review_status ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE llm_charge    ADD COLUMN archived_at TIMESTAMPTZ;

-- The reviews list reads live rows ordered by recency; keep that path off the archived rows.
CREATE INDEX review_status_live_updated
    ON review_status (updated_at DESC) WHERE archived_at IS NULL;
```

- [ ] **Step 4: Run it, confirm it passes**

Run: `./gradlew :spire-orchestrator:test --tests "*ReviewArchivalSchemaIT*"`

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src/main/resources/db/migration/V32__review_archival.sql \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewArchivalSchemaIT.java
git commit -m "Add the archival marker to the review row and the ledger"
```

---

### Task 2: Archive, unarchive, and the REST surface

**Files:**
- Create: `.../orchestrator/readmodel/ArchiveOutcome.java`
- Create: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewFixtures.java`
- Modify: `.../readmodel/ReviewProjection.java:724-774` (remove `deleteReview`), `:945` (`listSummaries`), the `ReviewSummary`/`ReviewDetail` mappers
- Modify: `.../readmodel/ReviewSummary.java`, `.../readmodel/ReviewDetail.java` (add `archivedAt`)
- Modify: `.../web/ReviewsResource.java:54` (list), `:182` (replace `delete`), `.../web/ReviewsSocket.java:26`
- Modify: `spire-orchestrator/src/test/.../ReviewProjectionTest.java:191,197,227`, `LlmChargeProjectionIT.java:177`
- Test: `spire-orchestrator/src/test/.../readmodel/ReviewArchivalTest.java`, `.../web/ReviewArchiveResourceTest.java`

**Interfaces:**
- Consumes: `archived_at` (Task 1).
- Produces: `ArchiveOutcome archiveReview(String, String, long)`, `boolean unarchiveReview(String, String, long)`, `boolean archived(String reviewId)`, `List<ReviewSummary> listSummaries(boolean includeArchived)`, `archivedAt` on both view records, and `ReviewFixtures`.

**Why the endpoint change is in this task, not its own:** removing `deleteReview` breaks `ReviewsResource.delete` at `:182` immediately. Split across two tasks, Task 2 could not compile, so it could not run its own tests. They are one deliverable.

- [ ] **Step 1: Write the shared fixtures**

Five later tasks need these and none exist today. `ReviewProjectionTest:180-200` is the old hard-delete test, **not** a fixture source. Build on the real primitives: `registerHeader`, `updateStatus`, and `recordCharges(ChargeCall)` — read `LlmChargeProjectionIT` for the charge-seeding pattern and copy it.

```java
package dev.codespire.orchestrator.readmodel;

/**
 * Shared review fixtures. Written once here because five tasks need them; every value is
 * obviously-synthetic (TEST- prefixes, round token counts) so a fixture can never be mistaken for a
 * real review or a real vendor price.
 */
public final class ReviewFixtures {

    public static final String WS = "TEST-WS";
    public static final String REPO = "TEST-REPO";

    private static final AtomicLong NEXT_PR = new AtomicLong(9_000_000L);

    private ReviewFixtures() {
    }

    /** A PR number no other test uses — the module shares one database across test classes. */
    public static long newPr() {
        return NEXT_PR.incrementAndGet();
    }

    public static String reviewIdFor(long pr) { /* ReviewIds.reviewId(new RepoRef(WS, REPO), pr) */ }

    public static void seedCompletedReviewWithCharges(ReviewProjection p, long pr) { /* … */ }

    public static void seedCompletedReviewWithoutCharges(ReviewProjection p, long pr) { /* … */ }

    public static void seedReviewingReview(ReviewProjection p, long pr) { /* … */ }

    public static void seedFailedReview(ReviewProjection p, long pr) { /* … */ }
}
```

Fill each body using the real projection methods. `seedCompletedReviewWithCharges` must register the header, set status via `updateStatus(id, "completed", STAGE_DONE)`, and record at least two charge lines through `recordCharges` so cost assertions have something to sum.

- [ ] **Step 2: Write the failing tests**

`ReviewArchivalTest`:

```java
@Test
void archivingKeepsEveryChargeRow() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
    String id = ReviewFixtures.reviewIdFor(pr);
    long before = projection.costOf(id).knownCostMillicents();
    assertTrue(before > 0, "the fixture must record real spend or this test proves nothing");

    assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr));

    assertEquals(before, projection.costOf(id).knownCostMillicents(),
            "archiving must not destroy recorded spend");
}

@Test
void anArchivedReviewStillShowsItsOwnCostModelAndLines() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
    String id = ReviewFixtures.reviewIdFor(pr);
    projection.archiveReview(WS, REPO, pr);

    assertTrue(projection.costOf(id).knownCostMillicents() > 0);
    assertFalse(projection.chargeLines(id).isEmpty(), "its charge lines are still readable");
    assertNotNull(projection.costOf(id).lastModel(), "and so is the model that ran it");
}

@Test
void archivingPreservesStatusAndPrState() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
    projection.archiveReview(WS, REPO, pr);

    ReviewDetail detail = projection.loadDetail(WS, REPO, pr).orElseThrow();
    assertEquals("completed", detail.status());
    assertEquals("OPEN", detail.prState());
    assertNotNull(detail.archivedAt(), "and it knows it is archived");
}

@Test
void archiveDistinguishesAllFourOutcomes() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
    assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr));
    assertEquals(ArchiveOutcome.ALREADY_ARCHIVED, projection.archiveReview(WS, REPO, pr));
    assertEquals(ArchiveOutcome.NOT_FOUND, projection.archiveReview(WS, REPO, ReviewFixtures.newPr()));

    long running = ReviewFixtures.newPr();
    ReviewFixtures.seedReviewingReview(projection, running);
    assertEquals(ArchiveOutcome.STILL_RUNNING, projection.archiveReview(WS, REPO, running));
}

@Test
void archivingClearsTheRetryScheduleAndTheAnsweringFlag() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
    String id = ReviewFixtures.reviewIdFor(pr);
    projection.scheduleRetry(id, 2, "TEST retry", Instant.now().plusSeconds(60));
    projection.setAnswering(id, true);
    projection.updateStatus(id, "completed", ReviewProjection.STAGE_DONE);

    assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr));

    // NOT isEmpty(): this module shares one database, and a sweep would claim other tests' due rows.
    assertFalse(projection.claimDueRetries(Instant.now().plusSeconds(120)).contains(id));
    assertFalse(projection.loadDetail(WS, REPO, pr).orElseThrow().answering());
}

@Test
void unarchiveRestoresTheReviewToTheLiveList() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
    projection.archiveReview(WS, REPO, pr);

    assertTrue(projection.unarchiveReview(WS, REPO, pr));

    assertTrue(projection.listSummaries(false).stream().anyMatch(s -> s.pr() == pr));
}
```

`ReviewArchiveResourceTest` (`@QuarkusTest`, `@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})`):

```java
@Test
void archivingAnUnknownReviewIs404() {
    given().when().post("/api/reviews/TEST-WS/TEST-REPO/9999999/archive").then().statusCode(404);
}

@Test
void archivingTwiceIs409WithAnActionableMessage() {
    long pr = seedCompleted();
    given().when().post(path(pr, "archive")).then().statusCode(204);
    given().when().post(path(pr, "archive")).then().statusCode(409)
            .body(containsString("already archived"));
}

@Test
void archivingARunningReviewIs409AndSaysToWait() {
    long pr = seedReviewing();
    given().when().post(path(pr, "archive")).then().statusCode(409)
            .body(containsString("still running"));
}

@Test
void archivedReviewsAreHiddenByDefaultAndVisibleOnRequest() {
    long pr = seedCompleted();
    given().when().post(path(pr, "archive")).then().statusCode(204);

    when().get("/api/reviews").then().statusCode(200)
            .body("findAll { it.pr == " + pr + " }", hasSize(0));
    when().get("/api/reviews?includeArchived=true").then().statusCode(200)
            .body("findAll { it.pr == " + pr + " }", hasSize(1));
}

@Test
void unarchiveRestoresTheReview() {
    long pr = seedCompleted();
    given().when().post(path(pr, "archive")).then().statusCode(204);
    given().when().post(path(pr, "unarchive")).then().statusCode(204);
    when().get("/api/reviews").then().body("findAll { it.pr == " + pr + " }", hasSize(1));
}
```

Note the JSON path is `it.pr`, not `it.prId` — `ReviewSummary`'s component is `pr`.

- [ ] **Step 3: Run and confirm they fail**

Run: `./gradlew :spire-orchestrator:test --tests "*ReviewArchival*" --tests "*ReviewArchiveResource*"`
Expected: FAIL — `cannot find symbol: method archiveReview`.

- [ ] **Step 4: Create `ArchiveOutcome`**

```java
package dev.codespire.orchestrator.readmodel;

/**
 * Why an archive attempt did or did not happen. A boolean cannot carry this: the UPDATE's WHERE
 * matches zero rows for all three failure cases, so the caller could not tell "no such review" from
 * "already archived" from "still running" — and each needs a different answer to the operator.
 */
public enum ArchiveOutcome {
    ARCHIVED,
    ALREADY_ARCHIVED,
    STILL_RUNNING,
    NOT_FOUND
}
```

- [ ] **Step 5: Add `archivedAt` to both view records**

Add `Instant archivedAt` as a component of `ReviewSummary` and `ReviewDetail`, populate it from the new column in every mapper that builds them (including `broadcast`'s per-row lookup), and add it to the TS types in Task 9. Fix all construction sites the compiler flags.

- [ ] **Step 6: Replace `deleteReview`**

Remove `deleteReview` — its real span is **`:724-774`**, stopping *before* the shared `deleteBy` helper at `:776`, which the re-run path still uses. Add:

```java
    /**
     * Archive a review: it leaves the live list but nothing is destroyed — not the timeline, not the
     * event stream, not the worker's claims or context blob, and above all not the charge ledger.
     * Deleting the ledger was how real paid usage disappeared with a row removed for being clutter.
     *
     * <p>Clears {@code retry_at} because {@link #claimDueRetries} sweeps every five seconds and would
     * otherwise resurrect the review minutes later, and {@code answering} so an archived review does
     * not display a responding pill forever.
     *
     * <p>Refuses while running: {@code ResultSaga.ifCurrentRun} guards on commit alone, so an in-flight
     * worker's results would still write status, findings and charges to a row that is supposed to be
     * frozen — and those late charges would carry a NULL archived_at into a future purge, becoming
     * exactly the orphan the column exists to prevent.
     */
    public ArchiveOutcome archiveReview(String workspace, String slug, long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        String sql = """
                UPDATE review_status
                   SET archived_at = now(), retry_at = NULL, answering = false, updated_at = now()
                 WHERE review_id = ? AND archived_at IS NULL AND lower(status) <> 'reviewing'
                """;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, reviewId);
                    updated = ps.executeUpdate();
                }
                ArchiveOutcome outcome = updated > 0
                        ? ArchiveOutcome.ARCHIVED : whyNotArchived(c, reviewId);
                c.commit();
                if (outcome == ArchiveOutcome.ARCHIVED) {
                    // A removal, not an update: the row leaves the LIVE list, and an archived review is
                    // frozen, so it needs no further live updates. Show-archived is a REST fetch.
                    broadcastRemoval(reviewId);
                }
                return outcome;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to archive " + reviewId, e);
        }
    }

    /** Which of the three non-archiving cases applies, read inside the archiving transaction. */
    private ArchiveOutcome whyNotArchived(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT archived_at, status FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ArchiveOutcome.NOT_FOUND;
                }
                return rs.getTimestamp("archived_at") != null
                        ? ArchiveOutcome.ALREADY_ARCHIVED
                        : ArchiveOutcome.STILL_RUNNING;
            }
        }
    }

    /** Undo an archive. One statement, because archiving stamped nothing else. */
    public boolean unarchiveReview(String workspace, String slug, long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE review_status SET archived_at = NULL, updated_at = now()
                      WHERE review_id = ? AND archived_at IS NOT NULL
                     """)) {
            ps.setString(1, reviewId);
            boolean restored = ps.executeUpdate() > 0;
            if (restored) {
                broadcast(reviewId);
            }
            return restored;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to unarchive " + reviewId, e);
        }
    }

    /** Whether this review has been archived — the gate every resurrection path consults. */
    public boolean archived(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM review_status WHERE review_id = ? AND archived_at IS NOT NULL")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            // Fail OPEN: failing closed would silently retire a live review on a transient read fault
            // and stop every reply on it. Proceed as the code did before archival existed.
            LOG.errorf(e, "Could not read archival state for %s — treating as live", reviewId);
            return false;
        }
    }

    /** Release the archived-notice claim so a later re-archive notifies again. */
    public void releaseArchivedNoticeClaim(String reviewId) {
        try (Connection c = dataSource.getConnection()) {
            if (!tableExists(c, "worker.comment_idempotency")) {
                return;
            }
            // Targeted: clearWorkerIdempotency would also drop the cached LLM result, so the next
            // event would pay for the model again.
            deleteBy(c, """
                    DELETE FROM worker.comment_idempotency
                     WHERE review_id = ? AND commit = ? AND anchor_key = ?
                    """, reviewId, ArchivedNotice.SLOT, ArchivedNotice.KEY);
        } catch (SQLException e) {
            LOG.errorf(e, "Could not release the archived-notice claim for %s", reviewId);
        }
    }
```

`deleteBy`'s existing signature takes a single id — extend it or inline a `PreparedStatement` with three binds, whichever matches the file's style.

- [ ] **Step 7: Add `includeArchived` to `listSummaries`**

Change `listSummaries()` (`:945`) to `listSummaries(boolean includeArchived)` and insert before the `ORDER BY`:

```sql
                  FROM review_status rs
                 WHERE (? OR rs.archived_at IS NULL)
                 ORDER BY rs.updated_at DESC
```

Two main callers: `ReviewsResource:54` passes the new query param; **`ReviewsSocket:26` passes `false`** — the socket carries live rows only, per the architecture decision above. Thirteen test call sites also need `false`.

- [ ] **Step 8: Replace the REST endpoint**

Delete `ReviewsResource.delete` (`:182`) and add:

```java
    /**
     * Archive a review. Not a DELETE: nothing is destroyed, and a DELETE verb that destroys nothing
     * misdescribes the operation to every future reader of this API.
     */
    @POST
    @RolesAllowed("spire-admin")
    @Path("/{workspace}/{slug}/{pr}/archive")
    public Response archive(@PathParam("workspace") String workspace,
                            @PathParam("slug") String slug,
                            @PathParam("pr") long pr) {
        return switch (projection.archiveReview(workspace, slug, pr)) {
            case ARCHIVED -> Response.noContent().build();
            case ALREADY_ARCHIVED -> throw conflict("This review is already archived.");
            case STILL_RUNNING -> throw conflict("This review is still running. "
                    + "Wait for it to finish, or cancel it, then archive.");
            case NOT_FOUND -> throw new NotFoundException(
                    "No review for " + workspace + "/" + slug + "#" + pr);
        };
    }

    @POST
    @RolesAllowed("spire-admin")
    @Path("/{workspace}/{slug}/{pr}/unarchive")
    public Response unarchive(@PathParam("workspace") String workspace,
                              @PathParam("slug") String slug,
                              @PathParam("pr") long pr) {
        if (!projection.unarchiveReview(workspace, slug, pr)) {
            throw new NotFoundException("No archived review for " + workspace + "/" + slug + "#" + pr);
        }
        // Re-arm the one-time notice, so a later re-archive can announce itself again.
        projection.releaseArchivedNoticeClaim(
                ReviewIds.reviewId(new RepoRef(workspace, slug), pr));
        return Response.noContent().build();
    }

    /**
     * A ClientErrorException built from a bare string sets the EXCEPTION's message, not the response
     * entity, so the sentence explaining what to do never reaches the client. Build the response.
     */
    private static ClientErrorException conflict(String message) {
        return new ClientErrorException(
                Response.status(Response.Status.CONFLICT).entity(message).build());
    }
```

`case X -> throw conflict(...)` is legal in a Java 25 switch expression — this compiles as written. Add the `ClientErrorException` import (absent from this file); `NotFoundException` is already imported.

Add `@QueryParam("includeArchived") @DefaultValue("false") boolean includeArchived` to the list method at `:54`.

- [ ] **Step 9: Fix the existing tests that call `deleteReview`**

`ReviewProjectionTest:191,197,227` and `LlmChargeProjectionIT:177` all call it. **`LlmChargeProjectionIT`'s assertion must be inverted** — it currently asserts the ledger *is* cleared on delete, which is precisely the behaviour being reversed. Rewrite it to assert the charges survive, and rename it to say so.

- [ ] **Step 10: Run the whole module**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add spire-orchestrator/src
git commit -F- <<'EOF'
Archive a review instead of destroying it

Deleting a review removed its charge ledger, so real paid usage
disappeared with a row removed for being clutter. Archiving sets a
marker and destroys nothing.

Clears retry_at, because the five-second retry sweep would otherwise
resurrect the review minutes later, and answering, so an archived
review does not show a responding pill forever. Refuses while the
review is running, since an in-flight result would write to a row the
design promises is frozen and leave a charge no future purge stamps.

Returns an enum rather than a boolean: the UPDATE matches zero rows
for all three failure cases, so a boolean could not tell an operator
which one they hit.

Archiving broadcasts a removal rather than an update. The row leaves
the live list, and an archived review is frozen, so the socket keeps
carrying live rows only and the archived view is a plain fetch.

The ledger test that asserted charges were cleared on delete now
asserts they survive -- it pinned the behaviour being reversed.
EOF
```

---

### Task 3: Keep a purged review's charges off the PR that reuses its id

**Files:**
- Modify: `.../readmodel/ReviewProjection.java` lines `954`, `957` (**inner subquery only**), `959`, `961`, `1085`, `1195`, `1197`, `1410`, `1422`, `1434`
- Modify: `.../attention/CostAttentionRow.java` — the `UNPRICED` and `UNRECONCILED` queries
- Test: `spire-orchestrator/src/test/.../readmodel/PurgedChargeIsolationIT.java`

**Interfaces:** Consumes `llm_charge.archived_at` (Task 1), `ReviewFixtures` (Task 2).

**Why now, before any purge exists:** these filters are no-ops today, because only a purge stamps rows. They land now because the day the purge is written is the day a re-registered PR starts reporting a dead review's money as its own.

- [ ] **Step 1: Write the failing test**

```java
/**
 * A purge hard-deletes the review row and stamps its charges. The PR is then registrable again, and
 * review_id is stable per PR — so the new review reads the old rows unless every ledger query filters.
 * This is the only test that exercises what llm_charge.archived_at is for.
 */
@QuarkusTest
class PurgedChargeIsolationIT {

    @Test
    void aReRegisteredPrInheritsNothingFromAPurgedReview() throws SQLException {
        long pr = ReviewFixtures.newPr();
        String id = ReviewFixtures.reviewIdFor(pr);
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        assertTrue(projection.costOf(id).knownCostMillicents() > 0, "fixture recorded spend");

        stampAllCharges(id);             // UPDATE llm_charge SET archived_at = now() WHERE review_id = ?
        deleteReviewRowDirectly(id);     // DELETE FROM review_status WHERE review_id = ?

        ReviewFixtures.seedCompletedReviewWithoutCharges(projection, pr);

        assertEquals(0L, projection.costOf(id).knownCostMillicents(),
                "a re-registered PR must not inherit a purged review's spend");
        assertTrue(projection.chargeLines(id).isEmpty(), "nor its charge lines");
        ReviewSummary row = projection.listSummaries(false).stream()
                .filter(s -> s.pr() == pr).findFirst().orElseThrow();
        assertEquals("", row.model(), "nor its model badge");
        assertEquals("", row.llmType(), "nor the vendor badge derived from that model");
        assertEquals(0, row.unpricedCalls(), "nor its unpriced-call count");
    }
}
```

The `llmType` assertion matters: line `957`'s nested subquery is the one easiest to miss, and without this line the test cannot catch it. If `ReviewSummary`'s vendor component has a different name, use the real one.

- [ ] **Step 2: Run and confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests "*PurgedChargeIsolationIT*"`
Expected: FAIL on `knownCostMillicents`.

- [ ] **Step 3: Add the filter to all ten ledger reads**

Append `AND archived_at IS NULL` (or `AND c.archived_at IS NULL` where aliased) at every listed line. Two traps:

- **Line 957: filter the INNER subquery only.** The outer query selects from `llm_model`, which has no `archived_at` — adding it there is a SQL error, not a stricter filter.
- **`costOf` has two separate references** (`1195`, `1197`); both need it.

Leave the `INSERT` at `1124` alone, and leave `commitOf` and `loadDetail`'s row fetch unfiltered — they read `review_status`, and an archived review must still answer for itself.

- [ ] **Step 4: Filter both cost attention rows**

In `CostAttentionRow.java`, add `AND archived_at IS NULL` to the `UNPRICED` and `UNRECONCILED` count queries.

- [ ] **Step 5: Run the module**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS. If `LlmChargeProjectionIT` or `AttentionQueriesCostTest` fail, a filter landed on a query serving an archived review's own page — re-read Step 3.

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src
git commit -F- <<'EOF'
Keep a purged review's charges off the PR that reuses its id

review_id is stable per PR, so once a purge hard-deletes a review row
the PR can be registered again and the new review reads the old rows.
Every ledger query now excludes stamped charges.

No-ops today, because only a purge stamps them and no purge exists
yet. They land now because the day that code is written is the day a
re-registered PR starts reporting a dead review's money as its own.

Deliberately not applied to point reads by review_id: an archived
review must still answer for its own cost, which is the entire reason
the ledger is retained.
EOF
```

---

### Task 4: Attention panel and retry sweep

**Files:**
- Modify: `.../attention/AttentionQueries.java:173-184` (`reviewRows`, **both** queries in the method)
- Modify: `.../readmodel/ReviewProjection.java:305-313` (`claimDueRetries`)
- Test: `spire-orchestrator/src/test/.../attention/ArchivedReviewAttentionTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void anArchivedFailedReviewStopsRaisingAttention() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedFailedReview(projection, pr);
    assertTrue(hasRowFor(pr), "a failed review raises attention while live");

    projection.archiveReview(WS, REPO, pr);

    assertFalse(hasRowFor(pr), "archiving is a fix; a permanently-lit row breaks the panel's contract");
}

@Test
void anArchivedReviewIsNotSweptBackIntoThePipeline() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
    String id = ReviewFixtures.reviewIdFor(pr);
    projection.archiveReview(WS, REPO, pr);
    // Set retry_at directly, AFTER archiving, so this tests the sweep's own filter and not just
    // archive's clearing. A test that only exercised the clearing would pass with the filter missing.
    setRetryAtDirectly(id, Instant.now().minusSeconds(1));

    assertFalse(projection.claimDueRetries(Instant.now()).contains(id));
}
```

- [ ] **Step 2: Run and confirm they fail**

Run: `./gradlew :spire-orchestrator:test --tests "*ArchivedReviewAttentionTest*"`

- [ ] **Step 3: Filter both queries**

Add `AND archived_at IS NULL` to **both** queries inside `reviewRows`, and change `claimDueRetries`'s `WHERE` to:

```sql
                 WHERE retry_at IS NOT NULL AND retry_at <= ? AND archived_at IS NULL
```

- [ ] **Step 4: Run and confirm they pass**

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src
git commit -m "Exclude archived reviews from attention and the retry sweep"
```

---

### Task 5: Refuse the two non-event resurrection paths

**Files:**
- Modify: `.../pipeline/ReviewRerunService.java:50-60`
- Modify: `.../ingress/ManualRegisterResource.java:111-121` (a local `reviewId` **already exists at `:117`** — reuse it, do not redeclare)
- Test: `spire-orchestrator/src/test/.../pipeline/ArchivedReviewGateTest.java`

**Interfaces:** Consumes `archived(String)` (Task 2).

**Why separate from Task 6:** neither path is an integration event, so neither passes through `IntegrationSaga`. A gate placed only in the saga leaves both open.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aRerunOfAnArchivedReviewIsRefusedAndLeavesItsNoticeClaimIntact() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
    String id = ReviewFixtures.reviewIdFor(pr);
    seedArchivedNoticeClaim(id);
    projection.archiveReview(WS, REPO, pr);

    assertThrows(ClientErrorException.class, () -> rerunService.rerun(WS, REPO, pr));

    assertTrue(archivedNoticeClaimExists(id),
            "the re-run must not clear the once-ever notice claim on its way to refusing");
}

@Test
void registeringAnArchivedPrIs409NotASilentSuccess() {
    long pr = ReviewFixtures.newPr();
    ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
    projection.archiveReview(WS, REPO, pr);

    given().contentType(ContentType.JSON)
            .body("{\"workspace\":\"TEST-WS\",\"slug\":\"TEST-REPO\",\"pr\":" + pr + "}")
            .when().post("/api/reviews/register")
            .then().statusCode(409).body(containsString("archived"));
}
```

The claim-survival assertion is the half the first draft named in its test title and never checked. The register path is **`/api/reviews/register`** — verify against `ManualRegisterResource`'s `@Path` before running.

- [ ] **Step 2: Run and confirm they fail**

- [ ] **Step 3: Gate the re-run**

In `ReviewRerunService.rerun`, immediately after the `reviewId` is computed and **before** `clearWorkerIdempotency`:

```java
        // Archived means retired. This path is REST, not an integration event, so the saga's gate never
        // sees it — and its first act below (clearWorkerIdempotency) deletes ALL claims for the review,
        // including the archived-notice claim that is supposed to fire once ever.
        if (projection.archived(reviewId)) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity("This review is archived. Unarchive it before re-running.").build());
        }
```

409, not 404 — the review exists, the request conflicts with its state, and the manual-register path answers 409 for the same condition.

- [ ] **Step 4: Gate the manual register**

Before `integration.send(event)`, reusing the existing `reviewId` local at `:117`:

```java
        // The saga would drop this event for an archived review, but silently: the caller would get a
        // 200 with a reviewId and nothing would happen. A silent non-response reads as a lost webhook,
        // which this project already had to fix once for the conversation turn cap.
        if (projection.archived(reviewId)) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity("This pull request's review is archived. Unarchive it to review again.")
                    .build());
        }
```

Inject `ReviewProjection` if absent.

- [ ] **Step 5: Run and confirm they pass**

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src
git commit -F- <<'EOF'
Refuse re-run and re-registration of an archived review

Neither path is an integration event, so neither passes through the
saga where the other gates live. The re-run endpoint additionally
clears every worker claim for the review as its first act, including
the archived-notice claim, so an ungated re-run both resurrected the
review and re-armed a notice meant to fire once.

Registering an archived PR answered 200 with a reviewId while the
saga dropped the event, so an operator saw success and nothing
happened. Both now answer 409.
EOF
```

---

### Task 6: The archived notice — contract types

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/command/ArchivedNotice.java`
- Modify: `.../command/ActionCommand.java:34`, `:199`
- Modify: `.../event/IntegrationEvent.java:44`, `:268`
- Modify: `.../event/EventKeys.java:28`
- Test: `spire-contract/src/test/java/dev/codespire/contract/ArchivedNoticeWireTest.java`

**Interfaces:** Produces `ActionCommand.NotifyArchived(String reviewId, RepoRef repo, long prId, ThreadRef threadRef, String scmCredential)`, `IntegrationEvent.ArchivedNotified(String reviewId, ThreadRef threadRef, String commentId)`, and `ArchivedNotice.SLOT` / `.KEY`.

- [ ] **Step 1: Write the failing test**

Use a plain `new ObjectMapper()` — the contract wire tests do not have a `WireMapper` factory. Read a neighbouring wire test and copy its setup exactly.

```java
@Test
void theArchivedNoticeRoundTripsOverTheWire() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ActionCommand command = new ActionCommand.NotifyArchived(
            "review::TEST-WS/TEST-REPO#1", new RepoRef("TEST-WS", "TEST-REPO"), 1L,
            new ThreadRef("TEST-THREAD"), "TEST-CREDENTIAL");

    String json = mapper.writeValueAsString(command);
    assertEquals(command, mapper.readValue(json, ActionCommand.class));
    assertTrue(json.contains("\"NotifyArchived\""), "the discriminator names the subtype");
}

@Test
void aTopLevelNoticeCarriesNoThread() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ActionCommand command = new ActionCommand.NotifyArchived(
            "review::TEST-WS/TEST-REPO#1", new RepoRef("TEST-WS", "TEST-REPO"), 1L, null,
            "TEST-CREDENTIAL");
    assertEquals(command, mapper.readValue(mapper.writeValueAsString(command), ActionCommand.class));
}

@Test
void theArchivedNotifiedEventIsKeyedByReviewId() {
    IntegrationEvent event = new IntegrationEvent.ArchivedNotified(
            "review::TEST-WS/TEST-REPO#1", new ThreadRef("TEST-THREAD"), "TEST-COMMENT");
    assertEquals("review::TEST-WS/TEST-REPO#1", EventKeys.of(event));
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `./gradlew :spire-contract:test --tests "*ArchivedNoticeWireTest*"`

- [ ] **Step 3: Add the shared slot constants**

The orchestrator releases this claim on unarchive and the worker takes it, so the two services must agree on one definition:

```java
package dev.codespire.contract.command;

/**
 * The idempotency coordinates of the archived notice. Shared because the worker TAKES this claim and
 * the orchestrator RELEASES it on unarchive; two literals in two services would drift into a notice
 * that never re-arms, with nothing failing.
 *
 * <p>The slot is a constant rather than a thread ref, which is what makes the notice fire once per
 * REVIEW instead of once per thread.
 */
public final class ArchivedNotice {

    public static final String SLOT = "archived-notice";
    public static final String KEY = "archived";

    private ArchivedNotice() {
    }
}
```

- [ ] **Step 4: Add the command and the event**

In `ActionCommand.java`, beside `NotifyTurnCap`:

```java
    /**
     * Tell a human that this review is archived and no further reviews will run for the pull request.
     *
     * <p>Carries no LLM credential — the notice is fixed text, so retiring a PR costs no tokens.
     * {@code threadRef} is null for a top-level PR comment and non-null to reply inside a thread, so
     * the notice appears where the event that triggered it arrived.
     */
    record NotifyArchived(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                          String scmCredential) implements ActionCommand {
    }
```

register it at `:34`, then in `IntegrationEvent.java`:

```java
    /**
     * The archived notice was posted. Deliberately NOT FollowUpPosted, which bumps the conversation
     * turn count — this notice consumes no turn and involves no model. {@code threadRef} is null when
     * the notice went to the top-level PR comment.
     */
    record ArchivedNotified(String reviewId, ThreadRef threadRef, String commentId)
            implements IntegrationEvent {
    }
```

register it at `:44`, and add to `EventKeys` at `:28`:

```java
            case IntegrationEvent.ArchivedNotified e -> e.reviewId();
```

`EventKeys`' switch is exhaustive with no `default`, so omitting this is a compile error rather than a runtime surprise.

- [ ] **Step 5: Run, then refresh the contract snapshot**

Run: `./gradlew :spire-contract:test`
`ContractSchemaSnapshotTest` will fail with a diff — inspect it, confirm it shows exactly the two new types, then update the golden file it names.

- [ ] **Step 6: Commit**

```bash
git add spire-contract/src
git commit -m "Add the archived-notice command and its result event"
```

---

### Task 7: The archived notice — worker handler

**Files:**
- Modify: `spire-review-worker/.../pipeline/FollowUpWorker.java` (beside `notifyTurnCap`, `:151`)
- Modify: `spire-review-worker/.../pipeline/CommandDispatcher.java:60`
- Test: `spire-review-worker/src/test/.../pipeline/ArchivedNoticeWorkerTest.java`

**Interfaces:** Consumes `NotifyArchived`, `ArchivedNotified`, `ArchivedNotice` (Task 6). No `WorkerScmClients` change is needed — `forCommand` reads the `scmCredential()` interface accessor, which the new record's component satisfies.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void theNoticePostsOnceHoweverManyEventsArrive() {
    worker.notifyArchived(notice("TEST-THREAD"));
    worker.notifyArchived(notice("TEST-THREAD"));
    assertEquals(1, comments.replies().size());
}

@Test
void theNoticeIsClaimedPerReviewNotPerThread() {
    worker.notifyArchived(notice("TEST-THREAD-A"));
    worker.notifyArchived(notice("TEST-THREAD-B"));
    assertEquals(1, comments.replies().size(), "a second thread must not produce a second notice");
}

@Test
void aNoticeWithNoThreadGoesToTheTopLevelPrComment() {
    worker.notifyArchived(notice(null));
    assertEquals(1, comments.summaries().size());
    assertTrue(comments.replies().isEmpty());
}

@Test
void theNoticeBrokersNoModelCall() {
    worker.notifyArchived(notice("TEST-THREAD"));
    assertTrue(llm.calls().isEmpty(), "retiring a PR must cost no tokens");
}

@Test
void theNoticeEmitsArchivedNotifiedNotFollowUpPosted() {
    worker.notifyArchived(notice("TEST-THREAD"));
    assertInstanceOf(IntegrationEvent.ArchivedNotified.class, results.emitted().getFirst(),
            "FollowUpPosted would bump the turn count for a notice that consumed no turn");
}
```

- [ ] **Step 2: Run and confirm they fail**

- [ ] **Step 3: Implement the handler**

```java
    /**
     * Fixed text: no model is called, so this never varies. It does not invite an @-mention — unlike
     * the turn cap, no policy overrides retirement.
     */
    private static final String ARCHIVED_TEXT =
            "This review has been archived, so no further reviews will run for this pull request.";

    /**
     * Post the one-time notice that this review is archived. The claim slot is a CONSTANT, not the
     * thread ref, which is what makes this once per REVIEW. The store's key is
     * (review_id, commit, anchor_key) and nothing depends on that middle column holding a real commit
     * — the follow-up path already puts a thread ref there.
     */
    public void notifyArchived(ActionCommand.NotifyArchived command) {
        WorkerScmClients.Clients clients = scm.forCommand(command);
        if (idempotency.claim(command.reviewId(), ArchivedNotice.SLOT, ArchivedNotice.KEY)
                instanceof CommentIdempotencyStore.Claim.AlreadyPosted) {
            // INFO, not DEBUG: the only record that an inbound event went unanswered on purpose.
            LOG.infof("Archived notice already posted for %s — staying quiet", command.reviewId());
            return;
        }
        CommentRef ref = command.threadRef() == null
                ? clients.comments().postSummary(command.repo(), command.prId(), ARCHIVED_TEXT)
                : clients.comments().replyInThread(command.repo(), command.prId(),
                        command.threadRef(), ARCHIVED_TEXT);
        idempotency.markPosted(command.reviewId(), ArchivedNotice.SLOT, ArchivedNotice.KEY,
                ref.commentId());
        LOG.infof("Posted archived notice for %s", command.reviewId());
        results.emit(new IntegrationEvent.ArchivedNotified(
                command.reviewId(), command.threadRef(), ref.commentId()));
    }
```

- [ ] **Step 4: Route the command**

In `CommandDispatcher.java` beside `:60`: `case NotifyArchived c -> followUpWorker.notifyArchived(c);` plus the import.

- [ ] **Step 5: Run the module**

Run: `./gradlew :spire-review-worker:test`

- [ ] **Step 6: Commit**

```bash
git add spire-review-worker/src
git commit -m "Post a one-time notice when a retired PR gets activity"
```

---

### Task 8: Gate the four integration events

**Files:**
- Modify: `.../pipeline/IntegrationSaga.java` — the `handle()` switch
- Modify: `.../pipeline/ResultSaga.java:271-279` (add `ArchivedNotified`)
- Test: `spire-orchestrator/src/test/.../pipeline/ArchivedEventGateTest.java`

**Interfaces:** Consumes `archived(String)` (Task 2), `NotifyArchived` (Task 6). `IntegrationSaga` already holds `reviewProviders` and `workerCredentials`, so it can broker the SCM credential exactly as `ConversationSaga` does for `NotifyTurnCap` — read that call site and mirror it.

**Three rules the first draft missed:**
1. **Fold `isBotAuthored` into the gate.** The bot's own notice echoes back as `AuthorReplied`; without this it re-enters the gate and emits a command forever (harmless but noisy).
2. **Apply the author allowlist.** The `/review` security fix gated paid work on it; a notice that answers any prober would partly reverse that. Use the same `authorAllowed` helper.
3. **`PullRequestClosed` gates but does not notify.** The notice fires once *ever*; spending it on a close leaves the human who later asks a real question with silence.

Also: if no provider resolves, **emit nothing**. A credential-less command reaches the worker's stub-sink fallback, which would consume the once-ever claim while posting nothing real.

- [ ] **Step 1: Write the failing tests**

```java
/**
 * Phrased as "unchanged", not "creates no new review row" — the latter cannot fail, because
 * review_status's primary key forbids a second row for one PR regardless of any gate.
 */
@ParameterizedTest
@MethodSource("conversationalEvents")
void aConversationalEventLeavesAnArchivedReviewUnchangedAndNotifiesOnce(IntegrationEvent event) {
    long pr = seedArchived();
    String before = snapshotOf(ReviewFixtures.reviewIdFor(pr));

    saga.on(event);

    assertEquals(before, snapshotOf(ReviewFixtures.reviewIdFor(pr)), "an archived review is frozen");
    assertEquals(1, commands.emitted().size());
    assertInstanceOf(ActionCommand.NotifyArchived.class, commands.emitted().getFirst());
}

static Stream<IntegrationEvent> conversationalEvents() {
    return Stream.of(authorReplied(), manualCommand("review"), prUpdated());
}

@Test
void closingAnArchivedPrIsGatedButSpendsNoNotice() {
    long pr = seedArchived();
    saga.on(prClosed());

    assertEquals("OPEN", projection.loadDetail(WS, REPO, pr).orElseThrow().prState(),
            "an archived review's badge is frozen at archival");
    assertTrue(commands.emitted().isEmpty(),
            "a close is not a question; the once-ever notice must stay available");
}

@Test
void theBotsOwnNoticeDoesNotRetriggerTheGate() {
    long pr = seedArchived();
    saga.on(authorRepliedBy(BOT_ACCOUNT_ID));
    assertTrue(commands.emitted().isEmpty());
}

@Test
void anUnlistedAuthorGetsNoNotice() {
    long pr = seedArchivedWithAllowlist("TEST-ALICE");
    saga.on(authorRepliedBy("TEST-MALLORY"));
    assertTrue(commands.emitted().isEmpty());
}
```

Use `@QuarkusTest` so the "row unchanged" assertion reads a real row, and swap the command emitter for a capturing fake — with container-free fakes that assertion would be vacuous.

- [ ] **Step 2: Run and confirm they fail**

- [ ] **Step 3: Gate the switch**

At the top of `handle()`, before any case runs — and for `AuthorReplied` this must precede `threads.markThreadLocation`, which otherwise writes to an archived review:

```java
        String archivedId = archivedReviewIdOf(event);
        if (archivedId != null) {
            notifyArchivedOnce(archivedId, event);
            return;
        }
```

```java
    /** The review id of an archived review this event targets, or null if it is live or unknown. */
    private String archivedReviewIdOf(IntegrationEvent event) {
        String reviewId = switch (event) {
            case PullRequestEventReceived e -> ReviewIds.reviewId(e.repo(), e.prId());
            case PullRequestClosed e -> ReviewIds.reviewId(e.repo(), e.prId());
            case ManualCommandReceived e -> reviewIdOf(e);
            case AuthorReplied e -> e.reviewId();
            default -> null;
        };
        return reviewId != null && projection.archived(reviewId) ? reviewId : null;
    }
```

`notifyArchivedOnce` records a timeline entry always, and emits `NotifyArchived` only when **all** of these hold: the event is not `PullRequestClosed`; the author is not the bot; the author passes `authorAllowed`; and a provider resolves so a real credential can be brokered.

- [ ] **Step 4: Handle the result event**

Beside `TurnCapNotified` in `ResultSaga`, add an `ArchivedNotified` case appending a timeline entry. **Null-guard `threadRef`** — the turn-cap handler dereferences it via `rootOf`, and this event's is nullable. Do not call anything that bumps the conversation turn count.

- [ ] **Step 5: Run the module**

Run: `./gradlew :spire-orchestrator:test`

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src
git commit -F- <<'EOF'
Retire an archived PR and answer its activity once

Four inbound events now stop at an archived review: a reply, a slash
command, a PR update and a PR close. The close is the one that writes
pr_state, so without it an archived review's badge would still move on
the first merge, breaking the frozen-state property archival promises.

Only the first three produce the notice. A close is not a human asking
a question, and the notice fires once ever, so spending it there would
leave the person who later asks a real question with silence.

The gate also drops the bot's own echoed notice and unlisted authors:
without the first it re-triggers itself forever, and without the second
a notice would answer probers the review path itself refuses.
EOF
```

---

### Task 9: UI — Show archived, Archive and Unarchive

**Files:**
- Modify: `spire-ui/src/api.ts:197`, `spire-ui/src/useLiveReviews.ts`, `spire-ui/src/App.tsx`
- Modify: `spire-ui/src/components/ReviewsList.tsx`, `spire-ui/src/components/ReviewDetail.tsx`
- Test: `spire-ui/src/components/ReviewsList.test.tsx` (**new `.tsx`** — the existing `.test.ts` cannot hold JSX), `spire-ui/src/components/ReviewDetail.archive.test.tsx`

**Architecture note:** `ReviewsList` is **presentational** — it receives rows as props from `useLiveReviews` via `App` and never calls `apiFetch`. The Show-archived state therefore lives where the fetch lives, not in the list.

- [ ] **Step 1: Write the failing tests**

```tsx
it('requests archived rows only when the box is checked', async () => {
  render(<App />);
  await screen.findByText(/TEST-REPO/);
  await userEvent.click(screen.getByLabelText(/show archived/i));

  expect(apiFetch).toHaveBeenLastCalledWith(
    expect.stringContaining('includeArchived=true'), undefined);
});

it('marks an archived row so it cannot be mistaken for live work', async () => {
  render(<ReviewsList reviews={[archivedRow()]} />);
  expect(screen.getByText(/archived/i)).toBeInTheDocument();
});

it('archives instead of deleting', async () => {
  render(<ReviewDetail />);
  await userEvent.click(await screen.findByLabelText(/archive review/i));
  await userEvent.click(screen.getByRole('button', { name: /confirm/i }));

  expect(apiFetch).toHaveBeenCalledWith(
    expect.stringContaining('/archive'), expect.objectContaining({ method: 'POST' }));
});

it('offers unarchive on an archived review and never both at once', async () => {
  render(<ReviewDetail />);   // fixture with archivedAt set
  expect(await screen.findByLabelText(/unarchive review/i)).toBeInTheDocument();
  expect(screen.queryByLabelText(/^archive review/i)).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run and confirm they fail**

Run: `cd spire-ui && npm test -- --run ReviewsList ReviewDetail`

- [ ] **Step 3: Replace the API client call**

In `api.ts`, replace the `DELETE` at `:197`:

```ts
export async function archiveReview(workspace: string, slug: string, pr: number): Promise<void> {
  const res = await apiFetch(
    `/api/reviews/${encodeURIComponent(workspace)}/${encodeURIComponent(slug)}/${pr}/archive`,
    { method: 'POST' },
  );
  // The 409 body carries an actionable sentence ("still running", "already archived") — surface it.
  if (!res.ok) { throw new Error(await res.text()); }
}

export async function unarchiveReview(workspace: string, slug: string, pr: number): Promise<void> {
  const res = await apiFetch(
    `/api/reviews/${encodeURIComponent(workspace)}/${encodeURIComponent(slug)}/${pr}/unarchive`,
    { method: 'POST' },
  );
  if (!res.ok) { throw new Error(await res.text()); }
}
```

Add `archivedAt: string | null` to the `ReviewSummary` and `ReviewDetail` TS interfaces, and an `includeArchived` argument to `fetchReviews`.

- [ ] **Step 4: Lift the Show-archived state to the fetch**

Add the flag to `useLiveReviews` (or to `App`, wherever `fetchReviews` is called), refetching when it changes. The WebSocket keeps carrying **live rows only** — an archived review is frozen and needs no live updates, and the socket's `onOpen` snapshot replaces the client list, which would otherwise drop archived rows on every reconnect.

- [ ] **Step 5: Add the checkbox and the archived marker**

Render the labelled checkbox beside the existing filter chips, and an **Archived** badge on any row with a non-null `archivedAt`, using a lucide-react icon.

`ReviewsList.tsx` was 244 lines against a 250 cap — if this pushes it over, extract the filter bar into its own component in the same commit.

- [ ] **Step 6: Swap the detail-page button**

Rename `confirmDelete` → `confirmArchive`, change the tooltip and `aria-label` to "Archive review", call `archiveReview`, and render **Unarchive review** instead when `archivedAt` is set. Update the confirmation copy — it no longer destroys anything, so wording about permanent deletion is now false.

`ReviewDetail.tsx` is **already 256 lines**, over the cap before this task adds to it. Extract the action buttons into their own component here rather than growing it further.

- [ ] **Step 7: Run tests and the type check**

Run: `cd spire-ui && npm test -- --run && npx tsc --noEmit`

- [ ] **Step 8: Commit**

```bash
git add spire-ui/src
git commit -m "Archive reviews from the dashboard instead of deleting them"
```

---

### Task 10: Record the decision

**Files:** `docs/DECISIONS.md`, `CLAUDE.md`, `docs/SMOKE-TEST.md`

- [ ] **Step 1: Write ADR-024**

Record: delete became archive; retaining AI usage outranks the clean-slate property it replaced; the clean slate was never complete anyway, since `review_thread` is deleted nowhere; retirement is a **spend boundary** — an author's push must not silently re-bill an operator who archived to be done — and specifically *not* what makes retention safe, which was the first draft's reasoning and was false; charges are stamped at purge rather than at archive, because stamping at archive hid an archived review's cost from its own detail page. Add a pointer from ADR-023's ledger section, whose "delete is a true clean slate" reasoning no longer describes the system.

- [ ] **Step 2: Update CLAUDE.md**

Add a status entry covering the migration, the six gated paths, the notice, and the new test counts. **Run the suites and use the real numbers** — do not estimate.

- [ ] **Step 3: Add the runbook mode**

Archive a completed review → confirm it leaves the list and reappears with Show archived → reply on the PR → confirm exactly one notice → reply again → confirm no second notice → `/review` → confirm no review starts → close the PR → confirm the badge does not move → unarchive → confirm it is live and a later archive notifies again.

- [ ] **Step 4: Full verification**

```bash
./gradlew testFast --rerun-tasks
./gradlew :spire-orchestrator:test :spire-gateway:test :spire-review-worker:test --rerun-tasks
cd spire-ui && npm test -- --run && npx tsc --noEmit
```

Use `--rerun-tasks`; a 2-second "BUILD SUCCESSFUL" is a cached pass, not a run.

- [ ] **Step 5: Commit**

```bash
git add docs/DECISIONS.md CLAUDE.md docs/SMOKE-TEST.md
git commit -m "Record archival as the replacement for hard delete"
```

---

## Ordering

Task 2 must precede 3, 4, 5, 8 and 9 (it defines `archived`, `listSummaries(boolean)`, `archivedAt` and the fixtures). Task 6 must precede 7 and 8. Tasks 3, 4 and 5 are mutually independent. Task 8 is the only one needing two predecessors (2 and 6).

## Self-review

**Spec coverage.** All 15 spec tests appear: 1→T2, 2→T2, 3→T2, 4→T3, 5→T3, 6→T7, 7→T7, 8→T8, 9→T8, 10→T5, 11→T4, 12→T2, 13→T2, 14→T2, 15→T5. The purge is correctly absent.

**Placeholders.** `ReviewFixtures`' method bodies are the one deliberate gap, with the exact primitives named — writing them requires reading `LlmChargeProjectionIT`'s seeding pattern, and inventing them here is how the first draft produced five methods that do not exist.

**Type consistency.** `ArchiveOutcome` has four values throughout. `archived(String)` is defined in T2, used in T5 and T8. `ArchivedNotice.SLOT`/`.KEY` are defined once in T6 and used in T2 (release) and T7 (claim). `NotifyArchived`'s five components and `ArchivedNotified`'s three are identical across T6, T7, T8. Every accessor now matches the verified list at the top of this document.
