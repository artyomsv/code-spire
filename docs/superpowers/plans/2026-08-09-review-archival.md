# Review Archival Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deleting a review archives it instead of destroying it, so recorded LLM usage and cost are never lost.

**Architecture:** A nullable `archived_at` on `review_status` marks a review archived; nothing is deleted. The PR is *retired* — six paths (four integration events, the re-run endpoint, the manual-register endpoint) refuse to act on an archived review, and inbound SCM events get a one-time "this review is archived" notice modelled on the existing turn-cap notice. `llm_charge.archived_at` exists but is written only by a future purge, so an archived review keeps its own cost visible while a purged review's orphans stay out of the PR that later inherits its id.

**Tech Stack:** Java 25, Quarkus 3.38.1, Gradle Kotlin DSL, Postgres + Flyway, Kafka (Redpanda), React 19 + Vite + vitest.

**Spec:** `docs/superpowers/specs/2026-08-09-review-archival-design.md` — read it before starting.

## Global Constraints

- Money in millicents (1/100,000 dollar). Never `double`/`float`/`BigDecimal` for money.
- **No synthetic data that could pass for real.** Test fixtures use `TEST-`/`CANARY-` prefixes and obviously-synthetic values. Never a real vendor's real price.
- `spire-contract` and `spire-diff` are framework-free: JDK plus `jackson-annotations` only. Build-enforced by `PureModulesAreFrameworkFreeTest`.
- 4-space Java indent, 2-space TS. Explicit types over `var`. `interface` over `type` for TS object shapes.
- Max 3 Java method params (use a parameter object beyond that). Methods ≤30 lines, classes ≤300, React components ≤250 lines / ≤8 props / ≤8 `useState`.
- **Never mention AI/agentic authoring in commit messages** — no model names, no vendor names as authorship, no "generated with". Describe what changed and why.
- Commit subject imperative, ≤72 chars. **Wrap commit body lines at 72 chars.**
- lucide-react icons only, never emoji.
- Run `./gradlew testFast` (Docker-free, ~25s) and `./gradlew :spire-orchestrator:test` as you go. Do **not** run `./gradlew assemble` — it fails in this environment for a known reason (JDK 21 `JAVA_HOME` vs toolchain 25) and CI covers it.

## File Structure

| File | Responsibility |
|---|---|
| `spire-orchestrator/src/main/resources/db/migration/V32__review_archival.sql` | **Create.** `archived_at` on both tables, partial index. |
| `.../orchestrator/readmodel/ArchiveOutcome.java` | **Create.** The four-valued result of an archive attempt. |
| `.../orchestrator/readmodel/ReviewProjection.java` | **Modify.** `deleteReview` → `archiveReview`/`unarchiveReview`; add the ledger filters. |
| `.../orchestrator/web/ReviewsResource.java` | **Modify.** `DELETE` → `POST /archive` + `POST /unarchive`; `?includeArchived`. |
| `.../orchestrator/attention/AttentionQueries.java` | **Modify.** Exclude archived from `reviewRows`. |
| `.../orchestrator/attention/CostAttentionRow.java` | **Modify.** Exclude purged charges from both queries. |
| `.../orchestrator/pipeline/IntegrationSaga.java` | **Modify.** Gate four events; emit `NotifyArchived`. |
| `.../orchestrator/pipeline/ReviewRerunService.java` | **Modify.** Refuse an archived review. |
| `.../orchestrator/ingress/ManualRegisterResource.java` | **Modify.** 409 on an archived PR. |
| `.../orchestrator/pipeline/ResultSaga.java` | **Modify.** Handle `ArchivedNotified`. |
| `spire-contract/.../command/ActionCommand.java` | **Modify.** Add `NotifyArchived`. |
| `spire-contract/.../event/IntegrationEvent.java` | **Modify.** Add `ArchivedNotified`. |
| `spire-contract/.../event/EventKeys.java` | **Modify.** Key `ArchivedNotified` by reviewId. |
| `spire-review-worker/.../pipeline/FollowUpWorker.java` | **Modify.** `notifyArchived`. |
| `spire-review-worker/.../pipeline/CommandDispatcher.java` | **Modify.** Route `NotifyArchived`. |
| `spire-ui/src/api.ts` | **Modify.** `archiveReview`/`unarchiveReview`, `includeArchived`. |
| `spire-ui/src/components/ReviewsList.tsx` | **Modify.** Show-archived checkbox, archived marker. |
| `spire-ui/src/components/ReviewDetail.tsx` | **Modify.** Archive/Unarchive buttons. |

---

### Task 1: V32 migration

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V32__review_archival.sql`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewArchivalSchemaIT.java`

**Interfaces:**
- Produces: columns `review_status.archived_at` and `llm_charge.archived_at`, both `TIMESTAMPTZ NULL`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.orchestrator.readmodel;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
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
             var ps = c.prepareStatement(sql)) {
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

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests "*ReviewArchivalSchemaIT*"`
Expected: FAIL — `review_status.archived_at ==> expected: <true> but was: <false>`

- [ ] **Step 3: Write the migration**

```sql
-- Deleting a review used to destroy its charge ledger, so real paid usage vanished with a row
-- removed for being clutter. Archival replaces that: nothing is deleted, and a NULL archived_at
-- means live.
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

- [ ] **Step 4: Run it and confirm it passes**

Run: `./gradlew :spire-orchestrator:test --tests "*ReviewArchivalSchemaIT*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src/main/resources/db/migration/V32__review_archival.sql \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewArchivalSchemaIT.java
git commit -m "Add the archival marker to the review row and the ledger"
```

---

### Task 2: `archiveReview` and `unarchiveReview`

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ArchiveOutcome.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java:732-780` (replace `deleteReview`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewArchivalTest.java`

**Interfaces:**
- Consumes: `review_status.archived_at` (Task 1).
- Produces: `ArchiveOutcome archiveReview(String workspace, String slug, long pr)`, `boolean unarchiveReview(String workspace, String slug, long pr)` on `ReviewProjection`.

- [ ] **Step 1: Write the failing tests**

Add to `ReviewArchivalTest.java`. Follow the existing `ReviewProjectionTest` setup for creating a review row (read `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewProjectionTest.java:180-200` and reuse its fixture helpers verbatim).

```java
@Test
void archivingKeepsEveryChargeRow() {
    long pr = newPr();
    seedCompletedReviewWithCharges("TEST-WS", "TEST-REPO", pr);
    long before = projection.costOf(reviewId("TEST-WS", "TEST-REPO", pr)).totalMillicents();

    assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview("TEST-WS", "TEST-REPO", pr));

    assertEquals(before, projection.costOf(reviewId("TEST-WS", "TEST-REPO", pr)).totalMillicents(),
            "archiving must not destroy recorded spend");
}

@Test
void archivingPreservesStatusAndPrState() {
    long pr = newPr();
    seedCompletedReviewWithCharges("TEST-WS", "TEST-REPO", pr);

    projection.archiveReview("TEST-WS", "TEST-REPO", pr);

    ReviewDetail detail = projection.loadDetail(reviewId("TEST-WS", "TEST-REPO", pr)).orElseThrow();
    assertEquals("completed", detail.status(), "an archived review still reports its outcome");
    assertEquals("OPEN", detail.prState());
}

@Test
void archiveDistinguishesAllFourOutcomes() {
    long pr = newPr();
    seedCompletedReviewWithCharges("TEST-WS", "TEST-REPO", pr);

    assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview("TEST-WS", "TEST-REPO", pr));
    assertEquals(ArchiveOutcome.ALREADY_ARCHIVED, projection.archiveReview("TEST-WS", "TEST-REPO", pr));
    assertEquals(ArchiveOutcome.NOT_FOUND, projection.archiveReview("TEST-WS", "TEST-REPO", 999_999L));

    long running = newPr();
    seedReviewingReview("TEST-WS", "TEST-REPO", running);
    assertEquals(ArchiveOutcome.STILL_RUNNING, projection.archiveReview("TEST-WS", "TEST-REPO", running));
}

@Test
void archivingClearsTheRetryScheduleAndTheAnsweringFlag() {
    long pr = newPr();
    seedCompletedReviewWithCharges("TEST-WS", "TEST-REPO", pr);
    projection.scheduleRetry(reviewId("TEST-WS", "TEST-REPO", pr), 2, "TEST retry",
            Instant.now().plusSeconds(60));
    projection.setAnswering(reviewId("TEST-WS", "TEST-REPO", pr), true);
    // scheduleRetry sets status back to 'reviewing'; archive must be attempted from a settled row.
    projection.markCompleted(reviewId("TEST-WS", "TEST-REPO", pr));

    assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview("TEST-WS", "TEST-REPO", pr));

    assertTrue(projection.claimDueRetries(Instant.now().plusSeconds(120)).isEmpty(),
            "an archived review must not be swept back into the pipeline");
    assertFalse(projection.loadDetail(reviewId("TEST-WS", "TEST-REPO", pr)).orElseThrow().answering(),
            "an archived review must not show a responding pill forever");
}

@Test
void unarchiveRestoresTheReviewToTheLiveList() {
    long pr = newPr();
    seedCompletedReviewWithCharges("TEST-WS", "TEST-REPO", pr);
    projection.archiveReview("TEST-WS", "TEST-REPO", pr);

    assertTrue(projection.unarchiveReview("TEST-WS", "TEST-REPO", pr));

    assertTrue(projection.listSummaries(false).stream()
                    .anyMatch(s -> s.prId() == pr),
            "an unarchived review is live again");
}
```

If `markCompleted` or `answering()` do not exist under those names, read `ReviewProjection` and `ReviewDetail` and use the real ones — do not invent them.

- [ ] **Step 2: Run and confirm they fail**

Run: `./gradlew :spire-orchestrator:test --tests "*ReviewArchivalTest*"`
Expected: FAIL — `cannot find symbol: method archiveReview`

- [ ] **Step 3: Create the outcome type**

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

- [ ] **Step 4: Replace `deleteReview` with `archiveReview` + `unarchiveReview`**

Delete the whole `deleteReview` method (`ReviewProjection.java:732-780`, including its javadoc) and add:

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
     * <p>Refuses while the review is running: {@code ResultSaga.ifCurrentRun} guards on commit alone,
     * so an in-flight worker's results would still write status, findings and charges to a row that is
     * supposed to be frozen — and those late charges would carry a NULL archived_at into a future
     * purge, becoming exactly the orphan the column exists to prevent.
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
                ArchiveOutcome outcome = updated > 0 ? ArchiveOutcome.ARCHIVED : whyNotArchived(c, reviewId);
                c.commit();
                if (outcome == ArchiveOutcome.ARCHIVED) {
                    broadcast(reviewId);
                }
                return outcome;
            } catch (SQLException e) {
                c.rollback();
                throw e;
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

    /**
     * Undo an archive. One UPDATE, because archiving stamped nothing else — the ledger is stamped only
     * by a purge. Releasing the notice claim is left to the caller (see ReviewsResource), which owns
     * the worker-schema access.
     */
    public boolean unarchiveReview(String workspace, String slug, long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        boolean restored = update("""
                UPDATE review_status SET archived_at = NULL, updated_at = now()
                 WHERE review_id = ? AND archived_at IS NOT NULL
                """, ps -> ps.setString(1, reviewId)) > 0;
        if (restored) {
            broadcast(reviewId);
        }
        return restored;
    }
```

If `update(...)` does not return an affected-row count, read its signature at `ReviewProjection.java:1644` and use the connection-based form shown in `archiveReview` instead.

Also add an `archived(String reviewId)` accessor, which Tasks 5–7 all need:

```java
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
            // Fail CLOSED is wrong here: a transient read fault would silently retire a live review and
            // stop every reply on it. Fail open and let the operation proceed, as it did before archival.
            LOG.errorf(e, "Could not read archival state for %s — treating as live", reviewId);
            return false;
        }
    }
```

- [ ] **Step 5: Add `includeArchived` to `listSummaries`**

`listSummaries` currently ends `FROM review_status rs ORDER BY rs.updated_at DESC` (`ReviewProjection.java:963`). Change the signature to `listSummaries(boolean includeArchived)` and insert before the `ORDER BY`:

```sql
                  FROM review_status rs
                 WHERE (? OR rs.archived_at IS NULL)
                 ORDER BY rs.updated_at DESC
```

binding `includeArchived` as the first parameter. Update every existing caller to pass `false`.

- [ ] **Step 6: Run and confirm they pass**

Run: `./gradlew :spire-orchestrator:test --tests "*ReviewArchivalTest*"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ArchiveOutcome.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewArchivalTest.java
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
EOF
```

---

### Task 3: Archive and unarchive over REST

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/web/ReviewsResource.java:175-186` (replace `delete`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/web/ReviewArchiveResourceTest.java`

**Interfaces:**
- Consumes: `ArchiveOutcome archiveReview(...)`, `boolean unarchiveReview(...)`, `listSummaries(boolean)` (Task 2).
- Produces: `POST /api/reviews/{workspace}/{slug}/{pr}/archive`, `POST …/unarchive`, `GET /api/reviews?includeArchived=true`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void archivingAnUnknownReviewIs404() {
    given().when().post("/api/reviews/TEST-WS/TEST-REPO/999999/archive")
            .then().statusCode(404);
}

@Test
void archivingTwiceIs409WithAnActionableMessage() {
    long pr = seedCompletedReview();
    given().when().post("/api/reviews/TEST-WS/TEST-REPO/" + pr + "/archive")
            .then().statusCode(204);
    given().when().post("/api/reviews/TEST-WS/TEST-REPO/" + pr + "/archive")
            .then().statusCode(409).body(containsString("already archived"));
}

@Test
void archivingARunningReviewIs409AndSaysToWait() {
    long pr = seedReviewingReview();
    given().when().post("/api/reviews/TEST-WS/TEST-REPO/" + pr + "/archive")
            .then().statusCode(409).body(containsString("still running"));
}

@Test
void archivedReviewsAreHiddenByDefaultAndVisibleOnRequest() {
    long pr = seedCompletedReview();
    given().when().post("/api/reviews/TEST-WS/TEST-REPO/" + pr + "/archive").then().statusCode(204);

    when().get("/api/reviews").then().statusCode(200)
            .body("findAll { it.prId == " + pr + " }", hasSize(0));
    when().get("/api/reviews?includeArchived=true").then().statusCode(200)
            .body("findAll { it.prId == " + pr + " }", hasSize(1));
}

@Test
void unarchiveRestoresTheReview() {
    long pr = seedCompletedReview();
    given().when().post("/api/reviews/TEST-WS/TEST-REPO/" + pr + "/archive").then().statusCode(204);
    given().when().post("/api/reviews/TEST-WS/TEST-REPO/" + pr + "/unarchive").then().statusCode(204);
    when().get("/api/reviews").then().body("findAll { it.prId == " + pr + " }", hasSize(1));
}
```

Annotate the class `@QuarkusTest` and `@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})`, matching `LlmModelResourceTest`.

- [ ] **Step 2: Run and confirm they fail**

Run: `./gradlew :spire-orchestrator:test --tests "*ReviewArchiveResourceTest*"`
Expected: FAIL — 404/405 on the archive path, which does not exist yet.

- [ ] **Step 3: Replace the DELETE endpoint**

Remove the `delete` method entirely and add:

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
            case ALREADY_ARCHIVED -> conflict("This review is already archived.");
            case STILL_RUNNING -> conflict("This review is still running. "
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

Note `conflict(...)` returns the exception for the `switch` to throw — change the two `conflict(...)` arms to `throw conflict(...)` if the compiler objects to mixing values and throws in a switch expression; a plain `if/else` chain is acceptable here.

- [ ] **Step 4: Add `includeArchived` to the list endpoint**

Find the `GET` list method in `ReviewsResource` and add `@QueryParam("includeArchived") @DefaultValue("false") boolean includeArchived`, passing it to `projection.listSummaries(includeArchived)`.

- [ ] **Step 5: Run and confirm they pass**

Run: `./gradlew :spire-orchestrator:test --tests "*ReviewArchiveResourceTest*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/web/ReviewsResource.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/web/ReviewArchiveResourceTest.java
git commit -m "Serve archive and unarchive instead of delete"
```

---

### Task 4: Keep a purged review's charges out of the PR that inherits its id

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java` — lines `954`, `957`, `959`, `961` (listSummaries), `1085` (chargeLines), `1195`+`1197` (costOf), `1410` (cumulativeCost), `1422` (latestModelFor), `1434` (unpricedCallsFor)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/CostAttentionRow.java` — both enum constants' queries
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/PurgedChargeIsolationIT.java`

**Interfaces:**
- Consumes: `llm_charge.archived_at` (Task 1).
- Produces: nothing new; changes existing query behaviour only.

**Why this task exists:** `llm_charge.archived_at` is written only by a future purge, so today every row is NULL and these filters are no-ops. They must land now anyway, because the day the purge is written is the day a re-registered PR starts inheriting a dead review's money — and that is the defect this whole design is guarding.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.orchestrator.readmodel;

/**
 * A purge hard-deletes the review row and stamps its charges. The PR is then registrable again, and
 * review_id is stable per PR — so the new review reads the old rows unless every ledger query filters.
 * This is the only test that exercises what llm_charge.archived_at is for.
 */
@QuarkusTest
class PurgedChargeIsolationIT {

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void aReRegisteredPrInheritsNothingFromAPurgedReview() throws SQLException {
        long pr = newPr();
        String id = ReviewIds.reviewId(new RepoRef("TEST-WS", "TEST-REPO"), pr);
        seedCompletedReviewWithCharges("TEST-WS", "TEST-REPO", pr);

        // Simulate the future purge: stamp the ledger, drop the review row.
        stampAllCharges(id);
        deleteReviewRowDirectly(id);

        // The PR is registered again — same review_id, brand new review, no charges of its own.
        seedCompletedReviewWithoutCharges("TEST-WS", "TEST-REPO", pr);

        assertEquals(0L, projection.costOf(id).totalMillicents(),
                "a re-registered PR must not inherit a purged review's spend");
        assertTrue(projection.chargeLines(id).isEmpty(),
                "a re-registered PR must not inherit a purged review's charge lines");
        ReviewSummary row = projection.listSummaries(false).stream()
                .filter(s -> s.prId() == pr).findFirst().orElseThrow();
        assertEquals("", row.model(), "nor its model badge");
        assertEquals(0, row.unpricedCalls(), "nor its unpriced-call count");
    }
}
```

`stampAllCharges` runs `UPDATE llm_charge SET archived_at = now() WHERE review_id = ?`; `deleteReviewRowDirectly` runs `DELETE FROM review_status WHERE review_id = ?`. Both go straight through the `DataSource` — the purge does not exist yet, and this test is what makes writing it safe later.

If `ReviewSummary`'s accessors are not `model()` / `unpricedCalls()`, read the record and use the real names.

- [ ] **Step 2: Run and confirm it fails**

Run: `./gradlew :spire-orchestrator:test --tests "*PurgedChargeIsolationIT*"`
Expected: FAIL — the assertion on `totalMillicents` reports the purged review's spend.

- [ ] **Step 3: Add the filter to every ledger read**

Append `AND archived_at IS NULL` (or `AND c.archived_at IS NULL` where the query aliases the table as `c`) to **all ten** sites listed under **Files**. Two need care:

- `listSummaries` line `957` nests a subquery inside another — filter the **inner** `SELECT model FROM llm_charge` as well as the outer, or the vendor badge still resolves from a purged model name.
- `costOf` (`1195`, `1197`) has two separate references; both need it.

Leave the `INSERT` at `1124` alone, and leave every point read by `review_id` that is *not* a ledger read (`commitOf`, `loadDetail`'s row fetch) unfiltered — an archived review must still answer for itself.

- [ ] **Step 4: Add the filter to both cost attention rows**

In `CostAttentionRow.java`, add `AND archived_at IS NULL` to the `UNPRICED` and `UNRECONCILED` count queries, so a purged review cannot keep a cost warning raised.

- [ ] **Step 5: Run the full orchestrator suite**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS, including `PurgedChargeIsolationIT`. If `LlmChargeProjectionIT` or `AttentionQueriesCostTest` fail, a filter went on a query that serves an archived review's own page — re-read Step 3.

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/CostAttentionRow.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/PurgedChargeIsolationIT.java
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

### Task 5: Stop the attention panel and the retry sweep from resurrecting archived reviews

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java:173-184` (`reviewRows`)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java:305-313` (`claimDueRetries`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/ArchivedReviewAttentionTest.java`

**Interfaces:**
- Consumes: `archiveReview` (Task 2).

- [ ] **Step 1: Write the failing tests**

```java
@Test
void anArchivedFailedReviewStopsRaisingAttention() {
    long pr = newPr();
    seedFailedReview("TEST-WS", "TEST-REPO", pr);
    assertTrue(hasRowFor(pr), "a failed review raises attention while live");

    projection.archiveReview("TEST-WS", "TEST-REPO", pr);

    assertFalse(hasRowFor(pr),
            "archiving is a fix; a permanently-lit row breaks the panel's own contract");
}

@Test
void anArchivedReviewIsNotSweptBackIntoThePipeline() {
    long pr = newPr();
    seedCompletedReviewWithCharges("TEST-WS", "TEST-REPO", pr);
    String id = reviewId("TEST-WS", "TEST-REPO", pr);
    // Set retry_at directly, bypassing archive's own clearing, to prove the sweep also filters.
    setRetryAtDirectly(id, Instant.now().minusSeconds(1));
    projection.archiveReview("TEST-WS", "TEST-REPO", pr);
    setRetryAtDirectly(id, Instant.now().minusSeconds(1));

    assertFalse(projection.claimDueRetries(Instant.now()).contains(id),
            "the sweep must skip archived reviews even if retry_at is somehow set");
}
```

The second test deliberately re-sets `retry_at` **after** archiving. Archive clearing it is one defence; the sweep filtering is the other, and a test that only exercised the first would pass with the second missing.

- [ ] **Step 2: Run and confirm they fail**

Run: `./gradlew :spire-orchestrator:test --tests "*ArchivedReviewAttentionTest*"`
Expected: FAIL on both.

- [ ] **Step 3: Filter both queries**

In `AttentionQueries.reviewRows`, add `AND archived_at IS NULL` to the `REVIEW_STUCK` query and to every other query in that method.

In `claimDueRetries`, change the `WHERE` to:

```sql
                 WHERE retry_at IS NOT NULL AND retry_at <= ? AND archived_at IS NULL
```

- [ ] **Step 4: Run and confirm they pass**

Run: `./gradlew :spire-orchestrator:test --tests "*ArchivedReviewAttentionTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/ArchivedReviewAttentionTest.java
git commit -m "Exclude archived reviews from attention and the retry sweep"
```

---

### Task 6: Refuse the two non-event paths that resurrect an archived review

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ReviewRerunService.java:50-60`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/ManualRegisterResource.java:111-121`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ArchivedReviewGateTest.java`

**Interfaces:**
- Consumes: `boolean archived(String reviewId)` (Task 2).

**Why these two are separate from Task 7:** neither is an integration event, so neither passes through `IntegrationSaga`. A gate placed only in the saga leaves both open.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aRerunOfAnArchivedReviewIsRefusedAndLeavesItsNoticeClaimIntact() {
    long pr = newPr();
    seedCompletedReviewWithCharges("TEST-WS", "TEST-REPO", pr);
    projection.archiveReview("TEST-WS", "TEST-REPO", pr);

    assertThrows(NotFoundException.class,
            () -> rerunService.rerun("TEST-WS", "TEST-REPO", pr),
            "an archived review must not be re-run");
}

@Test
void registeringAnArchivedPrIs409NotASilentSuccess() {
    long pr = newPr();
    seedCompletedReviewWithCharges("TEST-WS", "TEST-REPO", pr);
    projection.archiveReview("TEST-WS", "TEST-REPO", pr);

    given().contentType(ContentType.JSON)
            .body("{\"workspace\":\"TEST-WS\",\"slug\":\"TEST-REPO\",\"pr\":" + pr + "}")
            .when().post("/api/register")
            .then().statusCode(409).body(containsString("archived"));
}
```

Read `ManualRegisterResource` for its actual `@Path` and request body shape before writing the second test — do not assume `/api/register`.

- [ ] **Step 2: Run and confirm they fail**

Run: `./gradlew :spire-orchestrator:test --tests "*ArchivedReviewGateTest*"`
Expected: FAIL — the re-run succeeds; the register returns 200.

- [ ] **Step 3: Gate the re-run**

In `ReviewRerunService.rerun`, immediately after `String reviewId = ReviewIds.reviewId(repo, pr);`:

```java
        // Archived means retired. This path is REST, not an integration event, so the saga's gate never
        // sees it — and its first act below (clearWorkerIdempotency) deletes ALL claims for the review,
        // including the archived-notice claim that is supposed to fire once ever.
        if (projection.archived(reviewId)) {
            throw new NotFoundException("Review " + workspace + "/" + slug + "#" + pr
                    + " is archived. Unarchive it before re-running.");
        }
```

- [ ] **Step 4: Gate the manual register**

In `ManualRegisterResource`, before `integration.send(event)`:

```java
        String reviewId = ReviewIds.reviewId(repo, pr.prId());
        // The saga would drop this event for an archived review, but silently: the caller would get a
        // 200 with a reviewId and nothing would happen. A silent non-response reads as a lost webhook,
        // which this project has already had to fix once for the conversation turn cap.
        if (projection.archived(reviewId)) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity("This pull request's review is archived. Unarchive it to review again.")
                    .build());
        }
```

Inject `ReviewProjection projection` if the class does not already hold one.

- [ ] **Step 5: Run and confirm they pass**

Run: `./gradlew :spire-orchestrator:test --tests "*ArchivedReviewGateTest*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ReviewRerunService.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/ManualRegisterResource.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ArchivedReviewGateTest.java
git commit -F- <<'EOF'
Refuse re-run and re-registration of an archived review

Neither path is an integration event, so neither passes through the
saga where the other gates live. The re-run endpoint additionally
clears every worker claim for the review as its first act, including
the archived-notice claim, so an ungated re-run both resurrected the
review and re-armed a notice meant to fire once.

Registering an archived PR answered 200 with a reviewId while the
saga dropped the event, so an operator saw success and nothing
happened. It now answers 409.
EOF
```

---

### Task 7: The archived notice — contract types

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/ActionCommand.java:34` (subtype list) and `:199` (beside `NotifyTurnCap`)
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/IntegrationEvent.java:44` and `:268`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/EventKeys.java:28`
- Test: `spire-contract/src/test/java/dev/codespire/contract/ArchivedNoticeWireTest.java`

**Interfaces:**
- Produces: `ActionCommand.NotifyArchived(String reviewId, RepoRef repo, long prId, ThreadRef threadRef, String scmCredential)` and `IntegrationEvent.ArchivedNotified(String reviewId, ThreadRef threadRef, String commentId)`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void theArchivedNoticeRoundTripsOverTheWire() throws Exception {
    ObjectMapper mapper = WireMapper.create();   // use the project's existing mapper factory
    ActionCommand command = new ActionCommand.NotifyArchived(
            "review::TEST-WS/TEST-REPO#1", new RepoRef("TEST-WS", "TEST-REPO"), 1L,
            new ThreadRef("TEST-THREAD"), "TEST-CREDENTIAL");

    String json = mapper.writeValueAsString(command);
    ActionCommand back = mapper.readValue(json, ActionCommand.class);

    assertEquals(command, back);
    assertTrue(json.contains("\"NotifyArchived\""), "the discriminator names the subtype");
}

@Test
void theArchivedNotifiedEventIsKeyedByReviewId() {
    IntegrationEvent event = new IntegrationEvent.ArchivedNotified(
            "review::TEST-WS/TEST-REPO#1", new ThreadRef("TEST-THREAD"), "TEST-COMMENT");
    assertEquals("review::TEST-WS/TEST-REPO#1", EventKeys.of(event));
}

@Test
void aTopLevelNoticeCarriesNoThread() throws Exception {
    ObjectMapper mapper = WireMapper.create();
    ActionCommand command = new ActionCommand.NotifyArchived(
            "review::TEST-WS/TEST-REPO#1", new RepoRef("TEST-WS", "TEST-REPO"), 1L, null,
            "TEST-CREDENTIAL");
    assertEquals(command, mapper.readValue(mapper.writeValueAsString(command), ActionCommand.class));
}
```

Read a neighbouring wire test (e.g. the one covering `NotifyTurnCap`) for the real mapper factory and `EventKeys` entry-point names, and match them.

- [ ] **Step 2: Run and confirm it fails**

Run: `./gradlew :spire-contract:test --tests "*ArchivedNoticeWireTest*"`
Expected: FAIL — `NotifyArchived` does not exist.

- [ ] **Step 3: Add the command**

In `ActionCommand.java`, beside `NotifyTurnCap`:

```java
    /**
     * Tell a human that this review is archived and no further reviews will run for the pull request.
     *
     * <p>Carries no LLM credential — the notice is fixed text, so retiring a PR costs no tokens and
     * always says the same thing. {@code threadRef} is null for a top-level PR comment and non-null to
     * reply inside a thread, so the notice appears where the event that triggered it arrived.
     *
     * <p>The worker claims a CONSTANT idempotency slot for this, not the thread, which is what makes it
     * fire once per review rather than once per thread.
     */
    record NotifyArchived(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                          String scmCredential) implements ActionCommand {
    }
```

and register it in the `@JsonSubTypes` list at `:34`:

```java
        @JsonSubTypes.Type(value = ActionCommand.NotifyArchived.class, name = "NotifyArchived"),
```

- [ ] **Step 4: Add the result event**

In `IntegrationEvent.java`, beside `TurnCapNotified`:

```java
    /**
     * The archived notice was posted. Deliberately NOT FollowUpPosted, which bumps the conversation
     * turn count — this notice consumes no turn and involves no model. Carries the thread so the
     * orchestrator can attribute it on the timeline the way it attributes a turn-cap notice.
     */
    record ArchivedNotified(String reviewId, ThreadRef threadRef, String commentId)
            implements IntegrationEvent {
    }
```

register it in `@JsonSubTypes` at `:44`, and add to `EventKeys` at `:28`:

```java
            case IntegrationEvent.ArchivedNotified e -> e.reviewId();
```

- [ ] **Step 5: Run and confirm it passes, then refresh the contract snapshot**

Run: `./gradlew :spire-contract:test`
Expected: PASS. `ContractSchemaSnapshotTest` will fail with a diff — inspect it, confirm it shows exactly the two new types, then update the golden file it names.

- [ ] **Step 6: Commit**

```bash
git add spire-contract/src/main/java/dev/codespire/contract/ \
        spire-contract/src/test/java/dev/codespire/contract/
git commit -m "Add the archived-notice command and its result event"
```

---

### Task 8: The archived notice — worker handler

**Files:**
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/FollowUpWorker.java` (beside `notifyTurnCap`, `:151`)
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/CommandDispatcher.java:60`
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/ArchivedNoticeWorkerTest.java`

**Interfaces:**
- Consumes: `ActionCommand.NotifyArchived`, `IntegrationEvent.ArchivedNotified` (Task 7).
- Produces: `void notifyArchived(ActionCommand.NotifyArchived command)` on `FollowUpWorker`.

- [ ] **Step 1: Write the failing tests**

Model the fakes on the existing turn-cap coverage in this file's sibling tests.

```java
@Test
void theNoticePostsOnceHoweverManyEventsArrive() {
    worker.notifyArchived(notice("TEST-THREAD"));
    worker.notifyArchived(notice("TEST-THREAD"));

    assertEquals(1, comments.replies().size(), "the notice fires once per review");
}

@Test
void theNoticeIsClaimedPerReviewNotPerThread() {
    worker.notifyArchived(notice("TEST-THREAD-A"));
    worker.notifyArchived(notice("TEST-THREAD-B"));

    assertEquals(1, comments.replies().size(),
            "a second thread must not produce a second notice");
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

Run: `./gradlew :spire-review-worker:test --tests "*ArchivedNoticeWorkerTest*"`
Expected: FAIL — `notifyArchived` does not exist.

- [ ] **Step 3: Implement the handler**

```java
    private static final String ARCHIVED_SLOT = "archived-notice";
    private static final String ARCHIVED_NOTICE_KEY = "archived";

    /**
     * Post the one-time notice that this review is archived.
     *
     * <p>The claim slot is a CONSTANT, not the thread ref: that is what makes this once per REVIEW.
     * The store's key is (review_id, commit, anchor_key) and nothing depends on that middle column
     * holding a real commit — the follow-up path already puts a thread ref there.
     */
    public void notifyArchived(ActionCommand.NotifyArchived command) {
        WorkerScmClients.Clients clients = scm.forCommand(command);
        if (idempotency.claim(command.reviewId(), ARCHIVED_SLOT, ARCHIVED_NOTICE_KEY)
                instanceof CommentIdempotencyStore.Claim.AlreadyPosted) {
            // INFO, not DEBUG: this is the only record that an inbound event went unanswered on
            // purpose. Bounded by human activity, so it cannot get noisy.
            LOG.infof("Archived notice already posted for %s — staying quiet", command.reviewId());
            return;
        }
        CommentRef ref = command.threadRef() == null
                ? clients.comments().postSummary(command.repo(), command.prId(), ARCHIVED_TEXT)
                : clients.comments().replyInThread(command.repo(), command.prId(),
                        command.threadRef(), ARCHIVED_TEXT);
        idempotency.markPosted(command.reviewId(), ARCHIVED_SLOT, ARCHIVED_NOTICE_KEY, ref.commentId());
        LOG.infof("Posted archived notice for %s", command.reviewId());
        results.emit(new IntegrationEvent.ArchivedNotified(
                command.reviewId(), command.threadRef(), ref.commentId()));
    }
```

with the text as a constant on the class:

```java
    /**
     * Fixed text: no model is called, so this never varies. It does not invite an @-mention — unlike
     * the turn cap, no policy overrides retirement.
     */
    private static final String ARCHIVED_TEXT =
            "This review has been archived, so no further reviews will run for this pull request.";
```

- [ ] **Step 4: Route the command**

In `CommandDispatcher.java`, beside line 60:

```java
                case NotifyArchived c -> followUpWorker.notifyArchived(c);
```

and add the import.

- [ ] **Step 5: Run and confirm they pass**

Run: `./gradlew :spire-review-worker:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ \
        spire-review-worker/src/test/java/dev/codespire/worker/pipeline/ArchivedNoticeWorkerTest.java
git commit -m "Post a one-time notice when a retired PR gets activity"
```

---

### Task 9: Gate the four integration events and emit the notice

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/IntegrationSaga.java:94-140`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java:271-279` (handle `ArchivedNotified`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ArchivedEventGateTest.java`

**Interfaces:**
- Consumes: `archived(String)` (Task 2), `NotifyArchived` (Task 7).

- [ ] **Step 1: Write the failing tests**

```java
/**
 * Phrased as "unchanged", not "creates no new review row" — the latter cannot fail, because
 * review_status's primary key forbids a second row for one PR regardless of any gate.
 */
@ParameterizedTest
@MethodSource("inboundEvents")
void anInboundEventLeavesAnArchivedReviewUnchangedAndEmitsOnlyTheNotice(IntegrationEvent event) {
    seedArchivedReview("TEST-WS", "TEST-REPO", PR);
    String before = snapshotOf(reviewId("TEST-WS", "TEST-REPO", PR));

    saga.on(event);

    assertEquals(before, snapshotOf(reviewId("TEST-WS", "TEST-REPO", PR)),
            "an archived review is frozen");
    assertEquals(1, commands.emitted().size());
    assertInstanceOf(ActionCommand.NotifyArchived.class, commands.emitted().getFirst());
}

static Stream<IntegrationEvent> inboundEvents() {
    return Stream.of(authorReplied(), manualCommand("review"), prUpdated(), prClosed());
}

@Test
void closingAnArchivedPrDoesNotMoveItsPrState() {
    seedArchivedReview("TEST-WS", "TEST-REPO", PR);

    saga.on(prClosed());

    assertEquals("OPEN", projection.loadDetail(reviewId("TEST-WS", "TEST-REPO", PR))
            .orElseThrow().prState(), "an archived review's badge is frozen at archival");
}
```

`prClosed()` is the case the first draft of the design missed, and it is the one that writes `pr_state` — so the second test is the one that would catch a gate placed on only three of the four events.

- [ ] **Step 2: Run and confirm they fail**

Run: `./gradlew :spire-orchestrator:test --tests "*ArchivedEventGateTest*"`
Expected: FAIL — the events are handled normally; `pr_state` moves to `CLOSED`.

- [ ] **Step 3: Gate the switch**

At the top of the `switch (event)` in `IntegrationSaga`, before any case runs, extract the review id and check. For `AuthorReplied` the check must run **before** `threads.markThreadLocation`, which otherwise writes to an archived review:

```java
        String archivedId = archivedReviewIdOf(event);
        if (archivedId != null) {
            notifyArchived(archivedId, event);
            return;
        }
```

with:

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

`notifyArchived(...)` records a timeline entry and emits the command, brokering the SCM credential the same way `ConversationSaga` does for `NotifyTurnCap` — read that call site and mirror it. Pass the event's thread ref for `AuthorReplied`, and `null` for the other three.

- [ ] **Step 4: Handle the result event**

In `ResultSaga`, beside the `TurnCapNotified` case, add an `ArchivedNotified` case that appends a timeline entry. It must **not** call anything that bumps the conversation turn count.

- [ ] **Step 5: Run and confirm they pass**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ArchivedEventGateTest.java
git commit -F- <<'EOF'
Retire an archived PR and answer its activity once

Four inbound events now stop at an archived review and produce a
one-time notice instead: a reply, a slash command, a PR update and a
PR close. The close is the one that writes pr_state, so without it an
archived review's badge would still move on the first merge --
breaking the frozen-state property archival promises.

The reply path checks before recording the thread location, which
would otherwise write to the archived review on the way past.
EOF
```

---

### Task 10: UI — Show archived, Archive and Unarchive

**Files:**
- Modify: `spire-ui/src/api.ts:197` (replace the DELETE call)
- Modify: `spire-ui/src/components/ReviewsList.tsx`
- Modify: `spire-ui/src/components/ReviewDetail.tsx:24,158-162`
- Test: `spire-ui/src/components/ReviewsList.test.ts`, `spire-ui/src/components/ReviewDetail.test.tsx`

**Interfaces:**
- Consumes: `POST …/archive`, `POST …/unarchive`, `GET /api/reviews?includeArchived=true` (Task 3).

- [ ] **Step 1: Write the failing tests**

```ts
it('asks the server for archived rows only when the box is checked', async () => {
  render(<ReviewsList />);
  await screen.findByText(/TEST-REPO/);
  expect(apiFetch).toHaveBeenLastCalledWith(expect.stringContaining('/api/reviews'), undefined);

  await userEvent.click(screen.getByLabelText(/show archived/i));

  expect(apiFetch).toHaveBeenLastCalledWith(
    expect.stringContaining('includeArchived=true'), undefined);
});

it('marks an archived row so it cannot be mistaken for live work', async () => {
  render(<ReviewsList />);
  await userEvent.click(screen.getByLabelText(/show archived/i));
  expect(await screen.findByText(/archived/i)).toBeInTheDocument();
});
```

```tsx
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
Expected: FAIL — no such label.

- [ ] **Step 3: Replace the API client call**

In `api.ts`, replace the `DELETE` at `:197`:

```ts
export async function archiveReview(workspace: string, slug: string, pr: number): Promise<void> {
  const res = await apiFetch(
    `/api/reviews/${encodeURIComponent(workspace)}/${encodeURIComponent(slug)}/${pr}/archive`,
    { method: 'POST' },
  );
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

The 409 body carries an actionable sentence ("still running", "already archived"), so surface `res.text()` rather than a generic message.

Add `includeArchived` to the list fetch, appending `?includeArchived=true` when set.

- [ ] **Step 4: Add the checkbox and the archived marker**

In `ReviewsList.tsx`, add one `useState<boolean>` for `showArchived`, a labelled checkbox beside the existing filter chips, and pass the flag to the fetch. Render an **Archived** badge on any row whose `archivedAt` is non-null, using a lucide-react icon (never emoji).

Watch the component budget: `ReviewsList.tsx` was 244 lines and the cap is 250. If this pushes it over, extract the filter bar into its own component in the same commit.

- [ ] **Step 5: Swap the detail-page button**

In `ReviewDetail.tsx`, rename `confirmDelete` to `confirmArchive`, change the tooltip and `aria-label` to "Archive review", call `archiveReview`, and render an **Unarchive review** button instead when the review is archived. Update the confirmation copy: it no longer destroys anything, so wording about permanent deletion is now false.

- [ ] **Step 6: Run tests and the type check**

Run: `cd spire-ui && npm test -- --run && npx tsc --noEmit`
Expected: PASS, `tsc` silent.

- [ ] **Step 7: Commit**

```bash
git add spire-ui/src
git commit -m "Archive reviews from the dashboard instead of deleting them"
```

---

### Task 11: Record the decision

**Files:**
- Modify: `docs/DECISIONS.md` (new ADR at the end; ADR-023's ledger section gains a pointer)
- Modify: `CLAUDE.md` (status entry)
- Modify: `docs/SMOKE-TEST.md` (a mode covering archive → activity → notice → unarchive)

- [ ] **Step 1: Write the ADR**

Add ADR-024 recording: delete became archive; retaining AI usage outranks the clean-slate property it replaced; the clean slate was never complete anyway, since `review_thread` is deleted nowhere; retirement is a **spend boundary** — an author's push must not silently re-bill an operator who archived to be done — and specifically *not* what makes retention safe, which was the first draft's reasoning and was false; charges are stamped at purge rather than at archive, because stamping at archive hid an archived review's cost from its own detail page.

Add a pointer in ADR-023's ledger section, whose "delete is a true clean slate" reasoning no longer describes the system.

- [ ] **Step 2: Update CLAUDE.md**

Add a status entry under the ADR-023 bullet covering the migration, the six gated paths, the notice, and the new test counts (run the suites and use the real numbers — do not estimate).

- [ ] **Step 3: Add the runbook mode**

Append a SMOKE-TEST mode: archive a completed review → confirm it leaves the list and reappears with Show archived → reply on the PR → confirm exactly one notice arrives → reply again → confirm no second notice → `/review` → confirm no review starts → unarchive → confirm it is live and a later archive notifies again.

- [ ] **Step 4: Run the full verification**

```bash
./gradlew testFast --rerun-tasks
./gradlew :spire-orchestrator:test :spire-gateway:test :spire-review-worker:test --rerun-tasks
cd spire-ui && npm test -- --run && npx tsc --noEmit
```

Expected: all green. Use `--rerun-tasks`; a 2-second "BUILD SUCCESSFUL" is a cached pass, not a run.

- [ ] **Step 5: Commit**

```bash
git add docs/DECISIONS.md CLAUDE.md docs/SMOKE-TEST.md
git commit -m "Record archival as the replacement for hard delete"
```

---

## Self-review

**Spec coverage.** Every spec section maps to a task: data model → 1; archive/unarchive/outcomes/refuse-while-running → 2; API surface and `includeArchived` → 3; the ledger filter list → 4; attention + retry sweep → 5; the two non-event gates → 6; notice contract → 7; notice worker → 8; the four event gates and frozen `pr_state` → 9; UI → 10; ADR/CLAUDE/runbook → 11. The spec's 15 tests all appear. The purge is correctly absent — the spec lists it under "deliberately not built".

**Placeholders.** None. Three tasks say "read the neighbouring file and match its names" (the `ReviewProjectionTest` fixtures, the `WireMapper` factory, the `ManualRegisterResource` path) — that is a deliberate instruction to verify against real code rather than a gap, because inventing those names is how a plan produces code that does not compile.

**Type consistency.** `ArchiveOutcome` has the same four values in Tasks 2, 3 and the spec. `archived(String reviewId)` is defined in Task 2 and consumed in 6 and 9. `NotifyArchived`'s five components and `ArchivedNotified`'s three are identical in Tasks 7, 8 and 9. `listSummaries(boolean)` is introduced in Task 2 and used in 3, 4 and 10. `ARCHIVED_SLOT` is defined once, in Task 8.

**One ordering constraint worth stating:** Task 9 consumes both Task 2's `archived(...)` and Task 7's command, so it cannot move earlier. Tasks 4, 5, 6 and 10 are independent of the notice and can proceed in parallel with 7 and 8 if desired.
