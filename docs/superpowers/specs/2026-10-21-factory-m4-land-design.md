# M4 slice 2: land — design

Status: draft for operator review, 2026-10-21. Branch `feat/m4-land`. Part of #115.

## 1. Intent

Today an item builds, verifies, delivers a pull request, gets a GitHub review with a verdict, and
stops at the land gate. Approving that gate records the answer and changes nothing on GitHub: the
item ends at `land / capability_unavailable / land_capability_unavailable` (live on #43,
`M4-VERIFY-ACCEPTANCE.md`). The screen then offers "Re-admit", which starts a new generation and
would build and propose the same work again.

This slice makes `land: approve` real. When the operator approves land, the factory merges the
delivered pull request, deletes its branch, tells the ticket, and finishes the item. When GitHub
refuses the merge because of the code (a failed check or a conflict), the factory fixes the branch
with the harness, the reviewer reviews again, and land waits for the operator again. When the cause
is not code, or the attempts run out, the item goes to the operator with GitHub's reason.

**Why the ticket is not closed by default (operator, 2026-10-21):** testing happens after the merge,
and every organisation and tracker has its own lifecycle. A merged pull request is not finished work.
This is the same rule the operator applies to this project's own issues.

## 2. Goals

1. An approved land on a GitHub item merges the delivered pull request with the repository's chosen
   merge method, deletes the item's branch, applies the repository's ticket rule, and completes the
   item with a recorded merge.
2. Per repository settings: merge method, ticket rule after land, and fix attempts before land.
3. A refusal caused by code (failed checks, merge conflict, branch behind a required-up-to-date base)
   starts a bounded fix loop on the same pull request. Each fix ends at a new land gate.
4. A refusal not caused by code, or an exhausted fix budget, hands the item to the operator with the
   reason GitHub gave.
5. A pull request merged or closed by a person on GitHub is recognised, not treated as takeover.
6. An item that has landed cannot be readmitted, and the screen does not offer it.

## 3. Non-goals

- `land: auto_if_green`. It stays unwired (ROADMAP "Not built": needs a risk scorer, protected-path
  enforcement and a kill switch). It keeps today's `capability_unavailable` result.
- Land on GitLab or Bitbucket. Live item publication is GitHub only today; land follows it. Those
  forges keep `land / capability_unavailable` with a reason that names the forge.
- Moving a ticket to an arbitrary workflow state ("Ready for QA"). Only two rules exist: comment, or
  close.
- Merge queues (GitHub merge queue). A repository that requires one is handed to the operator.
- Rebasing or squashing the PR branch history before merge; the merge method does that.

## 4. Anti-goals (a failure even if it technically works)

- **Merging code the operator did not approve.** The merge is pinned to the exact head the land
  gate approved (`gate.artifact`). Any later change, by an agent or by GitHub's branch update, opens
  a new gate. The operator said so explicitly: after an agent fix, wait for me.
- **Closing tickets by default.** The default ticket rule is comment-and-keep-status.
- **A blind retry of an uncertain merge.** An uncertain merge is recovered by reading the pull
  request, never by sending the merge again (the tracker outbox rule, ADR-043).
- **An agent racing a human.** A human push or comment still means takeover. Only the factory's own
  pushes, and a human merge or close, are excluded.
- **Deleting a branch that is not the item's.** Only the item's own `spire/work-…` branch, and only
  while it still points at the merged head.

## 5. Constraints

- Domain events are appended only by the aggregate: `WorkItemLifecycle` decides, `WorkItemTransitions`
  appends under `lockItem` (ADR-010). New facts go through the decider.
- Effects that call a forge or a tracker are durable rows drained by a sweep, like `work_delivery_effect`
  and `work_tracker_outbox`. A restart loses nothing; an uncertain write is recovered by reading.
- Pure contract code stays framework-free (`PureModulesAreFrameworkFreeTest`).
- No credential defaults; the merge and the branch delete use the repository's FACTORY account.
- Money in millicents; fix runs use the existing pricing, seat and spend checks (`WorkRunAssembly`).
  A seat adds no cost per run; an API key does, and the fleet caps apply.
- Latest migration is V89; latest ADR is ADR-047.
- No local builds; CI proves every step.

## 6. What the operator decided

| Topic | Decision |
|---|---|
| Merge method | Per repository: merge, squash or rebase. |
| Ticket after land | Per repository, two options: (a) keep the status and post a comment that the work is merged, with the PR link; (b) close the ticket. |
| Branch | Delete it after the merge. Not a setting. |
| GitHub refuses the merge | The system tries to fix it with the harness: failed checks, conflicts. |
| Attempt limit | Per repository; the default is 3. A seat costs nothing extra, an API key does. |
| After an agent fix | Land waits for the operator to approve again. |
| After the merge | The item is finished; Readmit is gone. |
| The reviewer requests changes on an agent fix | The item is handed to the operator with the findings. The agent does not fix review findings in this loop. |
| Build and deliver approval gates during a land fix | Skipped. The new land approval at the end is the one human check. Verify still runs. |

## 7. Design

### 7.1 Land settings (per repository)

A new table `repository_land_settings` (V90), one row per repository, with `revision`, `updated_by`,
`updated_at` like `repository_build_defaults`:

| Column | Values | Default when no row |
|---|---|---|
| `merge_method` | `merge` · `squash` · `rebase` | `merge` |
| `ticket_rule` | `comment` · `close` | `comment` |
| `fix_attempts` | integer 0..10 | 3 |

API: `GET/PUT /api/repositories/{repository}/factory/land` beside `/build`, with
`expectedRevision` on PUT (same stale-save contract as build defaults), and `GET …/land/options`,
which reads the GitHub repository's `allow_merge_commit`, `allow_squash_merge` and
`allow_rebase_merge` through the FACTORY account and returns the allowed methods. An unreachable
forge returns all three methods marked "not checked"; the setting is still saveable.

UI: a new **Land** step in the Factory tab, after Build (`RepositoryFactory.tsx`). Three controls:
merge method (a select listing only allowed methods; a saved method GitHub no longer allows shows a
warning), ticket rule (two radio options with the words from §6), fix attempts (number). Copy says
the branch is deleted after the merge. `0` attempts means "hand every refusal to me".

*My call:* a Factory step rather than the Settings tab, because these values only matter to factory
items, and the Factory tab already holds build defaults.

### 7.2 New forge capability: land

A new SPI port `PullRequestLander` in `spire-contract/.../port/`, implemented for GitHub only
(`GitHubPullRequestLander`, wired in `ProviderClients`; other forges get no implementation, which is
what makes land `capability_unavailable` there):

- `LandState read(PullRequestRef pr)`: PR `state`, `merged`, `merge_commit_sha`, head sha, base
  branch, `mergeable` (nullable while GitHub computes it), `mergeable_state`
  (`clean|unstable|blocked|behind|dirty|draft|unknown|has_hooks`), and the head's checks: every check
  run (`GET /commits/{sha}/check-runs`, paged) and the combined status (`GET /commits/{sha}/status`),
  reduced to `PASSED | PENDING | FAILED` plus the failed entries (name, conclusion, `output.title`,
  `output.summary`, first annotations), capped in size.
- `MergeOutcome merge(pr, expectedHeadSha, method)`: `PUT /pulls/{n}/merge` with `sha` and
  `merge_method`. Maps 200 to merged (with `sha`), 405 to `not_mergeable` (with GitHub's message),
  409 to `head_moved`, 422 to `refused` (message), and transport failure to `uncertain`.
- `void deleteBranch(repo, branch, expectedSha)`: reads the ref first; sends the delete only when it
  read `expectedSha` (check-then-act, not a compare-and-swap: see A6); 404 and 422 (already gone, or
  auto-deleted by GitHub) count as done.
  `GitHubClient` gains `DELETE`.

The `mergeable_state` reading drives the decision (§7.4); the check reduction gives the fix run its
input and the operator their reason.

### 7.3 The land effect

Approving land (dashboard or a GitHub PR approval, both already resolve the gate through
`ResolveGate`) no longer reaches `land_capability_unavailable`. `WorkPhaseCapability.available(item,
"land")` becomes true for `approve` when the item's forge has a `PullRequestLander` and the item has a
delivered pull request. `WorkItemLifecycle.enter` then keeps the item at `land / active /
land_in_progress` and the transition enqueues one row in a new table `work_land_effect` (V90), bound
to the item id, generation, revision, the pull request, and `approved_head = gate.artifact`.

A `WorkLanding` sweep drains it (every few seconds, like `WorkDelivery`), with states:

```
pending → checking → merging → merged → cleaning → done
                 ↘ waiting_checks ↗          ↘ (ticket effect enqueued)
                 ↘ fixing        (item leaves land; see 7.5)
                 ↘ handed        (item goes to the operator; see 7.6)
```

1. **checking:** `read(pr)`.
   - PR already merged → record the merge as found (`merged_by` = whoever GitHub names) and go to
     `merged`. PR closed unmerged → `stopped / pr_closed_by_human`, as in §7.8.
   - Head ≠ `approved_head` → handed, `land_head_moved` (a human pushed; takeover normally catches
     this first).
   - Checks `PENDING` or `mergeable` null → `waiting_checks`; re-read every 60 s, up to 6 h, then
     handed with `land_checks_timeout`. *My call on both numbers.*
   - Checks `FAILED` → fix (§7.5), reason `land_checks_failed`.
   - `dirty` → fix, reason `land_conflict`.
   - `behind` → fix, reason `land_branch_behind`: the harness merges the base (no conflict to
     resolve) so verify runs on the result. No attempt is spent (§7.5).
   - `blocked` with checks passed → handed, `land_blocked` (protection: required reviews, code
     owners, signed commits, merge queue). `draft` → handed, `land_pr_draft`.
   - `clean`, `unstable` with checks passed, `has_hooks` → `merging`.
2. **merging:** the row is marked `uncertain` before the call. `merge(pr, approved_head, method)`.
   - merged → store `merge_commit_sha`, go to `merged`.
   - `head_moved` → handed, `land_head_moved`. `not_mergeable`/`refused` → re-read once: a code
     cause routes to fix, anything else is handed with GitHub's message,
     `land_merge_refused`. A method GitHub does not allow is handed with
     `land_merge_method_not_allowed`.
   - `uncertain` → recovery by `read(pr)` only: merged with a merge commit → `merged`; still open at
     `approved_head` → back to `checking` (a merge that did not happen may be attempted, since the
     sha guard makes a second merge of different code impossible); anything else → handed.
3. **merged:** the decider appends `WORK_ITEM_LANDED` (new event kind) with the pull request, merge
   commit, method, who merged (`factory` or the human's stable provider id), and the time. Then
   `cleaning`.
4. **cleaning:** `deleteBranch(repo, item branch, approved_head or the found head)`. Failure is
   retried by the sweep; it never blocks completion past three tries, after which it is recorded as
   `branch_delete_failed` on the item and left to the operator. Then the ticket effect (§7.7) is
   enqueued and the item enters `complete`: `completed / landed`.

### 7.4 Why `mergeable_state`

GitHub computes one field that already joins branch protection, checks and conflicts. Reading checks
ourselves only adds the detail the fix run needs. `unstable` (non-required checks failing) is merged
only when our own check reduction passes; a failing non-required check still sends the item to fix,
because the operator asked for failed checks to be fixed. *My call.*

### 7.5 The fix loop

A fix is a new build on the **same pull request branch**, which item builds cannot do today
(`ExecuteWorkRun` refuses an existing branch, `RunCommand.java:287`). The slice adds a land-fix
variant:

- **Trigger:** the land effect ends in `fixing` with a reason and a payload (failed checks with their
  summaries, or "merge `origin/<base>` into this branch and resolve the conflicts"). The decider
  appends `LAND_FIX_REQUESTED` and rewinds the item phase to `build`, like verify's Retry build
  (`WorkItemTransitions.java:302-304`).
- **Budget:** fix attempts are counted per generation from `LAND_FIX_REQUESTED` events whose reason
  is `land_checks_failed` or `land_conflict` (`land_branch_behind` is not counted). When the count
  would exceed the repository's `fix_attempts`, the item is handed instead with
  `land_fix_attempts_exhausted` and the last reason.
- **Item limits still apply** (*my call*). Each fix is a build run, so `maxRunsPerItem`, the item's
  cost limit and its wall-clock limit count it (`WorkProgress.start`). The land fix checks them
  before it is requested: a limit that would be crossed hands the item with `land_item_limit_reached`
  naming the limit, instead of today's `policy_cap_reached` deeper in build. The Land step shows
  "attempt k of N", where N is the smaller of `fix_attempts` and the runs the item has left.
- **Gates skipped (operator, 2026-10-21):** a build reached through `LAND_FIX_REQUESTED`, and the
  deliver that follows it, do not open their `approve` gates. The decider skips them only while the
  generation's latest land fix has no `WORK_ITEM_LANDED` or new land gate after it. Verify keeps its
  own result gate.
- **The pull request survives the rewind.** Today entering `build` clears `execution`, and
  `validExecution` refuses a build result that carries a pull request. A land-fix rewind keeps
  `execution.pullRequest` and `reviewId`; the build, verify and deliver results of a land fix are
  valid with that pull request present. This is a contract change in `WorkItemLifecycle`, with its
  own decide-table rows and tests.
- **The run:** assembled by `WorkRunAssembly` with the existing credential, model, pricing and spend
  checks, from the delivered head as checkpoint, `onExistingBranch(item branch)` (ADR-040), with the
  failure payload in the prompt. For a conflict the run unit fetches the base and the harness is told
  to merge it and resolve; the commit is a merge commit on the branch.
- **Verify** runs as usual on the fix checkpoint. FAILED/UNVERIFIED keep today's verify gate.
- **Deliver** pushes to the existing branch instead of opening a pull request: a new publication
  permit kind for an existing branch with an expected remote head (`approved_head`) so the push fails
  if anyone else moved the branch. The pull request is reused.
- **Takeover:** the factory account's own push to the item branch is not a takeover. Today any
  `pull_request synchronize` on a linked item suspends it (`WorkGateChannels.java:70`); pushes whose
  sender is the item's control identity or the current FACTORY bot are excluded.
- **Review:** the push re-triggers the reviewer (the bot is already a requested reviewer). The item
  waits in `review` as it does after first delivery.
- **Review blockers (operator, 2026-10-21):** if the review of a land fix has open blockers (Request
  changes), the item is handed with `land_fix_review_blocked` and the findings, instead of today's
  silent wait at `review_blockers_open`. No attempt is spent and no agent fixes the findings.
- **Land:** the land gate opens again with `artifact` = the new head. The operator approves again.

`behind` goes through the same loop with a base merge and no conflicts, so verify always runs on the
code that reaches the gate. It spends no attempt but still needs a new approval, because the head
changed. *My call:* GitHub's update-branch API was rejected because its merge commit would reach a
land gate unverified.

### 7.6 Handed to the operator

`handed` sets the item to `land / awaiting_input / <reason>` with GitHub's message stored on the
effect. The operator's choices are **Retry land** or Stop. Readmit is not offered (§7.9).

**Retry land is new** (*my call*). It is an operator command, not a gate answer: it needs a verified
operator, the expected revision and a note, like resume, and it is audited. It never merges on the
earlier approval. It opens a fresh land gate on the pull request's current head, under the current
policy revision; the operator then approves that gate as usual, and the land effect starts again from
`checking`. An approved land gate that was resolved before the hand-off is never reused. Each reason gets copy in `workReasons.ts`, naming
what GitHub said and what to do (for `land_blocked`: "GitHub requires something only a person can
give: …").

### 7.7 The ticket after land

Enqueued in the existing `work_tracker_outbox` through `WorkSourceEffects` (uncertain writes recovered
by reading, ADR-043):

- `comment` rule: one COMMENT, marked with an effect marker: "Merged by the factory: <PR link>
  (<merge commit short sha>). The ticket status is unchanged so testing can follow." (or "Merged by
  <handle>" for a human merge).
- `close` rule: the same COMMENT, then a TRANSITION that closes the ticket: `closed` on GitHub Issues,
  `close` on GitLab. **Jira:** close needs a transition id per workflow, which this slice does not
  configure; a Jira source under `close` posts the comment, skips the transition, and records
  `ticket_close_unsupported` on the item, shown as a note, not an error. *My call.*

The item is `completed` once the merge is recorded; the ticket write finishing later does not hold
completion, and its failure shows on the item like other tracker effects.

### 7.8 A person merges or closes the PR on GitHub

`GitHubIngress.activity` maps `pull_request closed` to kind `push` today, so a hand merge suspends
the item. It gains two kinds: `merged` (closed with `merged: true`) and `closed`. In
`WorkGateChannels.activity` for a linked item:

- `merged` at any phase after the pull request exists, including `build` and `verify` during a land
  fix: the decider records `WORK_ITEM_LANDED` with the human as merger, the land effect starts at
  `cleaning`, the ticket rule applies, the item completes. A running fix run is cancelled through the
  M1 cancel path and its publication permit is revoked, the same way takeover revokes it, so it
  cannot push to the merged branch. The branch is still deleted: it is the factory's branch.
  *My call.*
- `closed` unmerged: the item goes to `stopped / pr_closed_by_human`. No branch delete. A running fix
  run is cancelled and revoked the same way. The land effect reaching a closed pull request by reading
  gives the same result, so the two paths cannot disagree (`land_pr_closed` in §7.3 means this).

Without a webhook (none registered, or a missed delivery) a hand merge is noticed only when the land
effect reads the PR. That limit is recorded in UNVERIFIED.

### 7.9 Readmit after land

`WorkItemTransitions.resume` refuses readmit with `readmission_landed` when the current generation
has `WORK_ITEM_LANDED`, and with `readmission_land_pending` while the item is handed at `land /
awaiting_input` (Retry land is the way forward there). `canReadmit` in `WorkItemActions.tsx` excludes
both; today it offers Readmit on every `awaiting_input` item; the item page
shows the merge (PR link, merge commit, method, who) in the journey's land step. An item in the fix
loop is `active` and already refuses readmit.

### 7.10 Dashboard

- Journey step **Land**: "Waiting for your approval" → "Checking GitHub" → "Waiting for checks
  (n of m done)" → "Merging" → "Merged <sha> by <who>, branch deleted" / "Fixing: <reason>, attempt
  k of N" / "Needs you: <reason>".
- The land gate panel shows, before approval, what will happen: method, ticket rule, branch delete,
  and the current check summary when known.
- Attention lists `handed` items as needing the operator.

## 8. Docs to change

- ADR-048: land merges through a durable effect pinned to the approved head; refusals caused by code
  loop through build, verify, deliver, review and a new gate; a hand merge completes the item.
- `docs/factory/AUTONOMY.md` land row and "merged, or handed to a human" (now true), `ROADMAP.md`
  M4 paragraph, `docs/ROADMAP.md`, `CLAUDE.md` status and limits, `docs/UNVERIFIED.md` (#43 readmit
  entry resolved; new entries for the paths only fakes reach), `docs/CONTRACT.md` (new events and the
  port), `docs/DATA-MODEL.md` (two tables), `docs/HISTORY.md` at acceptance.
- `docs/factory/LAND-ACCEPTANCE.md` at acceptance.

## 9. Proof

CI for every step (RED before GREEN), mutation checks on every guard, and a live acceptance on
`spire-test` with TEST-labelled tickets:

1. Clean land, `comment` rule: PR merged with the chosen method, branch gone, one comment, ticket
   still open, item completed, Readmit absent.
2. `close` rule on a GitHub issue: comment then closed.
3. A failing check: one fix run, re-review, a new land gate, approve, merged.
4. A conflict made by a TEST commit on the base: one fix run with a merge commit, new gate, merged.
5. A protection block (required review from code owners, or a TEST ruleset): handed with GitHub's
   reason; no fix run.
6. A hand merge on GitHub: item completed with the human as merger, branch deleted, ticket comment.
7. Fix attempts set to 1, two failures: the second hands the item with `land_fix_attempts_exhausted`.

## 10. Review amendments (2026-10-22) — these override the sections they name

1. **Ticket effect after completion (§7.7).** `WorkSourceEffects.dispatch` calls
   `WorkPolicy.Phase.valueOf(item.phase())` and refuses on a changed revision/phase
   (`WorkSourceEffects.java:76-88`); `complete` is not a `Phase`, so a row drained after completion
   throws on every pass and stalls the whole outbox. Add a **post-land effect class**: enqueued in the
   same transaction as `WORK_ITEM_LANDED` (binding that revision), drained whatever the item's current
   phase or status, checked against the repository's `ticket_rule` instead of a phase mode, and never
   calling `Phase.valueOf` on `complete`. A contract change with its own tests, including "drained
   after completion" and "an unknown phase never stalls the batch".
2. **One landing, two observers (§7.3, §7.8).** The factory's own merge produces a `pull_request
   closed, merged: true` webhook. A `merged`/`closed` activity whose sender is the item's recorded
   control identity **or** the current FACTORY/REVIEWER bot (`currentMachine`) is receipted
   `recorded_machine` and left to the land effect. `WORK_ITEM_LANDED` is appended at most once per
   generation and pull request, under `lockItem`; the webhook path and the effect both check history
   first, and the later one only advances the effect row. Who merged comes from
   `pull_request.merged_by.id`, not `sender`.
3. **Retry land (§7.6).** The fresh gate is opened on `execution.head()` (the verified and reviewed
   head), never on the pull request's current head. If the PR head differs, Retry land is refused
   with `land_head_moved`; the operator can Stop. The existing "Recheck and resume" on a `land /
   awaiting_input` item would reopen the gate with no note: `advance` refuses it there with
   `retry_land_required`, and the UI shows Retry land instead.
4. **Check reduction (§7.2, §7.3).** Reduce from the `statuses[]` and `check_runs[]` lists, not the
   combined `state` (which is `pending` when there are no statuses at all). An empty set is PASSED.
   Conclusions: `success|neutral|skipped` PASSED; `failure` FAILED (fix); `queued|in_progress` and
   status `pending` PENDING; `cancelled|timed_out|action_required|stale` handed with
   `land_checks_not_code` and the names. When `mergeable_state` is `clean`, pending non-required
   checks are not waited for. `mergeable_state: unknown` is treated like `mergeable == null`.
5. **Merge outcomes (§7.2, §7.3).** `uncertain` for I/O failures (timeout, connection reset,
   unreadable response) and for any 5xx: a 502 or 504 from GitHub's edge is a lost answer, and GitHub
   can finish the merge after the gateway gives up. Every status below 500 is definite. *(Amended
   2026-10-22 after slice 1 review; this line first said every HTTP status was definite.)* A re-read
   after any refusal that finds the pull request merged is recorded as `merged`, never handed.
   409: re-read; head unchanged → `checking`
   (the base moved), head changed → `land_head_moved`. 403/404 → handed `land_merge_refused` with
   GitHub's message. A method the repository does not allow is detected in `checking` from
   `allow_*_merge`, not parsed from a 405 message.
6. **Revocation is best effort (§7.8).** As today, cancel goes through the delivery sweep and a push
   already running may land. The guards are the sha-pinned merge (nothing but `approved_head` is
   merged) and the branch delete, which reads the ref first and sends no delete when the ref moved
   (it then records `branch_delete_failed`). The delete is **not** a compare-and-swap: GitHub's
   `DELETE /git/refs/...` takes no expected sha, so a push that lands between the read and the delete
   is deleted with the branch and the delete reports done. That window is recorded in
   `docs/UNVERIFIED.md` §E; nothing may rely on the delete to stop a late push. A late build or
   verify result for a cancelled fix run is accounted through `recordLateBuild`
   (`WorkItemTransitions.java:116`), never left failing on `item_not_active`.
7. **Where the fix run starts (§7.5).** Like `/fix` (`FixDispatch.java:128`): clone the item branch at
   `approved_head` from the forge, `onExistingBranch(item branch)`, no dependency on a retained
   checkpoint. `approved_head` is also the publisher's expected remote head. The land-fix
   `ExecuteRun.branch` is the item branch, because `WorkRunDispatcher.claim` rewrites `WorkControl`
   from `execution().branch()` (`WorkRunDispatcher.java:100`).
8. **New wire facts (§7.3, §7.9, §7.10).** `WorkExecution` gains a `landing` component (pull request,
   merge commit, method, merger provider id, time) with a wither next to the record; it is covered by
   a snapshot test that recurses into it. `WorkItemDetail` in `api.ts` gains `landing`. `canReadmit`
   excludes `completed` with a landing and `land / awaiting_input`. Every new reason is added to
   `workReasons.ts` in the same change.
9. **Gate skipping is computed outside the decider (§7.5).** `WorkItemLifecycle.enter` sees no
   history. `advance` and `answer` derive `landFixInProgress` from history under the lock and pass it
   as `approved` for build and deliver. Decide-table rows for both, and a mutation test that the flag
   alone flips each gate.
10. **Takeover wording (§7.5).** `control.suspend` already skips the recorded FACTORY/REVIEWER
    identities (`WorkItemControl.java:62`). What is missing is the *current* identity after rotation;
    extend the exclusion with `currentMachine`.
11. **Migrations.** `repository_land_settings` is V90, `work_land_effect` is V91.
12. **Statuses (§7.3).** Use the existing `active / phase_started` on entering land; the new reasons
    are `landed` (on `completed`) and the hand-off reasons, each with a decide-table row.
13. **Branch delete (§7.2).** After any non-204 from `DELETE /git/refs/...`, re-read the ref: present →
    `branch_delete_failed`, absent → done. Do not treat 404/422 as gone without the re-read.

## 11. Left to the builder

Slice order and task boundaries; event and record names beyond the ones above; how the fix-run
prompt is worded; the exact check-summary cap; how the existing verify gate copy changes for a fix
checkpoint; the UI layout of the Land step within the existing Factory step style.
