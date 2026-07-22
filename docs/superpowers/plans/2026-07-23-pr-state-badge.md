# PR State Badge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the pull/merge request's own state — Open / Merged / Closed — on the reviews list and detail, as a badge distinct from the review-processing status, driven by the open/close webhook events all three SCMs already deliver.

**Architecture:** Purely additive. A `pr_state` column on `review_status` (default OPEN) is set from the events the ingresses already emit: any `PullRequestEventReceived` → OPEN, `PullRequestClosed(MERGED)` → MERGED, `PullRequestClosed(DECLINED)` → CLOSED. The existing cancel-on-close flow is UNCHANGED — the `ReviewLifecycle` decider already no-ops `CancelReview` on a non-in-flight review (`state.isReviewing() ? ReviewCancelled : no-op`), so a merge/close on a completed review preserves its status while `pr_state` records the outcome. Surfaced on `ReviewSummary`/`ReviewDetail`; the UI renders a separate PR-state badge.

**Tech Stack:** Java 25 / Quarkus 3.36; Postgres read model; React/Vite UI (vitest, lucide-react); JDK 25 via user gradle.properties; Docker for Testcontainers.

## Global Constraints

- PR state values (exact, uppercase, stored + wire): `OPEN`, `MERGED`, `CLOSED`. `PullRequestClosed(DECLINED)` maps to `CLOSED` (user-facing term for a non-merged close); `MERGED` maps to `MERGED`; any `PullRequestEventReceived` (OPENED/UPDATED action) maps to `OPEN`.
- PR state is INDEPENDENT of review status: do NOT change the existing `PullRequestClosed → CancelReview` behavior; only ADD a `setPrState` call. A new review's PR defaults to OPEN (column default); a reopened PR (a fresh `PullRequestEventReceived` after a close) resets to OPEN via the explicit set.
- Works for all three SCMs with no adapter change — GitHub/GitLab/Bitbucket ingresses already emit `PullRequestEventReceived` and `PullRequestClosed(MERGED|DECLINED)`.
- Migration additive (V22). Java 4-space indent, methods ≤30 lines; TS 2-space, `interface` for object shapes, lucide icons only (no emoji as icons); no AI/tool attribution in commits (imperative, ≤72-char subject).
- Each task TDD; run the touched module's suite before committing. Known pre-existing orchestrator Kafka-race flakes (OrchestratorChoreographyTest, GitHub/GitLabWebhookTest, ProviderResourceResolveTest) — verify any failure in isolation.

---

### Task 1: Persist and set PR state (read model + saga)

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V22__pr_state.sql`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewSummary.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewDetail.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/IntegrationSaga.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/readmodel/ReviewProjectionPriorRunIT.java` (extend); saga-fake update in `IntegrationSagaPolicyTest`

**Interfaces:**
- Consumes: `IntegrationSaga.on(...)` switch — `case PullRequestEventReceived e -> onPullRequestEvent(e)` and `case PullRequestClosed e -> lifecycle.handle(reviewId, new RecordCommand.CancelReview(e.reason().name()))`. `PullRequestClosed` has `repo()`, `prId()`, `reason()` (enum `CloseReason { MERGED, DECLINED }`). `ReviewIds.reviewId(repo, prId)`. `ReviewProjection.registerHeader(...)`, `toSummary`/`readRow`/`ReviewRow`, `toDetail`/`loadDetail`, `broadcast`, the shared `update(...)` helper. `ReviewSummary`/`ReviewDetail` records (append the new field at the end, like `answering` was).
- Produces:
  - V22 adds `pr_state VARCHAR(16) NOT NULL DEFAULT 'OPEN'` to `review_status`.
  - `ReviewProjection.setPrState(String reviewId, String prState)` — `UPDATE review_status SET pr_state = ?, updated_at = now() WHERE review_id = ?` then `broadcast(reviewId)`.
  - `ReviewRow` carries `prState` (read `rs.getString("pr_state")`); `ReviewSummary` gains `String prState` (end); `ReviewDetail` gains `String prState` (end); `toSummary`/`toDetail` map it; `listSummaries`/`loadDetail`/`broadcast` all flow it through the shared build path.
  - `IntegrationSaga`: in `onPullRequestEvent(e)` (after the existing registerHeader/observe logic — a place that always runs for an accepted PR event) call `projection.setPrState(reviewId, "OPEN")`; in the `PullRequestClosed` case, ADD `projection.setPrState(ReviewIds.reviewId(e.repo(), e.prId()), e.reason() == CloseReason.MERGED ? "MERGED" : "CLOSED")` ALONGSIDE the existing `lifecycle.handle(..., CancelReview(...))` (do not remove the CancelReview).

- [ ] **Step 1: Write the failing IT + saga assertion**

IT (`ReviewProjectionPriorRunIT`) — `prStateDefaultsOpenAndSetSurfacesOnSummaryAndDetail`:
```java
    @Test
    void prStateDefaultsOpenAndSetSurfacesOnSummaryAndDetail() {
        String reviewId = "review::ws/pr-state-it#1";
        projection.registerHeader(reviewId, new RepoRef("ws", "pr-state-it"), 1L,
                "t", "a", "aid", "src", "dst", "c1", "http://x", "github", "completed", 6);
        assertEquals("OPEN", projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow().prState(),
                "a new review's PR defaults OPEN");
        projection.setPrState(reviewId, "MERGED");
        assertEquals("MERGED", projection.listSummaries().stream()
                .filter(s -> s.id().equals(reviewId)).findFirst().orElseThrow().prState());
        assertEquals("MERGED", projection.loadDetail("ws", "pr-state-it", 1L).orElseThrow().prState());
    }
```
Saga seam (`IntegrationSagaPolicyTest`, its hand-fake `ReviewProjection` recording style): a `PullRequestClosed(reason=MERGED)` → the fake records `setPrState(reviewId, "MERGED")` AND the existing `CancelReview` still issued; a `PullRequestClosed(reason=DECLINED)` → `setPrState(reviewId, "CLOSED")`; an accepted `PullRequestEventReceived` → `setPrState(reviewId, "OPEN")`. (Match how the file's fakes record calls; the `PullRequestClosed`/`PullRequestEventReceived` fixtures may need small builders — mirror the existing `pr(...)` builder.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :spire-orchestrator:test --tests "*.ReviewProjectionPriorRunIT" --tests "*.IntegrationSagaPolicyTest"`
Expected: COMPILATION FAILURE (`prState`/`setPrState` missing).

- [ ] **Step 3: Implement**

`V22__pr_state.sql`:
```sql
-- The PR's own state (Open / Merged / Closed), distinct from the review-processing status.
-- Set from the open/close webhook events; a new review's PR is open.
ALTER TABLE review_status ADD COLUMN pr_state VARCHAR(16) NOT NULL DEFAULT 'OPEN';
```
`ReviewProjection`:
```java
    /** The PR's own Open/Merged/Closed state — independent of the review status (fix: PR-state badge). */
    public void setPrState(String reviewId, String prState) {
        update("UPDATE review_status SET pr_state = ?, updated_at = now() WHERE review_id = ?",
                ps -> {
                    ps.setString(1, prState);
                    ps.setString(2, reviewId);
                });
        broadcast(reviewId);
    }
```
Add `prState` to `ReviewRow` (+ `readRow` reads `rs.getString("pr_state")`), to `ReviewSummary` (end component) and `ReviewDetail` (end component), and thread it through `toSummary`/`toDetail`/`ReviewRow.toSummary`. (`SELECT *`/`rs.*` already returns the new column.)
`IntegrationSaga`: import `CloseReason`; in the `PullRequestClosed` case add the `setPrState(...)` line (MERGED→"MERGED", else "CLOSED") next to the existing `lifecycle.handle(...)`; in `onPullRequestEvent` add `projection.setPrState(reviewId, "OPEN")` on the accepted path (where `registerHeader` runs). Update the `IntegrationSagaPolicyTest` fake `ReviewProjection` with a recording `setPrState` override (and any other saga-test fake ReviewProjection — grep).

- [ ] **Step 4: Run the orchestrator suite**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS (Flyway applies V22 in the test container; known flakes isolated).

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator
git commit -m "Track the PR open/merged/closed state in the read model

A new pr_state column (default OPEN) records the pull/merge request's
own state from the open/close webhook events all three SCMs already
emit — OPEN on any PR event, MERGED/CLOSED on close by reason. It is
independent of the review status: the existing cancel-on-close flow
is unchanged (the decider already no-ops on a completed review), so a
merged PR keeps its review outcome and just gains a MERGED state."
```

---

### Task 2: PR-state badge in the UI

**Files:**
- Modify: `spire-ui/src/api.ts` (`ReviewSummary`/`ReviewDetail` interfaces)
- Modify: `spire-ui/src/render.tsx` (a `prStateBadge` helper) + `spire-ui/src/components/ReviewsList.tsx` (row) + `spire-ui/src/components/ReviewDetail.tsx` (header)
- Modify: `spire-ui/src/index.css` (badge styles)
- Test: a render vitest (match the project's `render*.test.tsx` style)

**Interfaces:**
- Consumes Task 1's JSON: `ReviewSummary.prState` / `ReviewDetail.prState` = `'OPEN' | 'MERGED' | 'CLOSED'`.
- Produces: `api.ts` adds `prState: 'OPEN' | 'MERGED' | 'CLOSED'` (a `type PrState` union) to both interfaces. `prStateBadge(prState)` renders a small badge — Open (lucide `GitPullRequest`, neutral/green accent), Merged (`GitMerge`, iris/purple), Closed (`GitPullRequestClosed`, muted/red) — using existing theme palette variables, light+dark aware. Rendered on the reviews-list row (near the outcome badge) and the detail header (near the status/outcome badge), DISTINCT from the review outcome badge.

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { prStateBadge } from './render'

describe('prStateBadge', () => {
  it('labels each PR state', () => {
    expect(renderToStaticMarkup(<>{prStateBadge('OPEN')}</>).toLowerCase()).toContain('open')
    expect(renderToStaticMarkup(<>{prStateBadge('MERGED')}</>).toLowerCase()).toContain('merged')
    expect(renderToStaticMarkup(<>{prStateBadge('CLOSED')}</>).toLowerCase()).toContain('closed')
  })
})
```
(Adapt to the real render-test style/imports in the project.)

- [ ] **Step 2: Run to verify failure**

Run: `cd spire-ui && npx vitest run`
Expected: FAIL — `prStateBadge` not exported.

- [ ] **Step 3: Implement**

`api.ts`: `export type PrState = 'OPEN' | 'MERGED' | 'CLOSED'` and add `prState: PrState` to `ReviewSummary` (inherited by `ReviewDetail`; if `ReviewDetail` doesn't extend `ReviewSummary`, add it there too).
`render.tsx`: add and export
```tsx
export function prStateBadge(prState: PrState) {
  const [cls, Icon, label] =
    prState === 'MERGED' ? ['pr-merged', GitMerge, 'Merged'] :
    prState === 'CLOSED' ? ['pr-closed', GitPullRequestClosed, 'Closed'] :
    ['pr-open', GitPullRequest, 'Open']
  return (
    <span className={`pr-state ${cls}`} title={`Pull request ${label.toLowerCase()}`}>
      <Icon size={13} aria-hidden="true" />
      {label}
    </span>
  )
}
```
(lucide imports: `GitPullRequest`, `GitMerge`, `GitPullRequestClosed`.) Render `prStateBadge(r.prState)` in the list row near the outcome badge (`ReviewsList.tsx`) and the detail header (`ReviewDetail.tsx`), beside — not replacing — the existing status/outcome badge.
`index.css`: `.pr-state` base pill + `.pr-open` (neutral/good accent), `.pr-merged` (`var(--iris)`), `.pr-closed` (muted/`var(--crit)` soft), all via existing palette vars, light+dark.

- [ ] **Step 4: Run UI checks**

Run: `cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: PASS. Any test fixture constructing a full `ReviewSummary`/`ReviewDetail` needs `prState` added.

- [ ] **Step 5: Commit**

```bash
git add spire-ui
git commit -m "Show a distinct open/merged/closed PR-state badge"
```

---

### Task 3: Docs + full verification

**Files:**
- Modify: `CLAUDE.md` (status bullet), `docs/EVENT-MODEL.md` (S9 note)

**Steps:**
- [ ] **Step 1:** `CLAUDE.md` — one status bullet (≤3 lines, existing style): a distinct PR open/merged/closed state (V22) driven by the open/close webhook events across all three SCMs, shown as its own badge separate from the review status.
- [ ] **Step 2:** `docs/EVENT-MODEL.md` — S9 (Cancellation) note: `PullRequestClosed` now ALSO stamps `pr_state` (MERGED/CLOSED) alongside the unchanged cancel-on-in-flight; a PR event stamps OPEN. Keep the file's format.
- [ ] **Step 3: Full verification.** `./gradlew build` (BUILD SUCCESSFUL modulo the known Kafka-race flakes — re-run any in isolation, report both facts; if a stale-daemon or JAVA_HOME JDK21-vs-25 error appears, export JAVA_HOME to the JDK 25 install and/or `./gradlew --stop` then retry). `cd spire-ui && npx vitest run && npx tsc --noEmit`.
- [ ] **Step 4: Commit** `git add docs CLAUDE.md && git commit -m "Document the PR-state badge"`

---

## Self-review checklist (author ran this)

- Coverage: PR-state model+persistence+set → T1; badge → T2; docs → T3. Cancel-on-close intentionally UNCHANGED (decider already guards `isReviewing`).
- Type consistency: `prState` String on the Java records / `PrState` union in TS; values `OPEN`/`MERGED`/`CLOSED` exact across migration default, saga set calls, and the UI badge.
- Flagged for implementers: confirm `onPullRequestEvent` has a single accepted-path point where `setPrState(OPEN)` belongs (after allowlist/observe checks, alongside registerHeader) so a rejected/skipped PR event doesn't stamp state on a non-registered review; confirm whether `ReviewDetail` extends `ReviewSummary` (inherit prState) or needs its own field; grep for ALL saga-test fake `ReviewProjection` subclasses needing a `setPrState` no-op.
