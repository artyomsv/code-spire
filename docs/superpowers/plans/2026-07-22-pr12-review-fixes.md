# PR #12 Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the five defects found testing PR #12 — the reviews-list row telling the truth about only the latest run (stale cost + findings + "passed"), the "Still open" reply spamming untouched findings, the misleading "clean" findings card during an in-progress review, and the missing "responding" indicator during a follow-up.

**Architecture:** Point fixes along the existing pipeline. The list-row bugs (2+3) are a read-model correctness fix — `ReviewSummary` must reflect the reconciled/cumulative state the detail page already computes, not the last run's overwritten columns. Fix 4 tightens the existing `UNCHANGED` downgrade from file-level to hunk-level. Fix 1 is a UI gate. Fix 5 adds one transient read-model flag + a UI indicator.

**Tech Stack:** Java 25 / Quarkus 3.36; Postgres read models (Tink-encrypted JSON columns); React/Vite UI (vitest); JDK 25 via user gradle.properties; Docker for Testcontainers.

## Global Constraints

- The reviews-list row must reflect the review's CURRENT reconciled state, not the latest run in isolation: cost is cumulative across all LLM calls; `findings`/`blockerCount` count OPEN findings (new this run + still-open/unchanged reconciliation entries, deduped by threadRef); "passed" only when zero open findings remain.
- Fix 4 policy (user-approved): the bot replies "Still open" ONLY when the follow-up commit changed a hunk at/around the finding's own anchor line (hunk-level). A finding whose own line isn't in a changed hunk becomes `UNCHANGED` (silent), even if elsewhere in the same file changed. The user-reply conversational path is unaffected.
- `downgradeUntouched` runs on verdicts whose `line` is in the PRIOR-head coordinate system → check against each hunk's OLD-side range `[oldStart, oldStart + oldLines)`. Register ranges under BOTH `patch.oldPath()` and `patch.newPath()` (rename-safe). A file with no hunks touching the path stays as today (→ UNCHANGED). No incremental diff (null) → no downgrade (unchanged behavior).
- Encrypted columns stay Tink-encrypted, AAD = reviewId. Migrations additive.
- Java 4-space indent, methods ≤30 lines; TS 2-space, `interface` for object shapes, lucide icons only (no emoji as icons); no AI/tool attribution in commits (imperative, ≤72-char subject).
- Each task TDD; run the touched module's suite before committing. Known pre-existing orchestrator Kafka-race flakes (OrchestratorChoreographyTest, GitHub/GitLabWebhookTest, ProviderResourceResolveTest) — verify any failure in isolation.

---

### Task 1: List row reflects cumulative cost + open findings (fixes #2, #3)

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewProjectionPriorRunIT.java` (extend)

**Interfaces:**
- Consumes: `listSummaries()` (`SELECT rs.*, (subquery llm_type) ...` → `toSummary(rs)` → `readRow`/`ReviewRow.toSummary`); `blockerCount(ReviewRow)` (already decrypts `findings_json` per row, counts `"critical"`); `parseReconciliation(json, reviewId)` (decrypts `reconciliation_json` → list of `ReconciliationEntry` with `.status()`/`.sev()`/`.threadRef()`/`.loc()` — read the real ReconciliationEntry record for exact accessors); `parseFindings(json, reviewId)` (→ FindingView with `.sev()`/`.loc()`/`.threadRef()`); `recordOutcome` (overwrites cost_millicents per run — do NOT change it). `ReviewSummary(... int findings, int blockerCount, long costMillicents, ...)`.
- Produces (no schema change, no new ReviewSummary fields — the meaning of the existing fields becomes "reconciled/cumulative"):
  - `listSummaries()` SQL adds a derived cumulative-cost column: `COALESCE((SELECT SUM(c.cost_millicents) FROM review_llm_call c WHERE c.review_id = rs.review_id), rs.cost_millicents) AS total_cost_millicents`, mapped into `ReviewSummary.costMillicents`.
  - A private helper `OpenCounts openCounts(ReviewRow row)` returning `record OpenCounts(int open, int openBlockers)`: computes OPEN findings = this run's new findings (`parseFindings(findingsJson)`) **plus** reconciliation entries whose status is open (`"still open"` or `"unchanged"` — the display-cased strings `parseReconciliation` returns; confirm casing), deduped by threadRef (a reconciliation entry sharing a threadRef with a new finding counts once). `openBlockers` = of those open items, count severity `"critical"`. `toSummary` uses `openCounts` for `findings` and `blockerCount` instead of `findings_count`/`blockerCount(row)`.
  - The same `toSummary` path is used by `broadcast(reviewId)` — confirm broadcast builds its summary through `toSummary`/`readRow` so live list updates are also corrected (if broadcast has a separate summary build, route it through the same helper).

- [ ] **Step 1: Write the failing IT** (extend `ReviewProjectionPriorRunIT`; use its existing `registerHeader`/`recordOutcome`/`recordReconciliation`/`recordLlmCall` helpers — match real signatures):

```java
    @Test
    void listSummaryShowsCumulativeCostAndOpenReconciledFindings() {
        String reviewId = "review::ws/list-recon-it#1";
        projection.registerHeader(reviewId, new RepoRef("ws", "list-recon-it"), 1L,
                "t", "a", "aid", "src", "dst", "c2", "http://x", "github", "completed", 6);
        // last run: 0 new findings, its own cost overwrites cost_millicents
        projection.recordOutcome(reviewId, new ReviewResult(List.of(), "sum",
                new ModelUsage("m", 1, 1, 738)), 6);
        projection.recordLlmCall(reviewId, "review", new ModelUsage("m", 1, 1, 2189));
        projection.recordLlmCall(reviewId, "review", new ModelUsage("m", 1, 1, 738));
        projection.recordLlmCall(reviewId, "reconcile", new ModelUsage("m", 1, 1, 965));
        // reconciliation carries one STILL_OPEN critical
        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-1", "src/A.java", 12,
                        FindingVerdict.Status.STILL_OPEN, "missing")),
                List.of(new PriorFinding("src/A.java", 12, Severity.BLOCKER, "npe", "t-1")));

        ReviewSummary summary = projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
        assertEquals(2189 + 738 + 965, summary.costMillicents(), "cumulative across all LLM calls");
        assertEquals(1, summary.findings(), "one still-open reconciliation finding");
        assertEquals(1, summary.blockerCount(), "the still-open critical");
    }

    @Test
    void listSummaryPassesOnlyWhenNoOpenFindingsRemain() {
        String reviewId = "review::ws/list-recon-it#2";
        projection.registerHeader(reviewId, new RepoRef("ws", "list-recon-it"), 2L,
                "t", "a", "aid", "src", "dst", "c3", "http://x", "github", "completed", 6);
        projection.recordOutcome(reviewId, new ReviewResult(List.of(), "sum",
                new ModelUsage("m", 1, 1, 100)), 6);
        // all prior findings resolved
        projection.recordReconciliation(reviewId,
                List.of(new FindingVerdict("t-9", "src/B.java", 3,
                        FindingVerdict.Status.RESOLVED, "fixed")),
                List.of(new PriorFinding("src/B.java", 3, Severity.MAJOR, "x", "t-9")));
        ReviewSummary summary = projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
        assertEquals(0, summary.findings(), "resolved findings are not open");
    }

    @Test
    void firstReviewOpenCountIsItsNewFindings() {
        String reviewId = "review::ws/list-recon-it#3";
        projection.registerHeader(reviewId, new RepoRef("ws", "list-recon-it"), 3L,
                "t", "a", "aid", "src", "dst", "c1", "http://x", "github", "completed", 6);
        projection.recordOutcome(reviewId, new ReviewResult(
                List.of(new Finding("src/C.java", new LineRange(4, 4), Severity.BLOCKER, "leak", null)),
                "sum", new ModelUsage("m", 1, 1, 500)), 6);
        ReviewSummary summary = projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow();
        assertEquals(1, summary.findings());
        assertEquals(1, summary.blockerCount());
        assertEquals(500, summary.costMillicents(), "no llm_call rows -> falls back to cost_millicents");
    }
```
(Confirm `recordReconciliation`'s parameter order and `ReconciliationEntry`/status casing against the real `ReviewProjection` before finalizing assertions — the status strings in `reconciliation_json` are what `parseReconciliation` returns.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-orchestrator:test --tests "*.ReviewProjectionPriorRunIT"`
Expected: FAIL — cost is 738 (last run), findings is 0 (findings_count), blockers 0.

- [ ] **Step 3: Implement**

Add the cumulative-cost subquery to `listSummaries()`'s SELECT and read it in `readRow` (bind `total_cost_millicents` into the `costMillicents` slot instead of `rs.cost_millicents`). Add:
```java
    private record OpenCounts(int open, int openBlockers) {
    }

    /**
     * The review's currently-open findings — this run's new findings plus reconciliation
     * entries still open (still-open/unchanged), deduped by thread. This is what the list row
     * must show (fixes #3): a review with a carried-forward open critical is NOT "passed".
     */
    private OpenCounts openCounts(ReviewRow row) {
        java.util.Map<String, String> openSevByKey = new java.util.LinkedHashMap<>();
        for (ReviewDetail.FindingView f : parseFindings(row.findingsJson(), row.id())) {
            openSevByKey.put(keyOf(f.threadRef(), f.loc()), f.sev());
        }
        for (ReviewDetail.ReconciliationView r : parseReconciliation(row.reconciliationJson(), row.id())) {
            if ("still open".equals(r.status()) || "unchanged".equals(r.status())) {
                openSevByKey.put(keyOf(r.threadRef(), r.loc()), r.sev());
            }
        }
        int blockers = (int) openSevByKey.values().stream().filter("critical"::equals).count();
        return new OpenCounts(openSevByKey.size(), blockers);
    }

    private static String keyOf(String threadRef, String loc) {
        return threadRef != null && !threadRef.isBlank() ? "t:" + threadRef : "l:" + loc;
    }
```
`ReviewRow` must carry `reconciliationJson` — add it to the record + `readRow` (`SELECT *` already returns the column; just read `rs.getString("reconciliation_json")`). `toSummary` computes `OpenCounts oc = openCounts(row)` and passes `oc.open()`/`oc.openBlockers()` into the `ReviewSummary` `findings`/`blockerCount` slots. Remove the now-unused `blockerCount(ReviewRow)` if nothing else calls it (grep first; `loadDetail` may use it — if so leave it).
Confirm `broadcast(reviewId)`'s summary build routes through `toSummary`/`readRow`; if it has its own path, apply `openCounts` + cumulative cost there too (or refactor both to one `buildSummary(row)`).

- [ ] **Step 4: Run the orchestrator suite**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS (new IT + existing; known Kafka flakes verified isolated).

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator
git commit -m "Show reconciled open findings and cumulative cost in the list

The reviews-list row read the review_status columns each run
overwrites, so a review with a carried-forward open finding showed
'passed / 0' and only the last run's cost. The list now counts open
findings (new + still-open/unchanged reconciliation, deduped) and
sums all LLM-call costs, matching the detail page."
```

---

### Task 2: Hunk-level UNCHANGED downgrade (fix #4)

**Files:**
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ReviewWorker.java`
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/ReviewWorkerTest.java` (extend)

**Interfaces:**
- Consumes: `downgradeUntouched(List<FindingVerdict> verdicts, String incrementalDiff)` (currently path-level via `touchedPaths`); `UnifiedDiffParser.parse(text) → List<FilePatch>`; `FilePatch(oldPath, newPath, change, language, binary, tooLarge, List<Hunk> hunks)`; `Hunk(int oldStart, int oldLines, int newStart, int newLines, List<DiffLine> lines)`; `FindingVerdict(threadRef, path, line, status, note)` with `Status.STILL_OPEN`/`UNCHANGED`. The verdict `line` is in the prior-head (OLD-side) coordinate system.
- Produces: `downgradeUntouched` becomes hunk-level. A `STILL_OPEN` verdict is downgraded to `UNCHANGED` unless the verdict's `line` falls within some hunk's OLD-side range for a patch matching the verdict's `path` (matched against oldPath OR newPath). `incrementalDiff == null` → no downgrade (unchanged). A path with no patch → downgraded (unchanged behavior preserved).

- [ ] **Step 1: Write the failing tests** (ReviewWorkerTest style — the existing `downgradeUntouched`/reconcile tests build small unified diffs; follow that fixture style):

```java
    @Test
    void stillOpenStaysOpenWhenItsOwnLineIsInAChangedHunk() {
        // incremental diff changes lines around 5 in src/A.java; finding at line 5
        String diff = "diff --git a/src/A.java b/src/A.java\n"
                + "--- a/src/A.java\n+++ b/src/A.java\n"
                + "@@ -4,3 +4,3 @@\n line4\n-old5\n+new5\n line6\n";
        List<FindingVerdict> in = List.of(new FindingVerdict("t-1", "src/A.java", 5,
                FindingVerdict.Status.STILL_OPEN, "still missing"));
        List<FindingVerdict> out = ReviewWorker.downgradeUntouchedForTest(in, diff);
        assertEquals(FindingVerdict.Status.STILL_OPEN, out.getFirst().status());
    }

    @Test
    void stillOpenBecomesUnchangedWhenOnlyAnotherPartOfTheFileChanged() {
        // change is around line 5; finding at line 12 (same file, untouched region)
        String diff = "diff --git a/src/A.java b/src/A.java\n"
                + "--- a/src/A.java\n+++ b/src/A.java\n"
                + "@@ -4,3 +4,3 @@\n line4\n-old5\n+new5\n line6\n";
        List<FindingVerdict> in = List.of(new FindingVerdict("t-2", "src/A.java", 12,
                FindingVerdict.Status.STILL_OPEN, "still missing"));
        List<FindingVerdict> out = ReviewWorker.downgradeUntouchedForTest(in, diff);
        assertEquals(FindingVerdict.Status.UNCHANGED, out.getFirst().status(),
                "finding's own line untouched -> silent even though the file changed elsewhere");
    }

    @Test
    void untouchedFileStillDowngrades() {
        String diff = "diff --git a/src/A.java b/src/A.java\n"
                + "--- a/src/A.java\n+++ b/src/A.java\n@@ -4,3 +4,3 @@\n line4\n-old5\n+new5\n line6\n";
        List<FindingVerdict> in = List.of(new FindingVerdict("t-3", "src/Other.java", 9,
                FindingVerdict.Status.STILL_OPEN, "x"));
        assertEquals(FindingVerdict.Status.UNCHANGED,
                ReviewWorker.downgradeUntouchedForTest(in, diff).getFirst().status());
    }

    @Test
    void nullDiffLeavesVerdictsUnchanged() {
        List<FindingVerdict> in = List.of(new FindingVerdict("t-4", "src/A.java", 5,
                FindingVerdict.Status.STILL_OPEN, "x"));
        assertEquals(FindingVerdict.Status.STILL_OPEN,
                ReviewWorker.downgradeUntouchedForTest(in, null).getFirst().status());
    }
```
(`downgradeUntouched` is private static — expose a package-private `static List<FindingVerdict> downgradeUntouchedForTest(...)` delegating to it, OR make the method package-private and call it directly. Match how the existing downgrade tests reach it — if they already call it, reuse that access.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-review-worker:test --tests "*.ReviewWorkerTest"`
Expected: FAIL — `stillOpenBecomesUnchangedWhenOnlyAnotherPartOfTheFileChanged` fails (today's path-level logic keeps it STILL_OPEN because the file is touched).

- [ ] **Step 3: Implement** — replace `downgradeUntouched`:

```java
    private static List<FindingVerdict> downgradeUntouched(List<FindingVerdict> verdicts, String incrementalDiff) {
        if (incrementalDiff == null) {
            return verdicts;
        }
        Map<String, List<int[]>> changedRangesByPath = changedOldSideRanges(incrementalDiff);
        return verdicts.stream()
                .map(v -> v.status() == FindingVerdict.Status.STILL_OPEN && !lineTouched(changedRangesByPath, v)
                        ? new FindingVerdict(v.threadRef(), v.path(), v.line(),
                                FindingVerdict.Status.UNCHANGED, v.note())
                        : v)
                .toList();
    }

    /** OLD-side hunk ranges per path (registered under both old and new path, rename-safe). */
    private static Map<String, List<int[]>> changedOldSideRanges(String incrementalDiff) {
        Map<String, List<int[]>> ranges = new HashMap<>();
        for (FilePatch patch : UnifiedDiffParser.parse(incrementalDiff)) {
            for (Hunk hunk : patch.hunks()) {
                int[] range = { hunk.oldStart(), hunk.oldStart() + Math.max(hunk.oldLines(), 1) - 1 };
                register(ranges, patch.oldPath(), range);
                register(ranges, patch.newPath(), range);
            }
        }
        return ranges;
    }

    private static void register(Map<String, List<int[]>> ranges, String path, int[] range) {
        if (path != null) {
            ranges.computeIfAbsent(path, k -> new ArrayList<>()).add(range);
        }
    }

    private static boolean lineTouched(Map<String, List<int[]>> ranges, FindingVerdict v) {
        List<int[]> forPath = ranges.get(v.path());
        if (forPath == null) {
            return false;
        }
        return forPath.stream().anyMatch(r -> v.line() >= r[0] && v.line() <= r[1]);
    }
```
Remove the old path-level `touchedPaths` code and the now-unused `Stream`/`Collectors` imports if nothing else needs them (check). Add `downgradeUntouchedForTest` only if the tests can't reach the private method.

- [ ] **Step 4: Run the worker suite**

Run: `./gradlew :spire-review-worker:test`
Expected: PASS (new + existing; the prior `downgradeUntouched`-related tests should still pass — a finding whose file is entirely untouched still downgrades).

- [ ] **Step 5: Commit**

```bash
git add spire-review-worker
git commit -m "Downgrade still-open findings to unchanged at hunk granularity

A follow-up that fixes one part of a file no longer draws a
'Still open' reply on every other prior finding in that file. A
still-open finding stays open (and replies) only when its own anchor
line sits inside a changed hunk of the incremental diff; otherwise it
goes quiet as UNCHANGED. The user-reply conversation path is
unaffected."
```

---

### Task 3: Hide the misleading empty findings card during an in-progress review (fix #1)

**Files:**
- Modify: `spire-ui/src/render.tsx` (`findingsCard`)
- Test: `spire-ui/src/render.test.tsx` (or the colocated render test file — match the project's vitest layout)

**Interfaces:**
- Consumes: `findingsCard(r)` where `r` has `status: ReviewStatus`, `stage: number`, `findingsList`, `reconciliation`. The empty-state branch currently renders "✓ clean / No issues found in this diff." whenever `!findingsList.length && !reconciliation.length` — including while `status === 'reviewing'`.
- Produces: when `status === 'reviewing'` and there are no findings/reconciliation yet, the card renders an in-progress placeholder ("Analyzing the diff…") instead of the "clean" state. The clean state renders only for a terminal status with genuinely zero findings. Failed/cancelled branches unchanged.

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { findingsCard } from './render'

describe('findingsCard in-progress gate', () => {
  const base = { findingsList: [], reconciliation: [], stage: 3 } as any

  it('shows an in-progress placeholder, not "clean", while reviewing', () => {
    const html = renderToStaticMarkup(<>{findingsCard({ ...base, status: 'reviewing' })}</>)
    expect(html).not.toContain('No issues found')
    expect(html.toLowerCase()).toContain('analyzing')
  })

  it('shows the clean state when a completed review found nothing', () => {
    const html = renderToStaticMarkup(<>{findingsCard({ ...base, status: 'completed' })}</>)
    expect(html).toContain('No issues found')
  })
})
```
(Adapt the import/fixture shape to the file's real `findingsCard` signature and the project's existing render-test style — check a sibling `render*.test.tsx`.)

- [ ] **Step 2: Run to verify failure**

Run: `cd spire-ui && npx vitest run`
Expected: FAIL — reviewing currently renders "No issues found".

- [ ] **Step 3: Implement** — in `findingsCard`, before the clean empty-state branch, add:

```tsx
  if (!r.findingsList.length && !reconciliation.length && r.status === 'reviewing') {
    return (
      <section className="card">
        <header className="card-head"><h3>Findings</h3></header>
        <div className="clean"><span className="em mono">Analyzing the diff…</span></div>
      </section>
    )
  }
```
(Match the card's real JSX wrapper/classes from the surrounding code; keep the header consistent with the other card branches.)

- [ ] **Step 4: Run UI checks**

Run: `cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: PASS / no type errors.

- [ ] **Step 5: Commit**

```bash
git add spire-ui
git commit -m "Show an in-progress findings state instead of a false clean"
```

---

### Task 4: "Responding" transient state (fix #5) — read model + sagas

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V21__answering_flag.sql`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewSummary.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewDetail.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/IntegrationSaga.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ResultSaga.java`
- Test: `spire-orchestrator/.../readmodel/ReviewProjectionPriorRunIT.java` (extend); saga-fake update in `ResultSagaRetryTest`/`IntegrationSagaPolicyTest`

**Interfaces:**
- Consumes: `IntegrationSaga` AuthorReplied branch (emits AnswerFollowUp via `conversation.planFollowUp(e).ifPresent(...)`, already appends event + `touch`); `ResultSaga` `FollowUpPosted` case (already `touch`); `ReviewProjection.touch/broadcast`; `ReviewSummary`/`ReviewDetail` records; `listSummaries`/`toSummary`/`loadDetail`.
- Produces:
  - V21 adds `answering BOOLEAN NOT NULL DEFAULT FALSE` to `review_status`.
  - `ReviewProjection.setAnswering(String reviewId, boolean answering)` — `UPDATE review_status SET answering = ?, updated_at = now() WHERE review_id = ?` then `broadcast(reviewId)`.
  - `IntegrationSaga` AuthorReplied: INSIDE the `ifPresent` (a follow-up will be answered) call `projection.setAnswering(reviewId, true)` (before/after the existing appendEvent; the single `touch` there can stay or be replaced by setAnswering's broadcast — avoid a double broadcast, prefer setAnswering to own it).
  - `ResultSaga` `FollowUpPosted` AND `FollowUpGenerated`-failure/terminal paths: `projection.setAnswering(reviewId, false)`. (FollowUpPosted is the normal clear; also clear on any followup ReviewFailed for the review so a failed follow-up doesn't stick "responding" forever.)
  - `ReviewSummary` gains `boolean answering` (append at end); `toSummary` reads it. `ReviewDetail` gains `boolean answering` (append near status). `listSummaries`/`loadDetail` select and map it.
  - Saga-test fakes: any fake `ReviewProjection` gets a no-op `setAnswering`.

- [ ] **Step 1: Write the failing IT + saga assertions**

IT (ReviewProjectionPriorRunIT): `setAnsweringTogglesTheFlagAndSurfacesOnSummaryAndDetail` — register header, `setAnswering(reviewId, true)` → `listSummaries` row `.answering()==true` and `loadDetail(...).answering()==true`; `setAnswering(reviewId, false)` → both false.
Saga seam: in `IntegrationSagaPolicyTest`, a human AuthorReplied that plans a follow-up → the fake projection records a `setAnswering(reviewId, true)` call; in `ResultSagaRetryTest`, a `FollowUpPosted` → `setAnswering(reviewId, false)`. (Follow the files' existing fake-recording conventions.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-orchestrator:test --tests "*.ReviewProjectionPriorRunIT" --tests "*.IntegrationSagaPolicyTest" --tests "*.ResultSagaRetryTest"`
Expected: COMPILATION FAILURE (`answering`/`setAnswering` missing).

- [ ] **Step 3: Implement** — V21 SQL:
```sql
-- Transient "the bot is answering a reply" hint for the dashboard (fix #5). Set when a
-- follow-up is dispatched, cleared when it posts or terminally fails. Best-effort UI signal.
ALTER TABLE review_status ADD COLUMN answering BOOLEAN NOT NULL DEFAULT FALSE;
```
Add `setAnswering`, thread `answering` through `ReviewRow`→`ReviewSummary`/`ReviewDetail` and the two select paths, wire the sagas (set true on planned follow-up, false on FollowUpPosted + on a followup ReviewFailed), and add the no-op fake overrides. Keep `setAnswering` ≤30 lines; reuse the class's `update(...)`/`broadcast` helpers.

- [ ] **Step 4: Run the orchestrator suite**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS (Flyway applies V21 in the test container; known flakes isolated).

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator
git commit -m "Track a transient answering flag for follow-up replies

review_status gains an 'answering' flag set when a follow-up reply is
dispatched and cleared when it posts or terminally fails, surfaced on
the summary and detail so the dashboard can show a 'responding'
indicator while the bot works on an answer."
```

---

### Task 5: "Responding" indicator in the UI (fix #5) — list + detail

**Files:**
- Modify: `spire-ui/src/api.ts` (`ReviewSummary`/`ReviewDetail` interfaces)
- Modify: `spire-ui/src/components/ReviewsList.tsx` (row) and `spire-ui/src/components/ReviewDetail.tsx` (header) — or the render helpers they use
- Modify: `spire-ui/src/index.css` (indicator style)
- Test: the relevant render/list vitest

**Interfaces:**
- Consumes Task 4's JSON: `ReviewSummary.answering?: boolean`, `ReviewDetail.answering?: boolean`.
- Produces: `api.ts` adds `answering?: boolean` to both interfaces. When `answering` is true, the reviews-list row shows a small "responding…" pill (lucide `Loader` or `MessageCircle` icon + text, theme-variable colored, no emoji) near the status badge; the detail header shows the same next to the pipeline/status badge. When false/absent, nothing changes.

- [ ] **Step 1: Write the failing test**

```tsx
it('renders a responding indicator when answering', () => {
  const html = renderToStaticMarkup(<>{/* list row or its badge helper */ statusCell({ status: 'completed', answering: true } as any)}</>)
  expect(html.toLowerCase()).toContain('responding')
})
```
(Adapt to the actual list-row/badge helper — if the row is a component, render it with a fixture row `{...base, answering:true}` and assert the indicator; match the file's real test style.)

- [ ] **Step 2: Run to verify failure**

Run: `cd spire-ui && npx vitest run`
Expected: FAIL.

- [ ] **Step 3: Implement** — add `answering?: boolean` to both interfaces in `api.ts`; render a `respondingPill()` (lucide icon + "responding…") gated on `r.answering` in the list row's status cell and the detail header; add a `.responding` CSS class using existing palette variables (a subtle accent, e.g. `var(--iris)` text). Import the lucide icon at the top (never emoji).

- [ ] **Step 4: Run UI checks**

Run: `cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-ui
git commit -m "Show a responding indicator while a reply is being answered"
```

---

### Task 6: Docs + full verification

**Files:**
- Modify: `CLAUDE.md` (status bullet), `docs/EVENT-MODEL.md` (S11 note on hunk-level UNCHANGED + answering flag if the slice describes verdicts)

**Steps:**
- [ ] **Step 1:** `CLAUDE.md` — one status bullet (≤4 lines) noting the PR-12 fix batch (list-row reconciled/cumulative counts, hunk-level UNCHANGED downgrade, in-progress findings state, answering indicator). Match the existing bullet style.
- [ ] **Step 2:** `docs/EVENT-MODEL.md` S11 — update the `UNCHANGED` clause to say the downgrade is hunk-level (finding's own line), and note the transient `answering` flag if the slice lists read-model state.
- [ ] **Step 3: Full verification.** Run `./gradlew build` (BUILD SUCCESSFUL modulo the known Kafka-race flakes — re-run any in isolation and report both facts). Run `cd spire-ui && npx vitest run && npx tsc --noEmit`.
- [ ] **Step 4: Commit** `git add docs CLAUDE.md && git commit -m "Document the PR-12 review fixes"`

---

## Self-review checklist (author ran this)

- Coverage: #2→T1 (cumulative cost), #3→T1 (open-findings count + passed gate), #4→T2 (hunk-level downgrade), #1→T3 (in-progress findings state), #5→T4+T5 (answering flag + indicator), docs→T6.
- Type consistency: `OpenCounts`, `changedOldSideRanges`/`lineTouched`, `answering` boolean threaded through ReviewRow→ReviewSummary/ReviewDetail→api.ts; reconciliation status strings are the display-cased ones `parseReconciliation` returns ("still open"/"unchanged"/"resolved"…) — implementer must confirm exact casing.
- Flagged for implementers: confirm `recordReconciliation` arg order + `ReconciliationView`/`ReconciliationEntry` accessors (T1); confirm `downgradeUntouched` test-access path (T2); confirm `broadcast`'s summary build routes through `toSummary` (T1); the `answering` flag is best-effort (clears on posted + followup-failed; a follow-up that DLQs without a ReviewFailed for the review could leave it stuck until the next activity — acceptable for a UI hint, noted).
