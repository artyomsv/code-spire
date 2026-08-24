# Per-repo prompts, honest prompt preview, default migration, and conversation-derived findings

**Status:** draft. Not approved, not implemented.
**Closes:** ROADMAP items **E16** (prompt management follow-ups) and **E17** (conversation-derived
findings), plus `techdebt/global/4-4-conversation-derived-findings.md`.
**Builds on:** ADR-010 (single-writer aggregate), ADR-011 (diffs never persisted, findings in read
models), ADR-019 (re-review reconciliation), ADR-022 (operator roles), ADR-025 (spend caps).

Four deliverables in one document because they meet at one place: E17's command has to reach a
thread, and the routing that would carry it there is broken in three different ways.

---

## Problem

Two roadmap items, both recorded as open since the features that created them shipped.

**E16.** Prompt management shipped **global-scope only** (2026-07-23). Three questions were deferred
and named in the roadmap: per-repo prompt scope, preview against a sample diff, and a migration story
for evolving built-in defaults. Today a customized template's only escape hatch is
reset-to-default, which discards the customization wholesale.

**E17.** The conversational-reply feature is **answer-only**. `FollowUpWorker` fetches the thread,
calls the LLM, posts the reply. Findings are produced *exclusively* by reviewing the diff
(`ReviewGenerated` → `CommentsPosted`). A follow-up answer never becomes a tracked finding, so it
does not count toward finding or blocker totals, does not appear in the Findings card, and is
invisible to reconciliation.

The debt entry records how it was found: on `spire-test#11` a human opened a thread on an unrelated
line and asked `@code-spire-bot do you think variable name is ok?`. The bot answered and **agreed
there was a real issue**. The finding count did not move. Nothing was filed.

---

## The finding that reshaped this design

`/finding` must be typed inside a thread. Chasing that through the three ingresses found that a
slash command in an inline thread produces **four different outcomes across three SCMs**, and that
`ManualCommandReceived(repo, prId, command, args, author)` carries neither a thread ref nor a
location — so on two of the three providers the thread context is *discarded at the ingress*.

| SCM | Handler | `/review` in an inline thread | `/unknown` in an inline thread |
|---|---|---|---|
| GitHub | `reviewCommentReply` — **no `/` check at all** | `AuthorReplied`; the LLM answers it as a question | `AuthorReplied` |
| GitLab | `note()` — `/` check covers `DiffNote`s | `ManualCommandReceived`, thread ref **discarded** | dropped silently |
| Bitbucket | `comment()` — `/` check runs first | `ManualCommandReceived`, thread ref **discarded** | falls through to `AuthorReplied` |

Two live defects fall out, neither of which has anything to do with E17:

- **`/review` in a GitHub inline thread does not re-review.** `GitHubIngress.issueComment` checks
  `text.startsWith("/")`; `GitHubIngress.reviewCommentReply` does not. The command is answered as a
  question — and **pays for an LLM call** to do it. The operator sees a conversational reply where
  they asked for a re-run.
- **`/foo` in a GitLab thread vanishes.** `GitLabIngress.note` returns `List.of()` for an
  unrecognised command; Bitbucket falls through to `AuthorReplied` and GitHub never checked. Same
  text, three outcomes.

This is the class of defect CLAUDE.md already names and `spire-arch` explicitly cannot catch: a
provider-neutrality leak that **carries no provider name**. The build check scans source text for
`"bitbucket-cloud"`; no scan can see "GitHub checks for `/` on one webhook surface and not the
other." It is found by reading the *paths*, which is how the two Criticals in the ADR-023 review
round were found as well.

Fixing the routing is a **prerequisite**, not scope creep: `/finding` cannot work at all without a
thread ref on the command event.

---

## 1. Command routing normalization

### 1.1 The event grows two nullable components

```java
record ManualCommandReceived(RepoRef repo, long prId, String command, String args,
                             Author author, ThreadRef threadRef, ThreadLocation location)
        implements IntegrationEvent {
```

Both null for a top-level command, which is every command that exists today. `ManualCommandReceived`
is a Kafka wire type (polymorphic JSON on a sealed hierarchy), so this takes the additive treatment
the codebase already uses for exactly this: **appended components, old constructors retained**. The
precedent is `AuthorReplied`, which grew `mentions` and then `location` this way and carries four
constructors as a result.

The ADR-013 contract-compat gate must pass on the round-trip and snapshot tests. Note the documented
limitation in `techdebt/spire-contract/3-2-…`: `ContractSchemaSnapshotTest` renders a nested record
component as `name: TypeName` and **does not recurse**, so it would not describe a change *inside*
`ThreadLocation`. That is not a problem here — the change is at the top level, which the snapshot does
cover — but the plan must not treat a green snapshot as proof of more than it checks.

### 1.2 One rule on every comment surface

Every comment surface on all three SCMs runs the same check, in the same order:

1. Text starts with `/` **and** the first token is in the command allowlist → `ManualCommandReceived`,
   carrying whatever thread ref and location that surface has.
2. Anything else, `/`-prefixed or not → `AuthorReplied`, unchanged.

Rule 2 is what fixes GitLab's silent drop: an unrecognised `/foo` is a comment, not a command, and a
comment engages the bot under the existing conversation policy. Bitbucket already behaves this way;
GitLab and GitHub change to match.

Per-SCM work:

| SCM | Change |
|---|---|
| GitHub | `reviewCommentReply` gains the command check (it has none). `issueComment` unchanged except for passing nulls explicitly. |
| GitLab | `note()` passes `discussion_id` and `location(attrs)` onto the command event; unrecognised `/foo` falls through to `AuthorReplied` instead of `List.of()`. |
| Bitbucket | `comment()` computes `threadRef`/`location` **before** the command branch and passes them through. |

### 1.3 The allowlist gets one home

`Set.of("review")` is currently triplicated across `GitHubWebhookResource`, `GitLabWebhookResource`
and `BitbucketWebhookResource`. It becomes one shared constant, composed the way
`WebhookProviders.SUPPORTED_TYPES` is — from each endpoint's own constant rather than a list core
holds. Adding `"finding"` then happens once.

### 1.4 The two live defects get their own commits

Following the repo's rename-commit discipline (`~/.claude/rules/dto-naming.md`: renames go in their
own commit so they stay visible in review), the GitHub `/review`-in-thread fix and the GitLab
silent-drop fix are **separate commits from the E17 feature work**, each with a test that fails
before it. Buried in a feature branch they are invisible; on their own they are two bug fixes an
operator can read.

---

## 2. E17 — conversation-derived findings

### 2.1 Trigger: an explicit human command

`/finding [severity] [note]` typed in an inline thread.

Chosen over a structured LLM signal, a separate classifier call, and a propose-then-confirm hybrid,
for four reasons:

- **No change to the locked FOLLOWUP contract.** The contract is deliberately free-text, and this
  project has already shipped a defect caused by one of its details — the locked wording said *"no
  markdown fences"*, so the model indented code and Bitbucket rendered it as prose. Adding a
  structured side-channel to a reply that must stay clean is the same risk again.
- **It costs nothing.** No LLM call, matching `NotifyTurnCap` and `NotifyArchived`, both fixed-text
  commands carrying no LLM credential. ADR-025 has just finished bounding paid paths; this adds none.
- **The gate already exists and was built for this.** `IntegrationSaga.onManualCommand` puts the
  per-provider author allowlist **ahead of the command switch**, and says why in a comment: *"so a
  future command cannot be added below it and arrive ungated — which is exactly how this one got
  in."* This is that future command.
- **Provenance is honest.** A finding is a claim about the code. "A human read the exchange and said
  file it" is stronger provenance than "a model inferred from its own reply that it had found
  something" — the same instinct that keeps fabricated data out of every other user-visible surface.

Accepted cost: it does not fire on its own, so the observed case needs someone who knows the command
exists. Mitigated in §2.7.

### 2.2 Severity

`/finding` → **MINOR**. `/finding major`, `/finding blocker`, etc. → that severity.

Parsed from `args` using the `/command args` grammar all three ingresses already split on
(`text.substring(1).split("\\s+", 2)`). An unrecognised first token is **not** a severity — it is
treated as the start of the note, so `/finding this shadows the field` files a MINOR with that note
rather than refusing on a typo.

MINOR is the honest floor: a human thought it worth filing, but nobody asserted it blocks the merge.
Defaulting upward would let a passing remark gate a pull request.

### 2.3 Anchor: inline threads only

Resolution order:

1. `ManualCommandReceived.location` — the provider reported it on this comment.
2. `review_thread.(path, line)` for the conversation **root** — recorded by `markThreadLocation`
   since V17/V27, for human-started inline threads as well as the bot's own.
3. Neither → **refuse with a reply**.

The debt entry treats the anchor as one of the hard parts. It is smaller than it looks, because V27
was added for a related reason and left the data in place: `review_thread` already carries `path`,
`line` and `is_finding`, the last one precisely so a human-started thread on a line can be
distinguished from a finding's thread on the same line.

**A refusal is spoken, not silent.** `/finding` on a summary or top-level comment replies:

> `/finding` needs to be on a specific line. Open an inline comment on the line in question and run
> it there.

This project has twice shipped a silence that read as a lost webhook — the turn cap posted nothing
when reached, and a dead tunnel during the Mode G parity run produced an identical symptom. A
command that does nothing and says nothing is the same failure.

This refusal is *not* the authorization refusal from §2.5. The two differ deliberately: an
unauthorized author is met with silence (a reply confirms to a prober that the command is wired, and
costs an outbound comment per probe), while an authorized author who used the command in the wrong
place is told how to use it correctly.

### 2.4 Where the finding lands

**`open_findings_json`. Not `findings_json`.**

`findings_json` is what the review of commit X produced — a truthful record of one model call — and
`recordPosted` copies it into `posted_findings_json` as the run snapshot. Writing a conversation
finding there corrupts a record of what the model said.

`open_findings_json` is already *defined* as the carry-forward baseline: "this round's new findings
UNION every prior finding still open after this round's verdicts" (V20, ADR-019 refinement). A
conversation finding is exactly that — something now open that the next round must reconcile against
and exclude from re-reporting. Landing it there means it flows into `priorRunFor` → `PriorRun` →
`GenerateReview`'s exclusion list and the reconcile set **with no new plumbing at all**.

### 2.5 The aggregate write

The debt entry says the finding must "land in `ReviewLifecycle`'s finding set". **There is no such
set.** `ReviewState` holds `status`, `currentCommit`, `reviewedCommits`, `summaryCommentId` and
`threads`; ADR-011 puts findings in read models and keeps only a digest in the aggregate
(`ReviewOutcomeRecorded(commit, findingsCount, summaryDigest)`).

So the split follows the one already in use:

```java
// spire-contract/command/RecordCommand.java
record RaiseConversationFinding(ThreadRef threadRef, String path, int line,
                                Severity severity, String triggeringCommentId)
        implements RecordCommand {}

// spire-contract/event/DomainEvent.java
record ConversationFindingRaised(ThreadRef threadRef, String path, int line,
                                 Severity severity, String triggeringCommentId)
        implements DomainEvent {}
```

The domain event carries **anchor and severity only** — non-sensitive, replay-safe. The finding's
message may quote source code, so it goes to the encrypted read model like every other finding
message (DATA-MODEL §5).

`ReviewState` gains **no findings field** — the count lives in the read model, which is where the
Findings card, the blocker count and the reviews-list columns already read it from.

It does gain **one idempotency field**, `Set<String> raisedFindingComments`. `ManualCommandReceived`
arrives at-least-once over Kafka, so without a key held by the single writer a redelivered webhook
appends the finding twice — the worker's claim guards the *SCM post*, not the aggregate append.
`ReviewState`'s own javadoc says it holds "decision-relevant state (**idempotency** + completion)"
and `reviewedCommits` is the precedent: a set of ids consulted by `decide` to return an empty event
list. `decide(RaiseConversationFinding)` returns `List.of()` when
`raisedFindingComments.contains(triggeringCommentId)`, exactly as `decideRequestReview` does for an
already-reviewed commit.

The event does **not** overwrite `ReviewOutcomeRecorded`'s `findingsCount`. That number answers "how
many findings did the review of this commit produce", and a conversation finding did not come from
that review. Two different questions, two different numbers.

### 2.6 Dedup, idempotency, and the guards already in place

**Dedup needs no new mechanism.** `dedupeByAnchor` already enforces one-anchor-one-tracked-concern
and merges a group's messages with `"; also: "`, keeping the first entry's severity and the first
non-null thread ref. A `/finding` on a line that already has an open finding therefore **merges**
rather than double-counting. This is free — and precisely because it is free it gets an explicit
test, since nothing in the E17 code would fail if the merge silently stopped working.

**Idempotency**: claim on `(reviewId, threadRoot, "finding:" + triggeringCommentId)`, the same shape
as `followup:`. A redelivered webhook files nothing twice.

**Two guards already cover this path and need no work**, both because they sit ahead of the switch:

- **Archived reviews.** `IntegrationSaga` checks `archivedReviewIdOf(event)` at the top of dispatch,
  so `/finding` on an archived review is refused and (per ADR-024's `noticeTriggerOf` allowlist)
  may trigger the archived notice. Nothing new.
- **Author allowlist.** `onManualCommand` gates before branching on the command name.

**Spend caps do not apply.** `/finding` makes no LLM call. It is not a `SpendGate` decision site.

### 2.7 Confirmation reply

```java
record ConfirmFinding(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                      String triggeringCommentId, Severity severity, String path, int line,
                      String scmCredential)
        implements ActionCommand {}
```

No LLM credential — fixed text, like `NotifyTurnCap` and `NotifyArchived`:

> Filed as **MINOR** at `Foo.java:44`. It will be tracked with the review's other findings and
> reconciled on the next push.

This is also the discoverability answer to §2.1's accepted cost. Two further placements, both cheap:

- The **turn-cap notice** already says "@-mention me if you still need something here." It gains a
  clause pointing at `/finding`, since a capped thread is exactly where a human has been discussing
  something worth filing.
- The **review summary comment** footer names the command once.

Neither costs a call, and both put the command in front of the person in the thread rather than in
documentation they would have to go looking for.

### 2.8 What a conversation finding does downstream

| Surface | Behaviour |
|---|---|
| Findings card | Appears with its anchor and severity, marked as raised in conversation |
| Open-finding count | Counted — it is open |
| New vs carried-over split | Counts as **new** in the run that raised it, carried-over after |
| Blocker count | Counted when severity is BLOCKER |
| Reconciliation | Reconciled like any other prior finding; can be RESOLVED, STILL_OPEN, etc. |
| Exclusion list | Excluded from re-reporting, via the same `PriorRun` path |
| Thread resolve | Its thread resolves on a closing verdict, like a finding thread |

The UI marks the origin. `ReviewDetail.FindingView` gains an origin discriminator so a reader can
tell "the model reported this on the diff" from "a human filed this from a discussion" — different
provenance, and the operator should not have to guess which.

---

## 3. E16.1 — per-repo prompt scope

### 3.1 Why this is not already answered by `.codespire`

`docs/REPO-RULES.md` states the case against building this, in its own words: *"the operator's prompt
template (Settings → Prompts) is global … Repository rules are what lets each repository be specific
without the operator maintaining a template per repo."*

That is true and stays true. Per-repo prompts are a **different instrument**, not a duplicate:

| | `.codespire` | per-repo prompt |
|---|---|---|
| Owner | contributors, via merge to the target branch | operator, via the dashboard |
| Trust slot | fenced **untrusted data** | **instructions** |
| Can change | rules *text*, additively | persona, priority order, which variables appear, structure |
| Review of a change to it | appears in the PR diff | admin-only, audited by role |

The deciding case: an operator who wants a Terraform module reviewed without the correctness-first
priority order that suits a Java service **cannot** express that in `.codespire`, because rules are
additive text in a fenced slot and the priority order lives in the persona. That is a structural
change, and structure is what the prompt template owns.

Scoped narrowly on that basis: **the same three kinds, no new kinds, a scope selector rather than a
per-repo page tree.** This is the sub-item to cut first if the plan needs trimming.

### 3.2 Schema and resolution

`prompt_template` re-keys from `kind TEXT PRIMARY KEY` to a composite:

```sql
-- V34
ALTER TABLE prompt_template ADD COLUMN scope TEXT NOT NULL DEFAULT '*';
ALTER TABLE prompt_template DROP CONSTRAINT prompt_template_pkey;
ALTER TABLE prompt_template ADD PRIMARY KEY (scope, kind);
```

`scope` is `'*'` for global, else `workspace/slug`. Existing rows take the default and remain global —
no behaviour change on upgrade.

Resolution becomes three levels, most specific wins:

```
repo row (scope = 'workspace/slug')  →  global row (scope = '*')  →  PromptCatalog default
```

`PromptRegistry.effective(kind)` becomes `effective(kind, scope)`; `WorkerPromptTemplates.forKind(kind)`
becomes `forKind(kind, repo)` and keeps returning `null` when neither level is customized, so the
common case adds nothing to the command.

**Whole-template override, not per-field merge.** A repo row replaces both `system` and `body`. A
per-field merge would mean an operator editing the global persona silently changes the effective
prompt of every repo that overrode only the body — a spooky-action-at-a-distance edit to the
instructions a review runs under. Same reasoning as §4's refusal to auto-merge.

### 3.3 Validation and the locked guards are unchanged

`PromptValidation.validate` runs identically per scope. The locked system suffix — security clause
plus output contract — is appended by `PromptRenderer` regardless of scope, so a repo override can no
more edit away the injection boundary than a global one can.

### 3.4 UI

Settings → Prompts gains a scope selector at the top: **Global** plus one entry per repository the
orchestrator has seen, read from its own `review_status` table, with free-text entry for a repo not
yet reviewed. Deliberately **not** `webhook_repo`, which is the more obvious source and is
**gateway-owned** — reading it means a cross-service call for a settings dropdown, and ADR-022 gives
the gateway its own URL prefix and session precisely so the two are not casually coupled. A repo
nobody has reviewed is also one there is nothing to preview against (§4). The list shows which
kinds are overridden at the selected scope, and — when a repo scope is selected — which are
inherited from global versus the built-in default. The effective template must be obvious without
clicking through: a reader who cannot tell at a glance which text a review will actually use has a
worse tool than the global-only one.

---

## 4. E16.2 — preview against a real review

### 4.1 The rule this has to satisfy

`PromptValidation.preview` is deliberately fabrication-free: it replaces `{{diff}}` with
`«diff inserted here»`, and its javadoc says so — *"no fabricated data"*. A sample diff shipped with
the product is exactly what `~/.claude/rules/no-synthetic-data.md` forbids: a plausible-looking
artifact rendered in the UI as though it were real input.

The rule's own protocol gives the answer at step 2 — *fetch live data and pipe it through the
feature*. So: **preview against a real review the deployment already has.**

### 4.2 Design

`POST /api/prompts/{kind}/preview` gains an optional `reviewId`.

- **Absent** → today's annotated preview, unchanged. Needs no data, works on a fresh deployment.
- **Present** → the orchestrator loads that review's real title, description and commit, re-fetches
  its diff by commit (ADR-011 — diffs are never persisted, so this is a live fetch through
  `ProviderClients`), loads its assembled context blob if one exists, and renders the candidate
  template through the **real `PromptRenderer`**.

Two properties a sample diff could not give:

- **Honest.** Every byte came from a pull request the operator already has.
- **It exercises the real renderer**, so token clipping (`maxTokens` per variable — 24,000 for the
  REVIEW diff, 4,000 for `context` and `prior_findings`) and untrusted-data fencing are *visible*.
  The annotated preview shows neither, so an operator cannot currently see that a large PR's diff is
  clipped before it reaches the model, nor where the fence markers land around each variable. The
  clip is estimated with the chars-per-token heuristic in `spire-diff`, not a real tokenizer
  (`docs/RESEARCH.md` §4), so the preview should show the *estimate that will actually be applied*
  rather than a recomputed one — a preview that disagreed with the renderer would be worse than none.

### 4.3 Placement and cost

`PromptValidation` lives in framework-free `spire-contract` and must stay there
(`PureModulesAreFrameworkFreeTest`). The real-diff render needs `DiffSource`, brokered credentials and
the blob store, so it is a **new orchestrator-side collaborator** — not a change to `PromptValidation`.

Cost and safety: one diff fetch and one blob read, **no LLM call**, so it is free and needs no
`SpendGate` decision. Already admin-only via `PromptResource`'s class-level `@RolesAllowed`. A review
picker on the preview panel lists recent reviews; a failed diff fetch degrades to the annotated
preview with the reason shown, rather than an empty panel.

---

## 5. E16.3 — the default-migration story

### 5.1 Root cause

A customized template is a fork of the built-in default with **no recorded common ancestor**.
`prompt_template` stores `system_text` and `body_text` and nothing about which default they came
from. That is the whole reason the only answer today is reset-to-default: with no ancestor there is
nothing to compare against, so the improvement to the shipped prompt and the operator's customization
cannot be told apart.

### 5.2 Store the ancestor

```sql
-- V33
ALTER TABLE prompt_template ADD COLUMN base_system_text TEXT;
ALTER TABLE prompt_template ADD COLUMN base_body_text   TEXT;
```

Written on save: the built-in default **as it stood at that moment**. Then
`PromptCatalog.defaultTemplate(kind) != (base_system_text, base_body_text)` answers "has the shipped
prompt moved since you customized this."

### 5.3 What the operator sees

On the Prompts list, a badge on any kind whose ancestor has drifted. On the detail page, the **diff of
the default** — ancestor → current shipped — so the operator reads *what changed in the built-in
prompt*, next to their own text. Two actions:

- **Take the new default** — equivalent to reset. Discards the customization, which is what it says.
- **Keep mine** — re-stamps `base_*` to the current default, dismissing the notice without touching
  the customization.

**No auto-merge.** Silently merging the instructions a review runs under is the class of change this
project consistently refuses, and a three-way merge of prose has no reliable conflict marker. The
operator reads the change and decides.

### 5.4 Not an attention-panel row

Tempting, because the panel's contract is "a condition true right now that fixing removes" and this
qualifies. But the panel deliberately excluded `CREDENTIAL_UNVERIFIED` on the grounds that it is
*"wallpaper — it lives inline on the settings pages"*, and nothing here is blocking: a drifted default
means the operator is missing an improvement, not that reviews fail. Inline badge, same reasoning.

### 5.5 Existing rows

Every row written before V33 has `base_* = NULL`, meaning **unknown ancestor** — not "up to date".
The UI says *"customized before default tracking began"* and offers the same two actions, with the
default-diff view unavailable because there is genuinely nothing to diff against. Showing a
confident "up to date" for a row whose ancestor was never recorded would be a fabricated claim about
state, which is the same rule as §4.1 applied to a badge instead of a diff.

---

## 6. Testing

Beyond per-unit coverage, the assertions worth naming because they are the ones that would otherwise
pass vacuously:

**Routing (§1)** — a table test running the *same* `/finding` payload through all three ingresses and
asserting an identical event shape. The defect this spec found is that they differed; only a test
that compares them can keep them from differing again. Plus one test per live defect: `/review` in a
GitHub inline thread produces `ManualCommandReceived`, and `/foo` in a GitLab thread produces
`AuthorReplied`.

**Anchor (§2.3)** — `/finding` on a summary thread *replies*, and the reply text is asserted. A test
that only asserts "no finding was filed" would pass on the silent-drop bug this explicitly rejects.

**Dedup (§2.6)** — `/finding` on a line that already has an open finding yields **one** tracked
concern, not two. This is inherited behaviour, so nothing in the new code fails if it regresses.

**Reconciliation (§2.8)** — a conversation finding raised in round 1 appears in round 2's `PriorRun`,
is excluded from re-reporting, and can be verdicted RESOLVED. This is the whole point of choosing
`open_findings_json` over `findings_json`; without this test that choice is unverified.

**Scope resolution (§3.2)** — repo beats global beats default, and the *absence* of a repo row falls
through rather than returning empty. Both directions: a test that only checks "repo wins" passes on
an implementation that ignores global entirely.

**Preview (§4)** — the real-review preview shows clipping when the diff exceeds `maxTokens`. Asserting
only that "some text came back" would pass on a preview that skipped the renderer.

**Migration (§5.5)** — a NULL-ancestor row reports *unknown*, not *up to date*. The false-negative
direction is the one that misleads.

---

## 7. Sequencing

1. **Routing normalization** (§1) — prerequisite for everything in §2, and independently valuable
   since it fixes two live defects. Ships alone.
2. **E17** (§2) — depends on 1.
3. **Preview against a real review** (§4) — independent of 1–2, smallest of the E16 three.
4. **Default migration** (§5) — independent.
5. **Per-repo scope** (§3) — last, and the first thing to cut if the plan needs trimming. It is the
   only sub-item whose need is argued rather than observed.

---

## 8. Out of scope

- **Auto-merging an evolved default into a customization** (§5.3) — refused, with reasoning.
- **A structured LLM signal for findings** (§2.1) — the trigger is a human command. If a future
  design wants the model to propose candidates, the `FollowUpAnswer` javadoc's *"Plan 2 adds a
  structured verdict"* seam is where it goes, and it can be added without disturbing anything here.
- **Per-repo scope for anything other than prompts** — conversation levels, models and providers keep
  their current scoping.
- **New prompt kinds.** Three kinds, at both scopes.
- **Waived-nit tracking.** The roadmap notes it "sits closest to E17" and deliberately excludes it as
  a feature rather than debt: it needs a store, a wire field and a prompt slot. Unchanged by this
  spec, and the `/finding` path is not a back door into it.
