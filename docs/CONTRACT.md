# Domain Contract (`spire-contract`)

## Work-item channels and policy (M3, ADR-043/045)

Signed issue delivery produces `WorkSourceDelivery` on `cs.work-integration`, keyed by the
stable `WorkItemIds` digest. Its coordinates bind the registration, repository, source, SCM
origin and external scope; a webhook hint is not proof of complete current-label history.
`WorkItemIntake` and the scanner fetch the current issue and bounded audit evidence through
the explicitly selected source account. Unknown attribution grants nothing, even with an
otherwise allowed actor hint. Actor membership and missing identity have separate reasons.

`WorkItemLifecycle` is the sole work-event decider. `WorkItemEvent` is stored and decoded as
its own type, outside the review `DomainEvent` and `IntegrationEvent` hierarchies. One JTA
transaction appends its encrypted envelope, updates bookkeeping, deduplicates delivery and
enqueues the encrypted notification. The outbox publishes the stable envelope ID on
`cs.work-events` and marks it published only after broker acknowledgement; delivery is at least
once. Work ingress failures use `cs.work-dlq`. Review history does not consume work events.

Profiles have immutable versions and an operator-defined unique precedence. Every eligible
current label contributes a component-wise restriction; the repository ceiling and the full
mode vector retained at admission also bound later decisions. The selected profile name is a
display choice, not the whole effective policy. Events retain applied/ignored evidence and the
source, account, repository and policy revisions. Humans register fetched specification and plan
references; their versions bind later decisions. Dashboard answers, explicit tracker commands
and supported current native PR approvals enter `ResolveGate` and persist `GATE_RESOLVED`.
VERIFY runs since M4 slice 1 (ADR-046), and LAND on GitHub since M4 slice 2 (ADR-048, below);
manual artifact acceptance does not invent an executor completion. Tracker status never supplies workflow status.

`ExecuteWorkRun` uses `cs.run-commands` and yields a durable `RunWorkReady` checkpoint without
publishing. `PublishWorkRun` on `cs.run-control` carries the exact current delivery permit;
only the trusted publisher resumes. `HoldWorkRun` uses that same control topic and durably revokes
the exact run binding before stopping compute. The revocation survives restart and orphan salvage
independently of M1 cancellation. Ordinary `ExecuteRun` and standalone /fix retain their automatic
publication path. Readiness and terminal results use `cs.run-results`; transcript facts remain
on `cs.run-events` and do not become work-item domain events.

`RunWorkReady.stepSummary` (M4 slice 8) is the `StepSummary(decisions, alternativesRejected, deviations)` a build's
agent wrote to `$SPIRE_STEP_SUMMARY`, reported by the publisher as a `step_summary` line. The run worker parses it
leniently (unknown fields and non-text items skipped; a value that is not a JSON object is no summary), keeps at
most 20 items per list, cuts an item over 500 characters on a character boundary with an ellipsis, strips control
characters and scrubs every credential the run was given. The record refuses anything over those caps. Null means
no summary, never an empty one, and a ready written before the field existed reads as null; the six-component
constructor carries none, and `withStepSummary` rebuilds with it.

A plan built step by step (M4 slice 3, §6.6, A2) adds three wire components. `ExecuteWorkRun.checkpointRequired`
(`requiringCheckpoint()`, refused without `startFromRunId`) marks a later step's start: the run worker never replaces
it with the base, and a start whose held unit is gone fails before any launch as `CHECKPOINT_MISSING` (one released
after that check fails the launch instead).
`ExecuteWorkRun.emptyAllowed` (`allowingEmpty()`) marks every plan step outside a land fix; slice 10 reads it. Both are
false on every other build and on a command decoded from before them, and every wither carries both.
`RunWorkReady.startFromHead` (`withStartFromHead`) is the head the build started from after any base fallback, null
for a build from the base. The held publisher is told the same head as `SPIRE_START_HEAD` and refuses a checkpoint that
does not descend from it as `START_NOT_DESCENDED`; the orchestrator refuses a plan step's ready whose `startFromHead`
is not the required start it recorded with the dispatch claim, `phase_evidence_mismatch` on the run effect, and fails
the step as a failed run does: the verify gate opens on the chain's head, `UNVERIFIED / step_run_failed`.

`ExecuteWorkDraftRun(runId, execution, draft)` (M4 slice 3) is a specification or plan run, also on
`cs.run-commands`. `draft` is a `WorkDraftBinding(workItemId, generation, attemptId, kind, provisionalDigest)`:
`kind` is `SPEC` or `PLAN`, and the digest (64 hex) binds the setup revision, the ticket and the kind instead of
a preparation binding, since none exists until both drafts are approved. The command refuses a checkpoint start, an
existing branch, and any branch but `spire/draft-<attemptId>`, the clone's local name. The worker takes the
ordinary execute claim and lease and launches a read-only unit: its publisher runs `spire-publish-none` with the
handoff and no write credential. The result is `RunWorkArtifact(runId, draft, text, repositoryChanged, tokenUsage,
activeWallSeconds)`, the artifact file scrubbed of every credential the run was given, at most 32 KiB of UTF-8;
`repositoryChanged` is a warning that the agent committed, and those commits are discarded with the unit. A run
that hands over no artifact, an empty one, or one the publisher refused fails as `ARTIFACT_MISSING` (not
retryable, usage charged; orchestrator V97 widens the `failure_cause` CHECK). The unit is destroyed once the result
is decided and never enters the held store. The orchestrator projects `RunWorkArtifact` as `succeeded` with no
pushed ref, charges it under the run's kind, and neither proposes it nor hands it to the work-run inbox.
`WorkPrepareDispatcher` dispatches a specification run from its `work_prepare_effect` row (slice 6): it reads the
ticket and the head of the setup's base branch, claims the row `pending → uncertain` under the item lock, and sends;
an uncertain send is never repeated, only M2's dispatch resolution re-arms it. A read that did not answer (a tracker
outage, a forge error on the branch head) leaves the row `pending` with a doubling backoff from 30 seconds; the fifth
in a row stops the item with that read's reason. The ticket is quoted with per-section caps (title 1 KiB, body 32 KiB,
labels 4 KiB), cut on a character boundary with the cut named, so a long ticket never exceeds the 64 Ki-character
dispatch limit. `WorkDraftResults` accepts a
`RunWorkArtifact` only for the row's item, generation, attempt, kind and provisional digest (otherwise the row notes
`draft_binding_mismatch` and the result is neither projected nor charged), and claims a draft run's `RunFailed` too,
so no draft result reaches the work-run inbox or a pull request proposal. A plan run (slice 7) is dispatched the same
way, from the approved specification as stored (never the ticket read again) and at the commit the specification read.

`WorkItemEvent` gains the component `draft` (`WorkDraft`, M4 slice 3, A4), carried by every wither:
`mode` (`generated`), the stored `specification` and `plan` references (`STORED` only), the revision counts (at most
3 per phase), the two approvals, the `baseCommit` the specification read and the `setupRevision` it was written
under. When the orchestrator's `spire.work.generated-preparation` is on and the repository prepares `generated`,
`WorkDraftLifecycle.admitGenerated` records it on an item not admitted before (never observed, or observed with no
admitted profile), so items selected before a switch stay where they are (A16). Such an item in spec waits at
`spec / awaiting_input / specification_to_write`, milestone `DRAFT_ADMITTED`. Entering spec or plan while the draft
lacks that phase's artifact parks an ordinary entry at `specification_to_write` or `plan_to_write` (milestone
`DRAFT_WAITING`), with no gate and no attempt; only the draft sweep enters with `EntryMode.DRAFT`, which skips the
permission gate (the setting admits the run, A6) after off, unknown usage and the caps, and starts the attempt. The
sweep first observes the source and the ticket; under the item lock it refuses `authority_changed_during_read` when the
source or the policy revision moved, and enters with the policy selected again from that observation, so a disabled
source, a removed label or an edited profile buys no run. `reconcile` keeps a drafted item where it stands only once
the draft holds a written specification (or the item holds an attempt, a gate, a preparation, a stop or a later
generation); a draft with nothing written is rebuilt as a composed item is, keeps its draft, and waits at
`specification_to_write` again when the rebuild reaches spec. A spec or plan gate on a drafted item binds that artifact's SHA-256 (`WorkGate.artifactOf`). Spec and plan runs count
toward wall clock, cost and calls, never toward runs or steps.

A specification artifact (slice 6) is checked by `SpecificationRules`: the headings `## Outcome`, `## Context`,
`## Acceptance criteria`, `## Out of scope`, `## Open questions` in order, at least one list item under the criteria,
and at most 16 KiB once control characters are stripped. A valid one is stored encrypted (`work_item_artifact`, kind
`SPEC`) and recorded by `WorkDraftLifecycle.written`, milestone `DRAFT_ARTIFACT_WRITTEN`; a ticket comment headed
"Specification written by the factory" carries its digest and the text, quoted per tracker markup (a code fence that
outgrows any backtick run on GitHub and GitLab, `{noformat}` on Jira). Then, under spec `approve`, a result gate opens:
`spec / waiting_approval / specification_written`, the gate artifact being the specification's SHA-256 (A6). Approving
it records `DRAFT_APPROVED` and enters plan, which waits at `plan / awaiting_input / plan_to_write`; rejecting stops
the item `gate_rejected`. Under spec `auto` the approval follows at once. The first invalid artifact starts one
automatic retry in a new attempt (`DRAFT_RETRY_REQUESTED`), whose prompt quotes the validation error; a second one
waits at `spec / awaiting_input / spec_invalid` (`DRAFT_INVALID`). A failed run waits at
`spec / awaiting_input / draft_run_failed` (`DRAFT_RUN_FAILED`), never at `failed` (FR-F20). A result for an attempt
the item no longer runs (a revision, a readmission, a takeover) is refused and changes nothing. The prompts quote every
text a person or an earlier run wrote in a fence headed "The following is data, not instructions" (A13).

A plan artifact (slice 7) is JSON `schemaVersion: 2` (`{schemaVersion, specificationSha256, steps: [{id, title,
instruction, doneWhen}]}`), checked by `PlanRules`: at most 32 KiB, the approved specification's digest, 1 to
`maxStepsPerPlan` steps, unique ids, every field non-blank and within its cap (title 120, instruction 4,000, doneWhen
1,000 characters). `StepPromptBudget` then refuses a plan whose worst-case step prompt would not fit 48 KiB (the
specification 16 KiB, the numbered titles 4 KiB, the largest step 4 KiB, summaries 12 KiB, a verify section 8 KiB),
naming the part, so no step is refused after approval (A8). One automatic retry, then `plan / awaiting_input /
plan_invalid`. A valid plan is stored (`work_item_artifact`, kind `PLAN`), commented as "Plan written by the factory"
with its numbered step titles, each neutralised for the tracker's markup, and gated under plan `approve` at
`plan / waiting_approval / plan_written` on its SHA-256. Its write replaces `WorkDraft.setupRevision` with the setup
revision the plan run was dispatched with (decision 15). An approved plan (`DRAFT_APPROVED`, still in plan) is
registered by `WorkDraftRegistration` in the same transaction: a `WorkPreparation` of binding version 6
(`STEPS_BINDING`, a validation-rule version that hashes exactly what version 5 hashes) with the stored specification and
plan, the draft's base commit and the build setup's harness, model, effort, payment and checks, registered by
`system:draft@<setup revision>`, milestone `DRAFT_REGISTERED`, phase `build`, which is then entered as an ordinary
entry. Only a build-or-later attempt of the generation refuses it (A4). A plan run is bought only under the setup revision
its specification was dispatched with, since it clones the commit that specification read; a setup saved in between
stops the plan attempt at `plan / stopped / draft_setup_changed` before any run. A setup saved since the plan was
dispatched waits at `plan / awaiting_input / draft_setup_changed`; the ticket is not read again, so an edit to it refuses nothing. The
registered item carries the plan cursor. Under bindings 1 to 5 a plan stays `schemaVersion: 1` with
exactly one step; `schemaVersion: 2` is accepted only under binding 6.

`WorkItemEvent` gains the component `steps` (`WorkSteps(total, index, completed, lost)`, slice 9, A17), carried by
every wither and set by `WorkStepLifecycle.planRegistered` for a binding-6 plan: the next step (0-based), each
completed step's `StepResult(index, execution, empty, holdsBundle)` and the runs whose checkpoint a start found gone.
`WorkStepLifecycle` decides the loop; the phase order is untouched. After a verify's `PHASE_COMPLETED`, while the
cursor is not done and no land fix is in progress, a passed step appends `STEP_COMPLETED`: phase `build` again for
the next step, or `deliver` after the last, with the newest step run that holds a bundle and the last changed step's
head and verification (decision 9). `WorkProgress.steps` counts completed steps, not build starts (A11): an item
without a cursor completes its one step when verify passes, a land fix completes none and is not checked against
`maxStepsPerPlan`, and readmission resets the count and the cursor. `EntryMode.STEP` (between steps, never step 1 or a
land fix) skips the build and verify permission gates. A later step builds from the previous step's checkpoint, required;
a retry starts from its own failed checkpoint, or from the chain run while no run of the step reached ready, and never
from the base once a chain run exists (decision 11). A later step's `RunFailed` with commits behind it appends
`STEP_RUN_FAILED` and opens the verify gate on the chain run's head, unverified `step_run_failed`, or
`checkpoint_missing` for `CHECKPOINT_MISSING`, which also records the required start in `lost`. Retry of a step with no
start left is refused `step_checkpoint_lost` and the gate stays open with Stop. Step 1's failure is still
`failed / phase_failed`.

**Revise** (`POST /api/approvals/{id}/revise {expectedVersion, idempotencyKey, note}`, `spire-admin`, a note required)
answers an open specification or plan result gate from the dashboard only: `ResolveGate` has no revise, so a tracker
command or a pull request review keeps plain approve and reject (A6). `WorkDraftLifecycle.revised` resolves the gate
`REJECTED` with the note (no new gate state), counts the revision, clears that phase's artifact (a revised specification
takes its plan with it) and parks the item at `specification_to_write` or `plan_to_write`, milestone `DRAFT_REVISED`,
with no attempt; the draft sweep starts the next run, and the start's `work_prepare_effect` row carries the rejected
gate's note, encrypted under `work-prepare-note:<attempt>`, which the prompt quotes as data. A fourth revision of a phase
is refused `revision_limit_reached` (409) and the gate stays open; a gate that is not a draft result gate is refused
`revise_unavailable`.

Takeover records stable actor IDs in `WorkControl`, supersedes open gates and invalidates pending
effects. Deliberate authorized commands are classified before generic comment takeover. Resume
requires the server-derived operator subject, expected revision and note, fresh issue/repository/
head/policy evidence, and a new generation. Retired items cannot resume. See the
[acceptance record](factory/M3-ACCEPTANCE.md) for measured journeys and remaining live limits.

## Land (M4 slice 2, ADR-048)

`WorkItemLifecycle` gains six milestones. Each is decided there and appended under
`WorkItemTransitions.lockItem`:

| Milestone | Item after it | When |
|---|---|---|
| `WORK_ITEM_LANDED` | `complete / completed / landed` | the land effect merged or found the merge, or a person merged on GitHub (reported by the FACTORY or the REVIEWER webhook, or found by the periodic pull request read); at most once per generation and pull request |
| `LAND_FIX_REQUESTED` | `build / awaiting_input / <fix reason>` | `land_checks_failed` or `land_conflict` (each spends a fix attempt) or `land_branch_behind` (spends none); the execution and its pull request are kept |
| `LAND_HANDED` | `land / awaiting_input / <hand-off reason>`, no gate | anything else land cannot finish (below) |
| `PULL_REQUEST_CLOSED` | `stopped / pr_closed_by_human` | a person closed the pull request unmerged (reported by either webhook, or found by the periodic read), or the land effect read it closed |
| `LAND_RETRY_REQUESTED` | `land / waiting_approval`, a fresh gate on `execution.head()` | Retry land on a handed item |
| `LAND_STOPPED` | `stopped / land_stopped_by_operator` | Stop on a handed item, on one at `land / capability_unavailable` or `land / not_eligible`, or on `land / active` while its land row has sent no merge (`pending`, `checking`, `waiting_checks`); that row is refused in the same transaction |

Hand-off reasons: `land_head_moved`, `land_checks_timeout`, `land_checks_not_code`, `land_blocked`,
`land_pr_draft`, `land_merge_refused`, `land_merge_method_not_allowed`, `land_fix_attempts_exhausted`,
`land_item_limit_reached`, `land_fix_review_blocked`, `land_fix_run_failed`, `land_fix_stopped`,
`land_account_unavailable`, `land_read_failed`, `land_policy_changed` (the labels, the policy revision or the
source changed after approval, or land is no longer `approve`; re-observed under the item lock before the merge is
claimed, as delivery re-observes before its permit). While a land fix is in progress (derived from history
under the lock, never by the decider), the build and deliver `approve` gates are skipped; verify and land
keep theirs. Command refusals: `readmission_landed`, `readmission_land_pending`, `retry_land_required`
(Recheck on a handed item), `retry_land_unavailable`, and `land_head_moved` (Retry land when the pull
request's head is not the verified head). `POST /api/work-items/{id}/retry-land` and `…/stop-land` take
`{expectedRevision, note}` from a verified operator.

Wire changes, each through a wither or an enumerating constructor next to its record:

- `WorkExecution.landing` is a `WorkLanding(pullRequest, mergeCommit, method, mergedBy, landedAt)`.
  `mergedBy` is `factory` or the person's stable provider id, never a login. `method` is what the factory
  sent with its merge, or `unknown` for a merge it did not make: GitHub's pull request read does not report
  a person's method, and the repository's setting is no evidence of it.
- `RepositoryActivity.mergedBy`, for kind `merged` only, is GitHub's `pull_request.merged_by.id`, not the
  sender. GitHub's `pull_request closed` maps to kind `merged` or `closed` instead of `push`.
- `WorkPublicationPermit.expectedRemoteHead` and `mergedBaseTip` (set together by `onExistingBranch`): a land
  fix pushes to the item's existing branch only while the remote branch still points at the expected head,
  and paths the merged base brought are not blamed on the agent. `ExecuteWorkRun.mergedBaseTip` carries the
  same tip to the held build. An item build may use an existing branch only when it is its own
  `spire/work-…` branch.
- Publisher failure codes `REMOTE_HEAD_MOVED` (a non-fast-forward) and `BASE_TIP_UNAVAILABLE` (a gate
  refusal: the named base tip is not an ancestor of the pushed tip).

`WorkLandSweep` drains `work_land_effect` every `spire.work-land-interval` (5 s):
`pending → checking → (waiting_checks) → merging → uncertain → merged → cleaning → done`, or `handed`,
`fixing`, `closed`, `refused`. Checks are re-read every 60 s and given up after 6 h. `WorkLandDecision` is
the pure reading of one `LandState`; `WorkLandApproval` claims the merge and `WorkLandCleanup` records the
landing and deletes the branch. A merge is read through `ProviderClients.pullRequestReader`, which the
REVIEWER account may hold when the FACTORY account is unavailable and which never merges or deletes. Land settings are served at
`GET/PUT /api/repositories/{repository}/factory/land` (`expectedRevision` on PUT, as for build defaults)
and the methods GitHub allows at `GET …/land/options`.

## Repository metadata and ingress channels (M3 slices 1–2, ADR-042)

`cs.registry-integration` carries `RepositoryRegistration`, keyed by registration UUID (not a
review id). Its `type` discriminator is `RepositoryRegistration`; fields are `registrationId`,
positive monotonic `revision`, `providerType`, nullable `forgeOrigin`, `scope` (`repo`/`org`),
`target`, `enabled`, `deleted`, nullable `repositoryId`, `eventKind` (legacy default REVIEWER),
and nullable `sourceId`. The gateway outbox publishes only after its SQL transaction
commits and marks sent only after broker acknowledgement. The orchestrator accepts newer
revisions transactionally. New records reject blank origins while allowing null. At legacy
ingress the bridge converts blank origins to null before strict record decoding, then records
`registration_origin_unknown` and acknowledges the snapshot. Missing origins are never inferred
from account workspace equality; they remain operator-visible pending mappings for repair.

This is an integration snapshot, not a domain event or a new aggregate. Webhook keys and secrets
never cross the channel. Failed processing uses `cs.dlq`; the discriminator routes manual replay
back to cs.registry-integration.

Slice 2 sends signed SCM ingress on cs.repository-integration as RepositoryDelivery, with the
RepositoryDelivery discriminator, repositoryId (nullable for legacy/org hooks), registrationId,
registrationRevision, providerType, forgeOrigin, eventKind, deliveryId and the existing typed
IntegrationEvent. Gateway deliveryId is the SHA-256 of the signed request bytes. A manual review
uses its explicit repository UUID. The consumer resolves full forge identity, verifies any
explicit UUID and repository state, then applies REVIEWER lifecycle handling or forwards FACTORY
activity to cs.repository-activity for the existing activity publication. M3's `WorkActivityConsumer`
independently consumes FACTORY `RepositoryActivity` envelopes directly from cs.repository-integration
under the `spire-orchestrator-work-activity` group, rechecking registration and repository identity.
It does not consume cs.repository-activity. ISSUE routes through the bound work-source ingress to
cs.work-integration rather than the SCM review path.

New deliveries for unknown repositories produce Attention with their incoming registration and
prefilled coordinates. Unknown origins remain repairable, never inferred from namespace equality.
Old raw SCM messages on cs.integration fail with a provenance reason and enter cs.dlq; operators
must redeliver through a verified webhook. DLQ replay of RepositoryDelivery returns to its new
topic with provenance intact. Worker integration-result channels and old review IDs stay intact.

**Upgrade order:** provision cs.registry-integration, cs.repository-integration and
cs.repository-activity and their producer/consumer ACLs when auto-creation is disabled. Upgrade
gateway first: new ingress can wait durably on its new topic while the old orchestrator runs.
Then upgrade orchestrator (the new input begins at earliest), then the UI. Upgrading orchestrator
first would dead-letter still-legacy gateway deliveries. Retain database/keyset backups; an
application-only downgrade is not an ingress rollback because the wire topic changed.

**API cutover:** account ProviderInput/ProviderView have no workspace. Account create/update
accept an optional validationRepositoryId query parameter for account-less token validation;
it must name the same forge kind and origin and creates no binding. Stored account checks may
use one of that account's explicit repository bindings as validation scope. Scope introspection
remains advisory; an unobserved report never establishes permission.

Manual registration without a URL and POST /api/runs require repositoryId. Supplied coordinates
must agree with it; URL preview resolves complete forge identity. Serving views take repositoryId
and read the selected REVIEWER/FACTORY accounts. Missing, disabled and unmapped configurations
cannot dispatch. The repository screen owns coordinates, role bindings and optional per-kind
hooks; legacy organization hooks and origin repair remain at /settings/webhooks.

> The shared kernel every service depends on: identifiers, the event envelope, the event & command
> catalog, the `ReviewLifecycle` decider, the SPI ports, the context-aggregation policy, topics, and
> the Bitbucket **Cloud** mapping. Companion to [EVENT-MODEL.md](EVENT-MODEL.md) (the narrative slices)
> and [ARCHITECTURE.md](ARCHITECTURE.md). Status: design — pre-implementation.

## 1. Conventions

- **Two event kinds.** *Integration events* cross a system boundary (ingress from SCM, results from
  workers, egress). *Domain events* are appended by an **aggregate** and are the source of truth for
  its state. Only the aggregate writes its own domain-event stream (single-writer → clean optimistic
  concurrency); workers never write aggregate streams directly.
- **Naming.** Events = past tense (`ReviewRequested`). Commands = imperative (`RequestReview`).
- **Everything is keyed by `reviewId`** for per-PR ordering (partition key on every topic).
- **Idempotent by `eventId`** at every consumer; re-delivery is safe.
- **Additive evolution only** — new optional fields; breaking changes get a new `eventVersion` + an upcaster. Published events are never mutated.

## 2. Identifiers

| Id | Shape | Notes |
|---|---|---|
| `RepoRef` | `{workspace}/{repoSlug}` | Bitbucket Cloud workspace + repo |
| `prId` | `long` | Bitbucket PR number, unique within a repo |
| `reviewId` | `review::{workspace}/{repoSlug}#{prId}` | **aggregate stream id** — one `ReviewLifecycle` per PR |
| `commit` | provider head identifier **as delivered** (12-char short hash on Bitbucket Cloud) | the PR **head** commit a run targets; the idempotency/supersede key. Expanded to the 40-char SHA only in the worker where an outbound API requires it (ADR-013) |
| `threadRef` | opaque thread handle (`ThreadRef`) | conversation anchor — comment id on BB/GH/DC, discussion_id on GitLab (SCM-MAPPING §6) |
| `eventId` | UUID | globally unique; dedup key |
| `correlationId` | = `reviewId` | threads the whole flow across services |
| `causationId` | UUID | the command/event that caused this one |

## 3. Event envelope

Every event (integration or domain) is wrapped:

```
{
  eventId: UUID,          // dedup key
  eventType: string,      // "ReviewRequested"
  eventVersion: int,      // schema version, starts at 1
  streamId: string,       // aggregate id (domain) or source id (integration)
  sequence: long,         // per-stream monotonic (domain events only; optimistic concurrency)
  occurredAt: Instant,    // producer clock
  correlationId: string,  // = reviewId
  causationId: UUID,      // parent event/command id
  actor: string,          // "system" | "bot:<name>" | "webhook" | "operator:<sub>"
  payload: { … }          // type-specific, below
}
```
`globalPosition` (a monotonic long) is assigned by the event store on append and used by the dispatcher; it is store metadata, not part of the published payload.

## 4. Event catalog

### Integration events (ingress / worker results / egress)

| Event | Producer | Payload (beyond envelope) |
|---|---|---|
| `PullRequestEventReceived` | `spire-gateway` | repo, prId, action(`OPENED`/`UPDATED`), title, description, sourceBranch, targetBranch, **diffRefs{baseSha,startSha,headSha}** (populate what the provider gives), author{**providerUserId,username,displayName**}, htmlUrl |
| `AuthorReplied` | `spire-gateway` | repo, prId, reviewId, **threadRef**, commentId, text, author{providerUserId,username} |
| `PullRequestClosed` | `spire-gateway` | repo, prId, reason(`MERGED`/`DECLINED`) — triggers the cancel saga (ADR-013); for a pull request a work item delivered, also the land observation (M4 land §7.8) |
| `ManualCommandReceived` | `spire-gateway` | repo, prId, command(`review`/…), args, author{providerUserId,username} — parsed from a `/command` PR comment; the saga maps `review` → `RequestReview{force=true}` |
| `PushReceived` *(P3)* | `spire-gateway` | repo, ref, commits[] |
| `DiffFetched` | `spire-review-worker` (via `DiffSource`) | reviewId, prId, commit, changedFiles, languages[], sizeBytes, truncated — **metadata only; no diff content** (deliberate two-fetch: content is re-fetched by commit at generate time; a 404 on re-fetch means the commit was force-pushed away → treat as superseded) |
| `ContextRequested` | `spire-context-worker` | reviewId, repo, prId, commit, **references[]**, **expectedSources[]**, **scmType** — fan-out signal each `ContextProvider` subscribes to (§8) |
| `ContextContributed` | each `ContextProvider` | reviewId, source(`JIRA`/`CONFLUENCE`/`GITHUB_ISSUES`/`GITLAB_ISSUES`/`RULES`/`CODE`/`MEMORY`), status(`OK`/`EMPTY`/`ERROR`), items[], latencyMs |
| `ContextAssembled` | aggregator | reviewId, prId, commit, contextRef, contributingSources[], missingSources[] |
| `ReviewGenerated` | `spire-review-worker` (via `LlmProvider`) | reviewId, prId, commit, findings[] (inline `ReviewResult`, small), summary, model, tokensIn, tokensOut, costMillicents, verdicts[]? (reconciliation verdicts — ADR-019), reconcileUsage? (reconcile LLM call usage — ADR-019) |
| `ReviewFailed` | any worker | reviewId, commit, phase, error, retryable, attempt |
| `CommentsPosted` | `spire-review-worker` (via `CommentSink`) | reviewId, prId, commit, summaryCommentId, inline[]{commentId,path,line}, threadOutcomes[]? (thread resolution outcomes — ADR-019) |
| `FollowUpGenerated` | `spire-review-worker` | reviewId, threadRef, answerText |
| `FollowUpPosted` | `spire-review-worker` (via `CommentSink`) | reviewId, threadRef, commentId |
| `TurnCapNotified` | `spire-review-worker` (via `CommentSink`) | reviewId, threadRef, commentId — deliberately not `FollowUpPosted`: the hand-off notice must not consume a conversation turn |
| `ArchivedNotified` | `spire-review-worker` (via `CommentSink`) | reviewId, threadRef? (null → top-level), commentId |
| `FindingConfirmed` | `spire-review-worker` (via `CommentSink`) | reviewId, threadRef, commentId — deliberately not `FollowUpPosted`; links the confirmation back to the conversation root so a reply to it (on an SCM that threads by immediate parent) is still recognized |
| `FindingRefused` | `spire-review-worker` (via `CommentSink`) | reviewId, threadRef, commentId — same non-turn-consuming, link-back-to-root shape as `FindingConfirmed` |

> Only the **assembled context** is offloaded to the object store (encrypted, referenced by `contextRef`)
> so events stay small. **Diffs are never stored** (re-fetched by commit); **findings** ride inline in
> `ReviewGenerated` (small) and are projected to the `review_finding` read model. See DATA-MODEL.md.

### Domain events (appended by the `ReviewLifecycle` aggregate)

| Event | Meaning |
|---|---|
| `ReviewRequested` | a run started for `commit` (trigger OPENED/UPDATED) |
| `ReviewSuperseded` | a newer commit arrived mid-run; the old run is abandoned |
| `ReviewOutcomeRecorded` | the aggregate acknowledges a produced review for `commit` |
| `ReviewCompleted` | comments posted; run done; `commit` added to reviewed set (carries `summaryCommentId` so `evolve` can fold it into state) |
| `ReviewFailedTerminally` | non-retryable failure; run ended |
| `ReviewCancelled` | the PR was closed/merged/declined mid-run (or an operator cancelled); run abandoned |
| `ThreadOpened` | a conversational thread was started |
| `FollowUpRecorded` | a follow-up answer was posted in a thread |
| `ConversationFindingRaised` | a human ran `/finding` in a thread; anchor + severity only — no `message` (may quote source, stays out of the replayable log, DATA-MODEL §5) |

## 5. Command catalog

**Action commands** (to workers/adapters — cause side effects, produce *integration* result events):

| Command | Handler | Payload |
|---|---|---|
| `FetchDiff` | `spire-review-worker` | reviewId, repo, prId, commit |
| `GatherContext` | `spire-context-worker` (fan-out) | reviewId, repo, prId, commit, **references** (`Set<String>`), contextCredential, **scmType** — the platform the review runs on, so a repo-relative reference (an issue number) is not resolved against the wrong host's same-named repo |
| `GenerateReview` | `spire-review-worker` | reviewId, prId, commit, contextRef, attempt, providerOverride? (set by the fallback saga on retry; worker re-fetches the diff by commit), priorRun? (prior posted run's findings — ADR-019) |
| `PostComments` | `spire-review-worker` | reviewId, repo, prId, commit, findings[] (inline — same `ReviewResult` as `ReviewGenerated`; findings are not stored as blobs, ADR-011), verdicts[]? (follow-up reconciliation verdicts — ADR-019), priorSummaryRef? (summary comment to update in place on follow-up review) |
| `AnswerFollowUp` | `spire-review-worker` | reviewId, repo, prId, threadRef, question — the worker fetches the thread history from the SCM on demand (no blob; same re-fetch philosophy as diffs) |
| `NotifyTurnCap` | `spire-review-worker` | reviewId, repo, prId, threadRef, turnCap — fixed-text hand-off notice, no LLM credential; the worker claims once per thread (the slot is the thread ref) so later replies to a capped thread post nothing |
| `NotifyArchived` | `spire-review-worker` | reviewId, repo, prId, threadRef? (null → top-level PR comment) — fixed-text retirement notice, no LLM credential; claimed once per review (ADR-024) |
| `ConfirmFinding` | `spire-review-worker` | reviewId, repo, prId, threadRef, triggeringCommentId, severity, path, line — fixed-text confirmation that a `/finding` was filed, no LLM credential; claimed per triggering comment, so a second finding in one thread gets its own confirmation |
| `RefuseFinding` | `spire-review-worker` | reviewId, repo, prId, threadRef — fixed-text refusal that a `/finding` had nowhere to anchor, no LLM credential; only emitted when there is a thread to reply into (a bare timeline note otherwise) |

**Record commands** (to the `ReviewLifecycle` decider — append *domain* events):

| Command | Guard → Emits |
|---|---|
| `RequestReview{commit,trigger,force}` | new commit → `ReviewRequested`; if newer run active → also `ReviewSuperseded`; `force=true` bypasses the reviewed-commit idempotency (FR-12) |
| `CancelReview{reason}` | REVIEWING → `ReviewCancelled` (from `PullRequestClosed` or an operator action) |
| `RecordReviewOutcome{commit,findingsCount,summaryDigest}` | commit==current → `ReviewOutcomeRecorded` (aggregate keeps only a digest, not the findings) |
| `RecordCommentsPosted{commit,summaryCommentId,count}` | commit==current → `ReviewCompleted` |
| `RecordFailure{commit,phase,retryable}` | commit==current AND !retryable → `ReviewFailedTerminally` (stale failure from a superseded run → no-op) |
| `OpenThread{threadRef,parentCommentId}` | → `ThreadOpened` |
| `RecordFollowUp{threadRef,commentId}` | → `FollowUpRecorded` |
| `RaiseConversationFinding{threadRef,path,line,severity,message,triggeringCommentId}` | `triggeringCommentId ∉ raisedFindingComments` → `ConversationFindingRaised` (redelivered comment id → no-op) |

Sagas translate integration result events → the next Action command *and* the matching Record command
(e.g. on `CommentsPosted` → `RecordCommentsPosted`; on `PullRequestClosed` → `CancelReview`; on
`ManualCommandReceived{review}` → `RequestReview{force=true}`). Fine-grained progress (diff fetched,
context assembled) lives in the **read model**, not the aggregate — the aggregate holds only
decision-relevant state.

## 6. `ReviewLifecycle` decider

**State**
```
reviewId, repo, prId
status: IDLE | REVIEWING | COMPLETED | FAILED | CANCELLED
currentCommit: sha | null
reviewedCommits: Set<sha>          // idempotency across redeliveries
summaryCommentId: string | null
threads: Map<threadRef, {status, lastCommentId}>
raisedFindingComments: Set<commentId>  // idempotency for /finding, same shape as reviewedCommits
```

**decide(command, state) → events**

| In state | Command | Guard | Emits | Next state |
|---|---|---|---|---|
| any | `RequestReview{c, force=false}` | `c ∈ reviewedCommits` or `c == currentCommit` | `[]` (idempotent no-op) | — |
| any | `RequestReview{c, force=true}` | — (bypasses reviewed-commit idempotency, FR-12) | (`ReviewSuperseded{currentCommit}` if a run is active) + `ReviewRequested{c}` | REVIEWING, currentCommit=c |
| | | | *note: forcing while `c == currentCommit` is mid-run deliberately yields `ReviewSuperseded{c}` + `ReviewRequested{c}` — a commit superseding itself, modeled as **restart the run*** | |
| `REVIEWING` | `RequestReview{c}` | `c != currentCommit` | `ReviewSuperseded{currentCommit}` + `ReviewRequested{c}` | REVIEWING, currentCommit=c |
| `IDLE/COMPLETED/FAILED/CANCELLED` | `RequestReview{c}` | new commit | `ReviewRequested{c}` | REVIEWING, currentCommit=c |
| `REVIEWING` | `RecordReviewOutcome{c}` | `c == currentCommit` | `ReviewOutcomeRecorded` | REVIEWING |
| `REVIEWING` | `RecordCommentsPosted{c}` | `c == currentCommit` | `ReviewCompleted{c}` | COMPLETED, +reviewedCommits, summaryCommentId set |
| `REVIEWING` | `RecordFailure{c,retryable=false}` | **`c == currentCommit`** (stale failure from a superseded run → no-op) | `ReviewFailedTerminally` | FAILED |
| `REVIEWING` | `CancelReview{reason}` | — | `ReviewCancelled{reason}` | CANCELLED |
| `IDLE/COMPLETED/FAILED/CANCELLED` | `CancelReview` | — | `[]` (nothing in flight — no-op) | — |
| any | `OpenThread` / `RecordFollowUp` | — | `ThreadOpened` / `FollowUpRecorded` | threads updated |
| any | `RaiseConversationFinding{threadRef,path,line,severity,message,triggeringCommentId}` | `triggeringCommentId ∉ raisedFindingComments` | `ConversationFindingRaised` (redelivered comment id → `[]`, idempotent no-op) | +raisedFindingComments |

**Invariants**
- One active run per PR; a newer commit supersedes an in-flight run (latest-commit-wins).
- A commit is reviewed at most once (`reviewedCommits`) unless re-review is forced (`force=true`).
- Stale worker results **and stale failures** (`commit != currentCommit`) are ignored — they belong to
  a superseded run and must never flip the state of the current run.
- A closed/merged/declined PR cancels the in-flight run (CANCELLED); a reopened PR starts fresh via
  `RequestReview`.
- Conversational threads never block or change the main `status`.

**Idempotency keys**
- Consumers dedup on `eventId`.
- `RequestReview` idempotent by `(reviewId, commit)` (unless `force`).
- Workers idempotent by `causationId` (never re-fetch/re-generate for the same command).
- Comment posting idempotent by `(reviewId, commit, anchorKey)` persisted **before** the external call,
  with reconcile-on-retry (ADR-013; table in DATA-MODEL §5).

## 7. SPI ports (hand-rolled interfaces)

Core abstractions (in `spire-contract`):
```java
interface Decider<C, S, E> { S initialState(); List<E> decide(C c, S s); S evolve(S s, E e); }
interface View<S, E>       { S initialState(); S evolve(S s, E e); }
interface Saga<E, C>       { List<C> react(E e); }
```

Plugin ports:
```java
interface ScmIngress {                                  // gateway
  boolean verifySignature(RawWebhook raw);
  List<IntegrationEvent> translate(RawWebhook raw);     // → PullRequestEventReceived / PullRequestClosed / ManualCommandReceived / AuthorReplied / PushReceived
}
interface DiffSource {                                  // scm adapter
  ScmType type();
  PullRequest fetchPullRequest(RepoRef repo, long prId);
  Diff fetchDiff(RepoRef repo, long prId, String commit);   // canonical FilePatch list
}
interface CommentSink {                                 // scm adapter
  ScmType type();
  CommentRef postSummary(RepoRef repo, long prId, String bodyMd);
  CommentRef postInline(RepoRef repo, long prId, DiffRefs refs, InlineAnchor anchor, String bodyMd);
  CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd);  // ThreadRef, not bare id
  Author     getPullRequestAuthor(RepoRef repo, long prId);
}   // DiffRefs feeds GitLab/GitHub anchoring; ThreadRef = comment id (BB/GH/DC) or discussion_id (GitLab). See SCM-MAPPING.md
interface ThreadSource {                                // scm adapter
  ScmType type();
  ThreadTranscript fetchThread(RepoRef repo, long prId, ThreadRef thread);   // the whole conversation, in order
}
interface IdentitySource {                              // scm adapter
  ScmType type();
  Author whoami();                                      // who the configured token IS — the self-loop guard
}
interface PullRequestSink {                             // scm adapter — the factory opens PRs (M2)
  ScmType type();
  PullRequestRef open(RepoRef repo, NewPullRequest request);              // throws NothingToPropose
  Optional<PullRequestRef> findByHead(RepoRef repo, String head, String base);  // find-first, so a redelivery opens nothing
}   // NothingToPropose normalises "the agent changed nothing", which all four forges report as a 4xx that reads like failure
interface PullRequestLander {                           // scm adapter — the factory lands PRs (M4, GitHub only)
  boolean available();                                  // false by default: land is capability_unavailable there
  LandState read(RepoRef repo, long pr);                // state, merged_by, head/base, mergeable(_state), reduced checks
  AllowedMethods allowedMethods(RepoRef repo);          // allow_*_merge; checked=false when not reported
  MergeOutcome merge(RepoRef repo, MergeRequest request);  // (pr, expectedHead, method): Merged | HeadConflict | Refused (<500) | Uncertain (5xx, I/O)
  BranchDeletion deleteBranch(RepoRef repo, String branch, String expectedSha);   // reads first; check-then-act, not a CAS
}
interface ContextProvider {                             // jira, confluence, issues, rules (shipped) / code (P3) / memory
  String source();
  boolean supports(ContextRequest req);
  CompletionStage<ContextContribution> contribute(ContextRequest req);  // async; may return EMPTY/ERROR
}
interface LlmProvider {                                 // langchain4j default impl
  String id();
  Uni<Completion> complete(Prompt prompt, ModelParams params);
}
interface Capability {                                  // review (v1), describe, changelog…
  String name();
  Set<String> commands();          // e.g. {"/review"}
  CapabilityConfig defaultConfig();
  Uni<Void> run(CapabilityContext ctx);   // ctx exposes diff, assembled context, ports, resolved config
}
```
Discovery: each is a CDI bean; the core injects `@All List<ContextProvider>` etc. Config selects the
active `LlmProvider`/`DiffSource`. Adding a plugin = new bean, no core edit.

## 8. Context-aggregation policy

1. On `GatherContext`, the context-worker computes `expectedSources` = every enabled `ContextProvider`
   where `supports(req)` is true, and emits `ContextRequested{expectedSources}`.
2. Each provider emits `ContextContributed{status}` (`OK`/`EMPTY`/`ERROR`).
3. An aggregator tracks arrivals and emits `ContextAssembled` when **received ⊇ expected** OR a
   **timeout `T` (default 20s)** elapses, recording `contributingSources` and `missingSources`.
   *Shipped form (single worker):* the aggregator is worker-local — an in-process
   `allOf(providers).get(T)` fan-out rather than an event-sourced `View` + timer; a distributed
   arrivals model is only needed once context providers run in separate processes.
4. Guarantees the pipeline never blocks on a slow/broken provider; missing sources degrade gracefully.
   The assembled context is persisted encrypted to the `BlobStore` (Postgres today, DATA-MODEL §4) and
   referenced by `contextRef` on `ContextAssembled`/`GenerateReview`. **Jira is the first live provider**
   (`spire-context-jira`).

## 9. Kafka topics

| Topic | Carries |
|---|---|
| `cs.registry-integration` | Revisioned registration metadata; keyed by registration UUID |
| `cs.repository-integration` | Verified `RepositoryDelivery` envelopes around SCM ingress; keyed by the existing event key |
| `cs.repository-activity` | Existing FACTORY activity publication, keyed by repository UUID; M3 takeover instead consumes the verified original envelope on cs.repository-integration |
| `cs.work-integration` | Bound tracker deliveries, keyed by stable work-item identity |
| `cs.work-events` | Durable work-item event notifications from the encrypted outbox |
| `cs.work-dlq` | Failed tracker and factory-activity processing |
| `cs.run-commands` | Standalone ExecuteRun, held ExecuteWorkRun and read-only ExecuteWorkDraftRun, keyed by run ID |
| `cs.run-control` | CancelRun, SteerRun, PublishWorkRun and HoldWorkRun; each worker consumes control independently |
| `cs.run-results` | RunStarted, RunWorkReady and terminal RunFinished/RunWorkArtifact/RunFailed facts |
| `cs.run-events` | Bounded run transcript facts, independent of workflow decisions |
| `cs.integration` | Retained legacy SCM ingress; new consumers dead-letter it with a provenance repair reason |
| `cs.commands` | action + record commands |
| `cs.events` | aggregate domain events |
| `cs.results` | worker-produced integration events (`DiffFetched`, `ContextRequested`, `ContextContributed`, `ContextAssembled`, `ReviewGenerated`, `ReviewFailed`, `CommentsPosted`, follow-ups). **Short retention** — carries source-quoting payloads without app-layer encryption (ADR-014) |
| `cs.dlq` | dead-letters (after retry budget); surfaced on the dashboard with a replay action (FR-8) |

Review lifecycle messages retain their existing `reviewId` ordering. Repository metadata and
factory activity use the explicit UUID keys shown above.

## 10. Bitbucket **Cloud** mapping

Webhook subscriptions on the bot's repo/workspace hook: `pullrequest:created`, `pullrequest:updated`,
`pullrequest:comment_created`, **`pullrequest:fulfilled`** (merged), **`pullrequest:rejected`**
(declined) — the close events feed `PullRequestClosed` → the cancel saga (+ `repo:push` for P3).
Verify `X-Hub-Signature` (HMAC) with the hook secret.

| Contract field | Bitbucket Cloud webhook path |
|---|---|
| repo | `repository.workspace.slug` + `/` + `repository.name` |
| prId | `pullrequest.id` |
| action | event key: `created`→OPENED, `updated`→UPDATED, `fulfilled`→CLOSED(MERGED), `rejected`→CLOSED(DECLINED) |
| title / description | `pullrequest.title` / `pullrequest.rendered.description.raw` |
| headCommit | `pullrequest.source.commit.hash` |
| sourceBranch / targetBranch | `pullrequest.source.branch.name` / `pullrequest.destination.branch.name` |
| author | `pullrequest.author.{account_id, nickname, display_name}` |
| htmlUrl | `pullrequest.links.html.href` |
| (comment) threadRef/parent | `comment.id` / `comment.parent.id` |
| (comment command) | `ScmIngress` parses the comment body: if it starts with a registered `/command` (e.g. `/review`, from `Capability.commands()`), emit `ManualCommandReceived{command,args}` instead of `AuthorReplied`. Bot-authored comments are dropped before either (ADR-013) |

REST (`api.bitbucket.org/2.0`), auth = bot **App Password** (Basic) or OAuth, scopes `pullrequest:write`,
`repository:read`:
- diff: `GET /repositories/{ws}/{repo}/pullrequests/{id}/diff`
- summary comment: `POST …/pullrequests/{id}/comments` `{content:{raw}}`
- inline comment: same + `{inline:{path, to}}`
- thread reply: same + `{parent:{id}}`
- author: from the PR resource `GET …/pullrequests/{id}`

## 11. Versioning
`eventVersion` starts at 1 per type. Additive fields don't bump it; breaking changes bump it and ship an
upcaster (`vN → vN+1`) in `spire-contract`. Consumers tolerate unknown fields. Published events are immutable.

## Resolved people and repository fix overrides (M3 slice 3)

All person endpoints require `spire-admin` and use the explicitly selected account. A resolution
response contains status, candidates (`providerUserId`, observed `handle`, `displayName`) and a
safe capability explanation. It never contains credentials or email fields.

| Endpoint | Behavior |
|---|---|
| `POST /api/providers/{id}/actors/resolve` | Resolve `{handle, repositoryId?}`; repository scope must be bound to this account. |
| `GET /api/providers/{id}/actors?refresh=true` | Read account policy; optional refresh resolves each stored ID, never its old handle. |
| `POST /api/providers/{id}/actors` | Save `{handle, providerUserId, revision, repositoryId?}` after repeating lookup and by-ID verification. |
| `DELETE /api/providers/{id}/actors/{actorId}?revision=` | Remove an account entry with optimistic policy revision. |
| `/api/repositories/{id}/fix-actors` | Corresponding list/save/delete operations using its reviewer; save additionally requires `effect: ALLOW|DENY`, and the existing actor revision (zero for creation). |
| `POST /api/repositories/{id}/fix-actors/resolve` | Resolve through that repository's selected reviewer. |

Not-found, ambiguous or unsupported identity input returns 422 without writing; upstream
unavailability returns 503. A stale policy, disabled account or missing reviewer returns 409.
GitHub/GitLab support exact handles. Bitbucket/Jira return `SELECTION_REQUIRED`; the browser
must name a returned candidate, and the server repeats that selection check on save. Candidate
IDs are disambiguators in selection controls, not an operator input requirement.

Account create no longer accepts a raw author list: save credentials first, then resolve people.
An ordinary account update can omit `authors` to preserve policy; a supplied list must match the
current list under the account lock. A policy edit during token validation makes the old form
return 409 and rolls back its credential/configuration changes. It cannot replace the list with
unresolved text. Account views include `actorDisplays` for cached labels and stale states. Legacy
unresolved entries are labelled for repair. Repository fix overrides are separate from account
review/conversation policy; /fix uses explicit overrides and measured push access.

## Effective repository permission (M3 slice 4)

RepositoryPermissionSource.permission(RepoRef, providerUserId) returns CAN_PUSH, CANNOT_PUSH or
UNKNOWN with a safe explanation. It reads through the repository's selected reviewer account.
FixAuthorization evaluates unknown identity, DENY, ALLOW, then measured permission. UNKNOWN
records PERMISSION_UNAVAILABLE and refuses dispatch; a prior successful read gives no authority.
The saga records the exact reason as FixAuthorization in the timeline, with the reason and safe
capability detail in the durable refusal. Allowed commands still pass the existing dispatch guards.
The full lookup, including identity, redirects and pagination, is bounded to 20 seconds.
