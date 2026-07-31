# Review detail: context visibility, card order, and a readable event stream

**Date:** 2026-08-01
**Status:** approved, ready for planning

## Problem

The review detail page has five sections — Findings, General discussion, Event stream, Model usage,
Metadata — and three of them work against the operator:

1. **Model usage sits above Metadata.** Model usage grows by one row per LLM call, so on a review
   that has been re-run or has held a conversation it pushes the fixed, frequently-read Metadata
   card off the bottom of the screen.
2. **Nothing on the page shows the context a review was given.** The pipeline resolves issue and
   ticket references into `ContextItem`s and injects them into the prompt, but the only trace on
   screen is a bare `ContextAssembled` event with the text "context assembled" — no count, no source,
   no content. When a review cites a requirement, there is no way to see where it came from.
3. **The event stream grows without bound, oldest first.** Every re-run appends below the last, so
   the newest events — the ones you are looking for — are furthest from the top.

## Decisions

Four questions were settled before design. Each had a real alternative; the reasoning matters more
than the choice.

### Context items come from the stored blob, not a live re-resolve

`worker.context_blob` already holds the exact `AssembledContext` each review was given, Tink-encrypted
with `AAD = reviewId`. Reading it is free, immune to third-party rate limits and outages, and answers
the question that actually matters when reading a review: **what did the reviewer see?**

Re-resolving live would show the ticket's current state, which can silently disagree with what the
model was given — the worst outcome for a page whose purpose is explaining a review's reasoning.

Cost accepted: a review that resolved no items has no blob and shows an empty card, and edits made to
a ticket after the review are not reflected. Both are correct for an as-reviewed view.

### Comments are split in the UI on the `Recent comments:` marker

`ContextItem` is `(kind, title, body, uri)`. Comments are not structured — all three issue providers
append a literal `"\n\nRecent comments:"` block into `body`. (Confluence emits none; its pages carry
no comments in this implementation.)

The UI splits on that marker. The alternative — adding a `comments` field to `ContextItem` — is the
better structure but changes a published Apache-2.0 SPI record, and would either duplicate the
comments (once in `body` for the prompt, once in the field) or change what the model is sent. The
prompt content was validated end to end on 2026-07-31 against real GitHub and GitLab issues; a UI
improvement is not a reason to perturb a validated model input.

Cost accepted: the UI depends on a rendering convention living in three separate adapter modules,
across a language boundary where no shared constant is possible. Mitigated by a test per provider
asserting the marker is emitted, so the coupling breaks loudly rather than degrading in silence.

### The PR description is fetched live, not persisted

The description is not stored: `review_status` carries `title`, `source_branch`, `commit_sha` and
`html_url`, and `DiffFetched` carries references but no body. Persisting it would mean a migration
plus a nullable field on a wire-format event.

It is fetched on expand instead, through the SCM credential the orchestrator already resolves.
Nothing new is stored anywhere for this feature.

Cost accepted: one SCM call per expand, a loading state, a failure mode when the token or network is
down, and text that may differ from what the review parsed if the description was edited since. The
UI labels it as current rather than as-reviewed, so the two sources are never confused.

### Runs read newest-first; events inside a run stay chronological

Runs are ordered newest-first so the latest is at the top with no scrolling, and only the newest is
expanded. Inside a run, events keep pipeline order — `ReviewRequested` → `DiffFetched` →
`ContextAssembled` → `ReviewGenerated` → `CommentsPosted`.

Fully reversing would put the most recent event on the first line, but each run would then read
backwards, inverting cause and effect exactly when you are tracing where a run stalled.

## Design

### Card order

`ReviewDetail.tsx`'s right column renders `metaCard(r)` then `usageCard(r)`. The left column is
unchanged.

### Context section

Two sources with different costs, so two endpoints.

**Items — `GET /api/review-context/{reviewId}`, served by the worker.**

Returns `ReviewContextView { items, contributingSources, missingSources }`. The worker reads
`worker.context_blob` by `review_id` and decrypts with its own keyset. `PostgresBlobStore` gains a
`getByReview(String reviewId)` — it has `put`, `get(BlobRef)`, `delete` and `deleteByReview` today,
and no caller outside the worker knows a review's `contextRef` (it rides on `ContextAssembled` into
`GenerateReview` and is never projected), which is why `review_id` is a first-class column on that
table.

The worker serves this rather than the orchestrator reaching across schemas, following the pattern
the attention panel set: each service answers for its own schema over its own HTTP surface, and the
UI merges. The worker has no REST resources today, so this is new surface, but Quarkus makes it
small.

The alternative — the orchestrator reading and decrypting `worker.context_blob` directly — is fewer
moving parts and has partial precedent (`ReviewProjection` deletes from that table). It was rejected
because that delete is a `WHERE review_id = ?` needing no knowledge of blob semantics, whereas a read
would move the worker's AAD convention and payload shape into a second service.

Cost: one Vite proxy line, `/api/review-context` → worker, listed before the catch-all `/api` →
orchestrator, exactly as `/api/webhook-repos` → gateway already is. Any reverse proxy fronting a
non-dev deployment needs the same rule.

**Description — `GET /api/reviews/{workspace}/{slug}/{pr}/description`, served by the orchestrator.**

Fetches the pull request through the stored SCM credential and returns its body.
`ManualRegisterResource` already calls `diffSource.fetchPullRequest(repo, pr)`, so the capability
exists. Failures surface honestly — a 404 as a 404, a rejected credential as such — never as an empty
description, which would read as "the PR has no description".

**Rendering.** A new `components/ContextCard.tsx`. Per item: kind, title and link always visible;
body behind a toggle; comments behind a second toggle, collapsed by default. The card states that it
shows context as captured for this review. The description is a separate expandable block labelled as
current.

Empty case: a review with no blob shows an empty card explaining that no references were resolved —
not an error, since running without context is the normal path when no provider is configured.

### Event stream

Events group into runs by splitting on `ReviewRequested`. Runs render newest-first, each collapsible,
only the newest expanded. The existing "Re-run N" numbering is computed in original chronological
order so a run keeps its identity after reordering.

### Where the code goes

`render.tsx` is 904 lines, well past the 250-line component guideline. The context card goes in its
own file, and `eventsCard` moves to `components/EventStream.tsx` as part of changing it — grouping
and collapse state will grow it further. Nothing else in `render.tsx` moves; this is not a general
refactor.

## Testing

**UI (vitest).**
- Metadata renders before Model usage.
- Context card renders items from the endpoint; body and comments both collapsed by default;
  expanding comments reveals the text after the marker.
- An item whose body has no `Recent comments:` marker renders with no comments toggle.
- Empty context renders the explanatory empty state, not an error.
- Runs render newest-first with only the newest expanded; a single-run review shows no collapsed
  siblings.
- Description block fetches on expand, not on mount.

**Java.**
- Worker endpoint returns the stored items for a review, and an empty result when no blob exists.
- Worker endpoint does not return a blob belonging to another review.
- Orchestrator description endpoint maps an SCM 404 and a rejected credential to honest statuses.
- One test per issue provider asserting the `Recent comments:` marker is emitted, guarding the UI
  split.

## Out of scope

No migration, no event-contract change, nothing newly persisted. Structured comments on `ContextItem`,
a rendered-prompt view, and per-source attribution on the timeline all remain open — the last two are
tracked in `techdebt/`.
