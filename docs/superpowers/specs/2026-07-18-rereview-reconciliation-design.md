# Re-review Reconciliation on Follow-up Commits (Phase 2) — design draft

**Status:** DRAFT — brainstorming, not yet a spec. Captured before a context compact so the design
survives. Next step: run it through `superpowers:brainstorming` to pin the open questions, then a
proper spec + plan.

**Date:** 2026-07-18

## Problem

When a fix commit is pushed to an already-reviewed PR, the current behavior is a **blind full
re-review**:

- Comment idempotency is keyed by `(review_id, commit, anchor_key)` — the commit is in the key, so
  it only dedupes re-delivery of the *same* commit's webhook, **not** across commits. Result: a
  still-open issue is **re-posted as a duplicate** on the new commit (especially on untouched lines,
  where GitHub also keeps the old comment live).
- Nothing detects that an issue was **fixed** — `resolveThread` / `FindingReassessed` exist only in
  design docs, never implemented. Fixed issues' threads stay open; on the dashboard they drop into
  General discussion (finding gone → no matching `path:line`).
- A re-review is **stateless w.r.t. the previous run** — it never diffs old findings vs new ones.

We do NOT want to test/keep this. Follow-up commits are fixes; the reviewer should understand them.

## The diff question — full state vs. incremental

Both, for different roles:

- **Review target = the full PR diff (base → new head).** Correctness needs the complete current
  state — a fix in one place can break another, and the posted review must reflect the PR as it
  stands. Do NOT switch to reviewing only the follow-up commit's diff.
- **Reconciliation lens = the incremental diff (prior-reviewed head → new head).** That is exactly
  "what did the author change in response to our comments" — the input for judging whether each prior
  finding was addressed, and for de-duplication.

So the follow-up review becomes a **reconciliation pass, not a blind re-review**: anchored on the
incremental diff + the prior findings, with the full diff as reference context.

## Core design: the reconciliation review

On a follow-up commit, feed the LLM **memory + the delta** and let it decide what changed.

**Inputs to the LLM:**
- **Prior findings**, each with its **linked thread transcript** (the discussion — including any human
  pushback or a bot concession). We already have the finding↔thread mapping (`review_thread`
  `path/line` → `threadRef`) and can re-fetch transcripts (`fetchThread`).
- **The incremental diff** (prior head → new head).
- Surrounding code / full diff as reference.

**LLM task (structured output):**
- For each prior finding → `resolved` | `still-open` (with *what is still missing*) | `superseded`.
- Plus any genuinely **new** issues introduced by the changes.

**Deterministic actions on the verdict:**
- **Resolved** → reply in the *existing* thread ("✓ Looks fixed in `<sha>` — …") and resolve the
  thread where the SCM supports it. No new comment.
- **Still-open** → **reply in the existing thread** ("Still an issue after `<sha>`: …") — continue the
  conversation, never a duplicate top-level comment.
- **New** → a fresh inline comment.
- Summary updated with resolved / remaining / new counts.

Result: **no duplicates, fixed issues closed, open issues keep one coherent thread, only new problems
get new comments.**

## Why LLM judgment, not deterministic matching

LLM findings vary run-to-run (same code can be phrased differently, split, or merged), so matching
"new finding == old finding" by text/anchor is brittle. The LLM is the right tool for "is this the
same concern, and is it fixed?" — hence feed it the prior context + the changes rather than diffing
LLM outputs. Deterministic anchor matching is only good enough for a cheap Phase-1 dedup.

## What we already have vs. new work

- ✅ Findings stored per run; finding→comment/thread mapping; thread re-fetch (`fetchThread`); a
  follow-up LLM prompt pattern; supersede/stale-run lifecycle; ADR-011 "re-fetch by reference" fits.
- 🔨 New: snapshot each run's findings **with their posted comment ids**; compute the **incremental
  diff** (a new SCM capability — compare two commits); the **reconciliation prompt + structured
  verdict**; change the worker from "post all findings" to "**post the reconciled deltas**"; wire
  **`resolveThread`** (specced in the S8 doc, never built — GitHub GraphQL-only, GitLab/BB REST).

## Phased path

1. **Phase 1 (cheap, stops the bleeding):** deterministic anchor dedup — before posting a finding on
   a re-review, skip it if a prior open comment already sits at that `path:line` (reuse the existing
   thread). Kills obvious duplicates, no extra LLM cost. Does NOT detect fixes.
2. **Phase 2 (the real thing):** the LLM reconciliation above — fixed-detection, thread continuation,
   resolve. This is where we're headed.

Recommendation: design straight for Phase 2 (it's the product's point — an AI reviewer that
understands your fixes); Phase 1 is a legitimate quick win if duplicates need to stop immediately.

## Open questions to resolve in brainstorming

- Auto-**resolve** threads, or only reply (leave resolution to the human)?
- A human pushed back on a finding and did **not** fix it — reply again, or stay quiet?
- Does Phase 1 ship first, or go straight to Phase 2?
- Where the reconciliation runs (new EVENT-MODEL slice — "S-reconcile") and its ADR (re-review posts
  **deltas**, not the full finding set — a change to today's PostComments contract).
- Snapshot storage: per-run findings + comment ids — new table or extend existing read models?
