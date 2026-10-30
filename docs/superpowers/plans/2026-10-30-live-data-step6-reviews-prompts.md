# Live data step 6 (fifth batch): review and prompt screens on React Query — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The review page (`ReviewDetail` with its `ContextCard`), the review-mode switch (`ReviewModeToggle`) and the
prompt screens (`PromptsSettings`, `PromptDetail`, `PromptScopePicker`, `PromptSamplePicker`) read through `useQuery`.
The review page's hand-written request counter and its "re-load when the summary changes" effect become a key per
review and an `invalidateLive`.

**Architecture:** Same pattern as the settings batches (PR #359, #360): one key per read, declared in `api.ts` beside
the REST function; a cached answer is shown at once; a form fills only from a read made on this visit. The live reviews
list (`useLiveReviews`, always mounted in `App`) keeps the review page current: when this review's summary changes, the
page re-reads its detail.

**Tech Stack:** React 19, `@tanstack/react-query` 5, vitest + Testing Library (CI only).

**Spec:** `docs/superpowers/specs/2026-10-28-live-data-design.md` (§3.5, §5 step 6). Earlier batches: PR #357–#360.

## Global Constraints

- Plain `useQuery` with the existing REST function; actions re-read with `invalidateLive` and write known results with
  `writeLive`/`updateLive` (`liveCache.ts`).
- "Answered" means `data !== undefined`; a failed re-read keeps the data and shows its error beside it.
- A form fills only from a read made on this visit (`isFetchedAfterMount && isSuccess`).
- A `Promise.all` or composed query function is written in the component, calling the `api` exports, so tests'
  `vi.spyOn` applies (PR #360 plan check).
- Tests render inside `test/queryWrapper.tsx`. `TEST-` placeholders only. No local build, test or lint.
- Commits: imperative, ≤ 72 chars, no AI attribution, no close/fix/resolve keyword before an issue number.

## Scope

In: `ReviewDetail`, `ContextCard`, `ReviewModeToggle`, `PromptsSettings`, `PromptDetail` (its `PromptEditor`),
`PromptScopePicker`, `PromptSamplePicker`.

Out (ruling 1): `FindingConversation`. It is rendered by `render.tsx`'s `findingsCard`, which seven `render.*` test
files and the run pages draw without a query provider; moving it puts a provider into all of them. Its fetch trigger is
already factored and tested (`needsFetch`, `refreshThread`). `ReviewsList` reads nothing (its only timer is a clock).

## Decisions this plan makes

- **Keys** (`api.ts`):
  - `reviewDetailKey(workspace, slug, pr) = ['review', workspace, slug, pr]`.
  - `reviewContextKey(workspace, slug, pr, sha) = ['review-context', workspace, slug, pr, sha]`.
  - `prDescriptionKey(workspace, slug, pr, sha) = ['pr-description', workspace, slug, pr, sha]`.
  - `reviewModeKey = ['review-mode']`.
  - `promptsKey(scope) = ['prompts', scope]`, `promptKey(kind, scope) = ['prompt', kind, scope]`,
    `promptScopesKey = ['prompt-scopes']`.
  - The sample picker reads `REVIEWS_KEY` (`useLiveReviews.ts`) with the same query function, exported from there as
    `readLiveReviews`, so the page shares the always-mounted live list and its socket frames.
- **Review page.** `useQuery(reviewDetailKey(...))`. "Loading…" until answered; the error page only while nothing is
  answered; a failed re-read keeps the detail and shows its error in an alert above it. When this review's summary
  `updatedAt` changes after the first one seen (the existing rule), `invalidateLive(reviewDetailKey(...))`.
  `ReviewActions.onChanged` does the same. `window.scrollTo(0, 0)` stays on a change of review.
- **Context card.** Two `useQuery`s. The context read is re-read with `invalidateLive` when `contextReady` changes
  after mount (the old effect's dependency). The description is not (the existing comment: one SCM call per page view).
  `loadFailed`/`descriptionFailed` become the queries' errors, with the same `console.error` lines.
- **Review mode.** `useQuery(reviewModeKey)`, mapped through `normalizeMode`. A toggle writes the chosen mode with
  `writeLive` (optimistic), then the server's answer; a failure writes the previous mode back and shows its error.
- **Prompt list.** `useQuery(promptsKey(scope))`; a scope change is a new key. Errors as before; the list is kept
  beside a failed re-read.
- **Prompt page.** `useQuery(promptKey(kind, scope))`. The editor mounts only from a read made on this visit (it holds
  its own copies of the text). A save writes the saved view into `promptKey` with `writeLive`; reset and "Keep mine"
  write the view they fetched. Then every prompt list and every other prompt key is invalidated
  (`invalidateQueries({ queryKey: ['prompts'] })`, and `['prompt']` with a predicate that skips the key just written),
  because a change at one scope changes what the scopes below it inherit.
- **Scope picker / sample picker.** `useQuery`; a failed read leaves the picker at its default, as now.

## Review Focus

1. A review's summary changing must re-read its detail exactly once per change, never on the first summary seen.
2. Navigating from review A to review B must never show A's detail under B's address (the old request counter).
3. A prompt edit must not be overwritten by a re-read while the editor is open.
4. A prompt saved at the global scope must reach a repository scope's page that inherits it.
5. A failed review-mode save must restore the previous mode.

---

### Task 1: Failing tests

**Files:** every existing test of the seven components renders inside `queryWrapper` (`ContextCard.test.tsx`,
`PromptDetail.drift.test.tsx`, `PromptSamplePicker.test.tsx`, `PromptScopePicker.test.tsx`, `PromptsSettings.test.tsx`,
`ReviewActions.rerun.test.tsx`, `ReviewDetail.*.test.tsx`, `EventStream.test.tsx` if it renders `ReviewDetail`). New:
`ReviewModeToggle.test.tsx` (the existing `.test.ts` keeps `normalizeMode`).

- [ ] **Step 1: Write the failing tests.**
  - Review page: `'shows the review at once when it opens again'` (reopen with a never-settling read). RED.
  - Review page: `'re-reads the detail once when its summary changes'` (rerender with a newer `updatedAt`: one more
    `fetchReviewDetail`; rerender with the same: none). Guard; passes before.
  - Review page: `'keeps the detail beside a failed re-read'`. RED (today the error replaces the page).
  - Prompt list, prompt page, review mode: `'shows … at once when it opens again'`. RED; the prompt page one asserts
    "Loading…" and no editor (the fill rule), and passes before — its mutant is "mount the editor from any success".
  - Prompt page: `'a save reaches the list'`: save, then the list page (same wrapper) shows the new customized badge
    without its read answering. RED.
  - Review mode: `'restores the previous mode when the save fails'`. Guard; passes before.
- [ ] **Step 2: Push; draft PR; CI fails** on exactly the RED tests.
- [ ] **Step 3: Commit** `Test the review and prompt screens on React Query (red)`.

### Task 2: Implementation

- [ ] **Step 1:** keys, `readLiveReviews`, the seven components as decided.
- [ ] **Step 2: Push; CI green.**
- [ ] **Step 3: Commit** `Read the review and prompt screens through React Query`.

### Task 3: Mutation check and docs

- [ ] **Step 1: Mutation commit** (reverted next): the prompt editor mounts from any `isSuccess`; the review page
  re-reads on every summary seen (first included). Expected: exactly the prompt fill test and the summary guard fail.
- [ ] **Step 2: Revert** with `git revert --no-edit <sha>`, checked before the next commit (PR #360 lesson).
- [ ] **Step 3: Docs.** CLAUDE.md live-data bullet and the spec status line; the `FindingConversation` exception.
- [ ] **Step 4: Commit** `Document the review and prompt screens on React Query`.

## Rulings

1. `FindingConversation` stays as it is: moving it forces a query provider into every static render test of
   `render.tsx` — cost if wrong: one more hand-written fetch, already factored and tested.
2. The pull request description read is not re-read on a stage change (existing rule), so its cache entry is per `sha`, as
   the old effect's dependencies were — cost if wrong: none; same calls as before.

## Changes after the plan check (they override the sections above)

1. **Wrappers.** `render.refused.test.tsx` renders `ReviewDetail` and gets a `queryWrapper` too. `ReviewActions.rerun`
   and `EventStream` tests render no query and stay as they are.
2. **A save writes the list row.** Save, reset and "Keep mine" write the view they hold into `promptKey` and into the
   list (`updateLive(promptsKey(scope), rows => rows && upsertBy(rows, view, r => r.kind))`), then invalidate the other
   prompt keys and lists. Invalidation alone cannot show the badge before the list's read answers.
3. **The summary rule is per review.** The previous-`updatedAt` ref holds `{ review, updatedAt }` and starts over when
   the review changes, so opening review B never re-reads B because A's summary differed. The guard test asserts the
   exact call count (1, then 2).
4. **The prompt editor latches.** It mounts once a read made on this visit answered and stays mounted after a failed
   re-read (a ref latch), so an operator's text is never lost to a failed read.
5. **The sample picker reads `REVIEWS_KEY` with `staleTime: Infinity`.** The socket keeps that entry fresh; a failed
   read the picker started must not put an error banner on the reviews list.
6. **Old data stays across a key change** where it did before: `placeholderData: keepPreviousData` on the prompt list
   (scope change) and the context card (sha change).
7. **The context card's `contextReady` effect skips mount** (previous-value ref); its mutant is "invalidate on every
   effect run".
