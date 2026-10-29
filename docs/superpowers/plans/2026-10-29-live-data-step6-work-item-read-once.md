# Live data step 6 (first batch): work-item read-once screens on React Query — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The decision panel, past decisions, the preparation form and the profiles screen read through `useQuery`
instead of a `useEffect` with its own loading, error and "ignore a late answer" code.

**Architecture:** Each read gets a query key from one module. Reads of work-item state go under the `work-items` root,
so the change feed from step 5 keeps them current; reads of configuration and of the forge stay outside its reach.
Actions call `invalidateQueries` or `setQueryData` instead of bumping a `refresh` counter (spec §3.5).

**Tech Stack:** React 19, `@tanstack/react-query` 5, vitest + Testing Library (CI only).

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` (§3.3, §3.5, §5 step 6).

## Global Constraints

- Screens with no live data use plain `useQuery` with their existing REST function; actions call `invalidateQueries`
  instead of bumping a `refresh` counter (spec §3.5). Moved screen by screen; no big-bang rewrite.
- Frames reach the cache only through `liveCache.ts`; sockets open only through `hooks/useLiveSocket.ts` (CLAUDE.md).
- A forge read is never re-read by a change notice (step 5 ruling for `tracker`; it spends forge rate limit).
- Tests render inside `test/queryWrapper.tsx`. A test that renders the work-item list stubs `WebSocket` with `FakeSocket`.
- Test data uses `TEST-` placeholders only (no-synthetic-data rule).
- No local build, test or lint: CI runs them (no-local-builds rule). "Run" below means "push and read the CI log".
- Commits: imperative, first line ≤ 72 chars, no AI attribution, no close/fix/resolve keyword before an issue number.
- The `.tsx`/`.ts` files under `spire-ui/src` are CRLF: edit with a script that normalises to LF and writes CRLF back.

## Scope

The operator chose the work-item batch (2026-10-29): `DecisionPanel`, `PastDecisions`, `WorkItemActions`,
`WorkItemPreparation`, `WorkPolicies`. **`WorkItemActions` reads nothing**: its only `useEffect` is a mounted flag for
its mutations, and its callers already re-read through `changed`. It is left as it is (Ruling 1). The other areas
(repositories, settings, reviews, prompts, the rest) are later PRs, one per area, on the operator's word.

## Decisions this plan makes

- **New keys** (`components/work-items/workQueries.ts`):
  - `workKeys.approvals(answered: boolean)` → `['work-items','approvals',answered]`, and `workKeys.allApprovals()` →
    `['work-items','approvals']`.
  - `workKeys.evidence(id: string, binding: string)` → `['work-items','evidence',id,binding]`, where `binding` is
    `` `${gate.id}:${gate.version}:${spec.sha256}:${plan.sha256}` ``. The old effect re-read on a new gate or a new
    preparation; a new binding is a new key, so it does the same.
  - `preparationKeys.options(id)` → `['work-preparation','options',id]` and `llmModelsKey` → `['llm-models']`
    (configuration: no notice concerns it).
  - `buildDefaultsKey(repositoryId)` → `['build-defaults',repositoryId]` in `repositories/factory/buildDefaultsApi.ts`.
  - `policyKeys.profiles()` → `['work-policies','profiles']`, `policyKeys.policy(repositoryId)` →
    `['work-policies','policy',repositoryId]`, and `repositoriesKey` → `['repositories']` in
    `repositories/repositoriesApi.ts`.
  - `draftVersion(item)` moves from `DraftArtifact.tsx` to `workQueries.ts` (exported), so the decision panel keys its
    draft read the way the detail page does.
- **What the feed re-reads, changed in `useWorkItemChanges.ts`:**
  - An id notice also re-reads `workKeys.allApprovals()`: answering, opening or expiring a gate is a change to that
    item, and the open and past decision lists show it.
  - `'*'` and every open skip a set of forge reads, `FORGE_READS = new Set(['tracker', 'evidence'])`, instead of
    `tracker` alone. `preparationEvidence` reads the ticket texts from the tracker, the same as `tracker`.
- **The decision panel shares the item read** (`workKeys.item(itemId)`). On the detail page the open panel already
  closes the feed (step 5), so nothing changes there. On the list the feed stays open, so a notice for the item re-reads
  the item, its draft and the open decisions under the panel. That is the safe direction: Approve is offered only when
  the gate's binding matches the texts and the draft on screen, so a newer preparation or draft hides Approve with the
  existing "changed after this decision was opened" message, and a gate answered elsewhere disappears instead of
  offering an Approve the server refuses. The note the operator types is component state and survives a re-read.
- **The decision panel's draft key** is `workKeys.draft(itemId, draftVersion(item) ?? 'current')`. `'current'` keeps
  today's behaviour (read the draft even when the item's events show no write) and sits under `workKeys.drafts(id)`, so
  a notice re-reads it. `DraftArtifact` never reads under `'current'` (it is disabled when the version is null).
- **Loading and errors keep their words.** The panel shows "Loading the decision…" until both the open decisions and
  the item have answered. A failed read shows its error; a failed answer keeps its own error state, as today. A failed
  re-read after a success keeps the last answer on screen and shows the error beside it.
- **The preparation choices stay all-or-nothing.** The harness and model selects fill only when both
  `preparationOptions` and `fetchLlmModels` answered, as `Promise.all` did. The build-setup defaults are read with
  `useQuery` (`enabled: !item.preparation`), and a `useEffect` on its data merges them into the form with the same
  `touched` rule. That effect writes form state; it reads nothing. It merges only when `isFetchedAfterMount` is true
  and `item.preparation` is null: the build setup is saved outside React Query (`RepositoryFactory`), so a cached
  answer can be stale, and the `||` rule would stop the fresh one from replacing it. A disabled query still returns
  cached data, so the preparation guard sits in the effect too, not only in `enabled`.
- **"Loaded" means `data !== undefined`.** The `select` on `approvals(false)` answers `null` for "no open decision",
  and a failed re-read keeps `data` with `status === 'error'`; truthiness or `isSuccess` breaks one of the two. The
  panel's draft counts as loaded when `isSuccess || isError`, as the old `.catch` did.
- **Refresh workflow on the detail page also re-reads `workKeys.allApprovals()`**: the panel now shares the item key,
  so the item and its decision are re-read as a pair. The step-5 comment in `WorkItemDetail.tsx` about re-reads under
  an open panel names this one exception.
- **Profiles: Refresh keeps the table.** Refresh invalidates `['work-policies']` and `repositoriesKey` (exact). The old
  code emptied every repository's policy first, so "Used by" showed "—" until each answered. A saved profile is written
  into the profiles query with `setQueryData`, as the old `setData` did.

## Review Focus

1. A notice under an open decision panel on the list re-reads the item while the operator reads: Approve must follow
   the binding check, and the typed note must stay.
2. A `'*'` or a reconnect must not re-read the ticket texts (`evidence`) or the tracker.
3. A remounted preparation form (step 5 keys it on the revision) must still not overwrite a registered preparation or a
   coordinate the operator cleared.
4. Two repositories' policies answering out of order must each land under their own repository.
5. A failed re-read after a success must not blank the decision panel, past decisions or the profiles table.

---

### Task 1: Keys and the feed

**Files:**
- Modify: `spire-ui/src/components/work-items/workQueries.ts`, `useWorkItemChanges.ts`, `DraftArtifact.tsx`
- Test: `spire-ui/src/components/work-items/useWorkItemChanges.test.tsx`

**Interfaces:**
- Produces: `workKeys.approvals(answered: boolean)`, `workKeys.allApprovals()`, `workKeys.evidence(id, binding)`,
  `draftVersion(item: { generation: number; events: WorkItemDetail['events'] }): string | null`.

- [ ] **Step 1: Write the failing tests.** Add to `KEYS`: `openDecisions: workKeys.approvals(false)`,
  `pastDecisions: workKeys.approvals(true)`, `evidenceA: workKeys.evidence('TEST-a', 'TEST-gate:1:TEST-s:TEST-p')`.
  - Extend `'re-reads the named item, its draft, its steps and every list page'` (rename it to add "and the decisions"):
    after `{"changed":["TEST-a"]}`, `openDecisions` and `pastDecisions` were read twice; `evidenceA` once.
  - Extend `"re-reads everything but the tracker on '*'"` and `'… on every open'` (rename "but the forge reads"):
    `trackerA` and `evidenceA` were read once; every other key twice.
- [ ] **Step 2: Push; CI fails** on the decision and evidence assertions (the keys do not exist yet: a type error is
  also a RED; prefer adding the keys with no feed change first so the failure is the assertion).
- [ ] **Step 3: Implement** the keys, `FORGE_READS`, `noticeLive(client, { queryKey: workKeys.allApprovals() })` once
  per id notice, and move `draftVersion` into `workQueries.ts` (`DraftArtifact` imports it).
- [ ] **Step 4: Push; CI green** for `useWorkItemChanges.test.tsx` and `DraftArtifact.test.tsx`.
- [ ] **Step 5: Commit** `Re-read open decisions on a notice; keep ticket texts off '*'`.

### Task 2: Decision panel and past decisions

**Files:**
- Modify: `spire-ui/src/components/work-items/DecisionPanel.tsx`, `PastDecisions.tsx`
- Test: `DecisionPanel.test.tsx` (wrap every `render` in `{ wrapper: queryWrapper() }`), `WorkItems.test.tsx`

**Interfaces:**
- Consumes: Task 1's keys and `draftVersion`.

- [ ] **Step 1: Write the failing tests** in `WorkItems.test.tsx`, each on the list with `?decide=` / `?history=`.
  The fixture: admin `fetchMe`, `detail()` with `preparation: prepared()`, an open `approvals(false)` row whose gate
  has `phase: 'plan'` and an `artifact`, and `preparationEvidence` answering `binding` equal to that artifact. Count
  the way the existing feed tests do (`WorkItems.test.tsx` ~943-951): `FakeSocket.open()`, wait for the open's
  re-read, push the notice, expect one more.
  - `'reads an open decision again when its item changes'`: `{"changed":["TEST-item"]}` → `approvals(false)` and
    `getWorkItem('TEST-item')` are each called once more than after the open.
  - `'hides Approve when a notice closes the decision elsewhere'`: the operator has typed `TEST-note`; the next
    `approvals(false)` answers no row (the gate was answered on the tracker) → "This item has no open decision" is
    shown and no Approve button. (A newer preparation alone cannot hide Approve: `mismatched` compares the evidence
    binding with the gate, and a real server closes the gate when the preparation changes.)
  - `'reads past decisions again when an item changes'`: `?history=1`, a notice → `approvals(true)` once more.
  - `'keeps the decision on screen when a re-read fails'`: the next `getWorkItem` rejects → the heading
    "Approve the plan" stays and the error is shown in an alert.
  - In `DecisionPanel.test.tsx`: `'shares the item read with the screen behind it'`: a hand-built
    `QueryClientProvider` whose client holds `workKeys.item('TEST-item')` (as `useWorkItemChanges.test.tsx` builds its
    client), and `getWorkItem` returning a promise that never settles → the heading "Approve the plan" is shown.
    Before the change the panel stays on "Loading the decision…".
- [ ] **Step 2: Push; CI fails** on those five; the existing 32 tests in `DecisionPanel.test.tsx` (30 panel, 2 past
  decisions) still pass inside the wrapper.
- [ ] **Step 3: Implement.** `DecisionPanel`: four `useQuery` calls (`approvals(false)` with `select` finding this
  item's row, `item`, `evidence` with `enabled: admin && !!gateKey && !!prepared`, draft with
  `enabled: admin && !!gateKey && draftPhase`); `loaded` derives from the first two; keep `answer`/`revise` errors in
  local state. Drop the `live`-ref effects that only guarded the reads (keep the one guarding `decide`/`reviseDraft`).
  `PastDecisions`: one `useQuery(workKeys.approvals(true))`. `WorkItemDetail.invalidate()` adds
  `workKeys.allApprovals()`.
- [ ] **Step 4: Push; CI green** for both files and `WorkItems.test.tsx`.
- [ ] **Step 5: Commit** `Read the decision panel and past decisions with useQuery`.

### Task 3: Preparation form

**Files:**
- Modify: `spire-ui/src/components/work-items/WorkItemPreparation.tsx`, `workQueries.ts`,
  `repositories/factory/buildDefaultsApi.ts`
- Test: `WorkItemPreparation.test.tsx` (wrap every `render`)

- [ ] **Step 1: Write the failing tests.**
  - `'shows the choices at once when the form opens again'`: render, wait for the `TEST-harness` option, unmount; set
    `preparationOptions` to a promise that never settles; render again with the same wrapper; `await
    findByLabelText('Harness')` (the form waits for `useMe`) → the `TEST-harness` option is present. Before: never.
  - `'fills from a fresh build setup, not a cached one'`: first open answers `buildDefaults` with branch
    `TEST-old`; unmount; the next answer is `TEST-new`, deferred; render again → Base branch is empty until the deferred
    answer resolves, then `TEST-new`.
- [ ] **Step 2: Push; CI fails** on the first (the second guards the regression the move would add; it passes before
  and is proven by the mutation check); the existing 20 tests pass inside the wrapper.
- [ ] **Step 3: Implement** the three `useQuery` calls and the defaults-merge effect described above.
- [ ] **Step 4: Push; CI green**, including `'keeps a registered preparation …'` and
  `'does not refill a coordinate the operator deliberately cleared'` unchanged.
- [ ] **Step 5: Commit** `Read the preparation choices and build setup with useQuery`.

### Task 4: Profiles

**Files:**
- Modify: `spire-ui/src/components/work-items/WorkPolicies.tsx`, `workQueries.ts`,
  `repositories/repositoriesApi.ts`
- Test: `WorkPolicies.test.tsx` (wrap every `render`)

- [ ] **Step 1: Write the failing test** `'keeps who uses a profile on screen while Refresh reads again'`: the table
  shows `TEST-owner/TEST-repo` under "Used by"; click "Refresh profiles" with `api.policy` now pending → the repository
  is still listed; after it answers, `api.profiles` and `api.policy` were each called twice.
- [ ] **Step 2: Push; CI fails** on it (today Refresh empties the policies first).
- [ ] **Step 3: Implement** with `useQuery` for profiles and repositories and `useQueries` for the policies;
  `saved` → `setQueryData(policyKeys.profiles(), …)`.
- [ ] **Step 4: Push; CI green**, including `'files a slow repository policy under the repository it was asked about'`.
- [ ] **Step 5: Commit** `Read profiles and repository policies with useQuery`.

### Task 5: Mutation check and docs

- [ ] **Step 1: Mutation commit** (reverted next): drop `'evidence'` from `FORGE_READS`, drop the `allApprovals`
  notice, and drop the `isFetchedAfterMount` guard. Expected CI: exactly six fail — three feed tests (`'*'`, open,
  id notice), two list tests (`'reads an open decision again …'`, `'reads past decisions again …'`) and
  `'fills from a fresh build setup, not a cached one'`.
- [ ] **Step 2: Revert commit.**
- [ ] **Step 3: Docs.** CLAUDE.md live-data bullet: one sentence that the decision panel, past decisions, the
  preparation form and the profiles screen read through `useQuery`, and that `FORGE_READS` is what `'*'` skips. Spec
  status line: "step 6 has started: the work-item read-once screens (PR #…)". The plan file is committed with them.
- [ ] **Step 4: Commit** `Document the work-item read-once screens on React Query`.

## Rulings

1. `WorkItemActions` is not changed: it reads nothing — no read to move — cost if wrong: one screen left for a later PR.
2. The decision panel follows the feed on the list (shared item key) instead of reading once — the binding check makes
   a newer read safer than a stale one — cost if wrong: an operator sees the panel change under them; mitigation is
   `enabled`-gating like the detail page.
