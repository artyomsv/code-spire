# Factory M3 — work items, labels and gates — design

**Date:** 2026-09-12

**Status:** Accepted with amendments in [Round 2 review](https://github.com/artyomsv/code-spire/pull/153#pullrequestreview-5188278775). Implementation and test evidence remain per-slice obligations.

**Issue:** [#114](https://github.com/artyomsv/code-spire/issues/114), re-read from GitHub,
updated `2026-09-12T21:47:45Z`.

**Plan:** [Ordered slices and proof obligations](../plans/2026-09-12-factory-m3-work-items.md).

## 1. Scope and evidence

M3 makes a tracker ticket the entry point to the factory, with operator-owned policy, attributable
labels, durable approvals and human takeover. It also moves workspace ownership to repositories,
replaces `/fix`'s list-only authorization with repository push permission plus explicit overrides,
and lets people enter handles while authorization continues to use stable provider ids.

The ticket records the live M2 loop on `artyomsv/spire-test#31`, runs `3987682681:1` and
`3987682176:1`: command, dispatch, push to the existing source branch, another review, resolved
thread and persisted verdict. That is the issue's reported evidence, not a measurement made in
this planning round. M3 is unblocked. The older contrary statements in `CLAUDE.md` and
`docs/UNVERIFIED.md` are reconciled in slice 1; the automated GitLab run-unit
network gap remains a separate claim and must not be deleted on the strength of a live GitHub run.

Read alongside [AUTONOMY](../../factory/AUTONOMY.md), [factory PRD](../../factory/PRD.md),
[factory architecture](../../factory/ARCHITECTURE.md), [decisions](../../DECISIONS.md),
[unverified claims](../../UNVERIFIED.md), and the
[Accounts design](2026-09-07-accounts-and-roles-design.md) and
[Accounts plan](../plans/2026-09-07-accounts-and-roles.md). The September 7 documents establish
format and review depth; ADR-041 and the updated issue supersede their deferred account design.

### What exists at branch base `27fe17b`

| Observation | Implementation evidence | Consequence |
|---|---|---|
| Only review events implement `DomainEvent`. | `spire-contract/.../event/DomainEvent.java` | There is no run or work-item aggregate to extend by assumption. |
| Run results update the run projection, charges, credential feedback and PR proposal directly. | `spire-orchestrator/.../factory/RunResultSaga.java` | Keep delivered run durability; introduce workflow ownership deliberately. |
| PR proposal is now called after a finished run. | `factory/FactoryPullRequests.java` | Item delivery must intercept this path or a gated item will open a PR early. |
| Account lookup is by type, workspace and scalar role; a workspace-only fallback still exists. | `provider/ProviderRegistry.java`, `factory/MachineAccounts.java`, migration V44 | Changing only the form or UNIQUE key cannot move workspace ownership. Every resolver needs repository coordinates. |
| CONTEXT rows have null workspace; REVIEWER/FACTORY rows must have one. | Orchestrator V59, `scm_provider_workspace_by_role` | Both this CHECK and the old UNIQUE constraint need migration. Account ids and encrypted credentials can stay intact. |
| Gateway owns `webhook_repo`, including secrets and org/repo scope. | Gateway V1; `registry/WebhookRepoRegistry.java` | No cross-schema FK, SQL join, or orchestrator credential lookup at webhook ingress. |
| The keyed edge verifies signature, then every event's scope, then publishes. | Gateway `RegistryWebhookEdge.java` | Add tracker scope and event-kind validation here; Jira cannot be forced into `RepoRef` parsing. |
| `/fix` has two list barriers. | `IntegrationSaga.onManualCommand` and `requestFix` | Replacing only `allowedById` still denies a push-authorized person before the switch. |
| Context clients expose only `getJson`, sharing `PinnedJsonClient`. | `GitHubIssueClient`, `GitLabIssueClient`, `JiraClient` | Reuse transport and auth configuration, but keep writes off the context-reader API. |
| The existing sink API has no draft flag. | `PullRequestSink.NewPullRequest` | `draft_pr` is actual adapter work, not a label to paint on a regular PR. |

Paths abbreviated with `...` above are under `src/main/java/dev/codespire/<module>/`.
The implementation plan names exact source roots for new files.

## 2. Decisions and ADRs

These decisions are accepted by the review; ADR records land with the implementing slices.
Reserve the next available numbers at
implementation time; `042`–`045` are the expected sequence after ADR-041.

| ADR | Decision to record | Existing decisions affected |
|---|---|---|
| ADR-042 — Repositories own workspace and account bindings | Account identity is its UUID; repository identity includes forge origin. Remove UNIQUE `(type, workspace, role)` and the workspace-by-role CHECK. Explicit repository-role bindings replace workspace resolution. Gateway owns webhook registrations independently. | Completes ADR-041 deferrals; preserves scalar roles and separate identities under ADR-038. |
| ADR-043 — A work item owns workflow milestones; a run remains a durable execution record | Add an event-sourced `WorkItemLifecycle`, transactional milestone/gate/outbox persistence, and separate work-item keys. Do not invent or backfill a run aggregate. | Clarifies ADR-034's aspirational milestone catalogue; generalizes ADR-010's single-writer rule to one writer per aggregate stream. |
| ADR-044 — Command authority uses stable identities and effective repository permission | `/fix`: explicit deny, explicit grant, then measured push permission. Handle resolution uses the selected account's credential; display names grant nothing. | Replaces the temporary M2 list-only guard; does not alter reviewer eligibility or tracker actor authority. |
| ADR-045 — Profiles have explicit precedence; authority is bounded per dimension | Versioned immutable profiles, operator-declared precedence, component-wise restriction, current labels/allowlist/ceiling at every boundary, version-bound gates and takeover precedence. | Makes ADR-033's “lowest” and “ceiling” implementable for vectors; records the M3/M4 boundary after analyst resolution. |

### 2.1 The aggregate decision

**A work-item aggregate will exist. A separate run aggregate will not.** A run remains the delivered
`factory_run` record plus `llm_charge`; `run_event` remains a bounded, encrypted transcript, never
state. Do not create synthetic historical `RunStarted` domain events or replay transcripts to
reconstruct runs. Amend ADR-034 to distinguish its intended milestones from the shipped run tier.

The work item owns admission, the pinned policy version, phase cursor, gates, run associations,
retirement, takeover and completion. These decisions must survive restart, concurrent approvals,
duplicate deliveries and policy edits. A pure `WorkItemLifecycle.decide(state, command)` is their
single domain-event writer. Rehydration folds only recorded milestones and never calls a tracker,
checks today's policy, spends money or emits commands. A new decision receives current authorized
facts separately. Workers and webhooks still emit integration events; the saga converts them into
aggregate commands.

Add work-item milestone records to `DomainEvent` and implement the lifecycle under
`spire-contract/.../lifecycle/`. `EventEnvelope` already has a generic stream id and payload.
Add typed work-item payload decoding and routing: today's `DomainEventSink` writes every envelope
to review history before its switch, so its default branch is not sufficient isolation.
`ReviewLifecycle` must reject work-item payloads and vice versa. Pure modules gain no framework
imports; extend the contract snapshots to the new nested wire types explicitly.

**Why not just mutate `work_item` and emit an audit row?** That makes gate answers and phase changes
two sources of truth unless every writer implements the same concurrency discipline. A small pure
aggregate provides one decision table and replayable authority changes. Conversely, introducing a
second aggregate for every run would duplicate the delivered run lifecycle without solving a new
M3 invariant. Work-item events refer to run outcomes by id and result identity; the run record stays
authoritative for execution details and charges.

### 2.2 Transactions and transport

Use `event_log` for work-item streams, with a `work-item::` discriminator in the derived stream id.
Use separate `cs.work-integration` and `cs.work-commands` topics keyed by workItemId; `cs.events`
retains envelopes keyed by their own stream id. Run commands/control/results remain keyed by runId.
Update topic provisioning, serializers, retention and `spire-arch` checks together. Never put a
work-item command on the review worker's `ActionCommand` consumption path.

One orchestrator transaction locks the item/version, checks the policy revision used to decide,
appends the expected event sequence, updates `work_item` and `work_item_gate`, records the input
deduplication key, and writes outgoing effects to a work-item outbox. A conflict reloads and
re-decides; it does not reuse a stale decision. The transaction must share one JDBC connection:
calling today's independently connected `JdbcEventStore.append` beside another repository write
does not make them atomic. Introduce a connection-scoped append implementation and retain the
existing `EventStore` adapter for review callers. Test actual rollback with PostgreSQL.

Outbox delivery is at least once with stable effect ids. Mark sent after broker acknowledgement.
Consumers deduplicate by effect/delivery id, not receipt timestamp. A committed gate survives a
crash before publishing; a redelivery cannot open a second gate or dispatch a second run. Current
item and policy are checked again before releasing an unstarted effect. Disabled sources, unknown
policy, stale observations and transferred issues cannot authorize new effects.

Repository registration snapshots need their own registry channel, keyed by gateway registration
id rather than workItemId. Add `cs.registry-integration` and its durable gateway outbox in slice 1;
do not force configuration snapshots through either a review or a work-item aggregate stream.
Gateway webhook success still waits for broker acknowledgement, as `IntegrationPublisher` does
today. A failed publish returns a retryable failure, never an accepted-but-lost delivery.

Tracker comments and PR creation also need recoverable side-effect records. Use deterministic
comment markers and read-before-retry, with bounded retries and an explicit uncertain state when
the tracker cannot establish whether a timed-out write succeeded. Do not claim remote exactly-once
behavior. Existing M2 standalone run proposal behavior remains; item-linked runs are proposed only
by the item's delivery effect, not `FactoryPullRequests.propose`'s unconditional BUILD path.

## 3. Repository and account ownership

### 3.1 Registry model

In the orchestrator schema introduce:

| Table | Essential fields and constraints |
|---|---|
| `repository` | `id UUID`, `scm_type`, canonical `forge_origin`, `workspace`, `slug`, provider repository id when known, enabled, revision, timestamps. UNIQUE `(scm_type, forge_origin, workspace, slug)`. One workspace per repository now. |
| `repository_account` | repository id, account id, role; UNIQUE `(repository_id, role)` for REVIEWER and FACTORY. An account can serve many repositories. References block account deletion. |
| `repository_fix_actor` | repository id, stable actor id, effect `ALLOW` or `DENY`, observed handle and resolution time; one effect per actor. |

`scm_provider.id` stays the credential identity and Tink AAD stays `provider:<id>`. Do not copy or
re-encrypt account secrets for this key change. Do not replace the old uniqueness with
`UNIQUE(type, role)` or with a uniqueness on handle, bot id or token: multiple credentials at the
same host and role are legitimate. Keep immutable scalar roles. Account kind/origin changes with
references are refused pending reassignment; token rotation updates all consumers as in ADR-041.

Binding checks enforce matching forge kind and normalized API origin, the selected account's role,
enabled state at use, and distinct resolved reviewer/factory identities when both are known.
No resolver silently selects the first account with working credentials. Repository views name
the configured account even if disabled or unreachable and distinguish selection from measured
reachability/permission. CONTEXT sources continue to use their explicit account references.

Repository lookup must carry host as well as type and path; a self-managed forge and its cloud
counterpart may contain the same namespace. Existing ambiguous legacy review coordinates are
shown as needing repository assignment and cannot dispatch a factory run. Do not broaden this
milestone into rewriting every historical review id or charge reference.

Replace `resolve(type, workspace, role)`, `registration(...)` and `resolveByWorkspace` on active
paths with `RepositoryAccounts.resolve(repositoryId, role)`. `ReviewProviderResolver`, manual
registration/rerun, prompts, `IntegrationSaga`, `MachineAccounts`, run resources, fix dispatch and
PR proposal must all use it. Serving chips call the same resolver's non-secret view.

### 3.2 Migration and rollout

Use an expand/bridge/contract migration, with independently numbered orchestrator and gateway
Flyway files (next orchestrator number is expected to be V60; verify before allocating).

1. Create repository/binding tables while old workspace resolution still serves existing traffic.
   Snapshot old `(account id, type, origin, workspace, role)` assignments into a migration-only
   mapping table. Keep credentials and ids unchanged. Add nullable repository references to
   existing review/run records; do not invent a host when historic records cannot establish it.
2. Bootstrap repositories from real known review/run coordinates and gateway registrations.
   Gateway publishes a versioned, non-secret registration snapshot through a durable outbox;
   orchestrator consumes it idempotently. No cross-schema SQL access. Bind a role only when the
   old assignment and host identify exactly one account. Ambiguous rows remain pending with an
   attention entry; count and report them. No placeholders that look like actual repositories.
3. Preserve org webhook coverage during the bridge. A verified event for a previously unseen
   repository may materialize a real repository using the snapshotted legacy assignment, with
   origin supplied by its registration. It must not inherit a different host or newer account by
   workspace alone. Org auto-enrollment ends at cutover. Afterwards a verified event naming an
   unregistered repository raises an attention row naming repository, forge origin and incoming
   registration id, with a Register action pre-filled from those three. No silent drop and no
   automatic inheritance of workspace accounts. One source/ISSUE hook per repository and the
   REVIEWER/FACTORY/ISSUE kinds are confirmed.
4. Cut over every runtime resolver and the repository screen together after mapping checks pass.
   Unresolved repositories fail closed for action with a named repair path. Remove account
   workspace input and validation, drop the old UNIQUE and workspace-by-role CHECK, but keep
   `scm_provider.workspace` populated with its existing values until slice 10 as rollback evidence.
   No production code may read it after slice 2; a build guard enforces that rule. Slice 10 owns
   the explicit column-drop migration. The migration-only snapshot is
   explicitly excluded from account selection after cutover. Preserve source credential recovery
   columns from V59; their removal is unrelated to this migration.
5. Update both packaged compositions, local dev configuration and upgrade instructions for the
   new wire fields. Gateway/orchestrator upgrades need a compatible overlap. A new consumer can
   read legacy registrations during the bridge; after cutover an old payload with ambiguous
   repository identity is refused. No claim of binary downgrade after contracting columns.

Test migration from V59 with real PostgreSQL/Flyway, distinct hosts, nested GitLab namespaces,
multiple roles, disabled accounts, context references, an org registration and an interrupted
snapshot exchange. Prove identical credential decryption before and after, exact row mappings,
idempotent restart and refusal of ambiguous mappings. Do not exercise this on the running dev DB
in a test. The bridge must preserve its existing webhook keys and encrypted secrets.

Before any new migration can reach the real dev stack, slice 1 takes and validates a full
`pg_dump` into the session scratchpad. The plan includes the exact binary-safe command. Preserve
the matching existing keyset outside git and capture a credential-continuity proof using real
rows and their actual Tink AADs. Slice 2 compares decrypted credentials against that baseline on
the real dev rows after cutover; matching fixture data or matching ciphertext alone is insufficient.
Only counts/ids and comparison outcomes are reported, never plaintext credentials or keysets.

### 3.3 Repository screen and webhook model

`#/settings/repositories` becomes a repository list and detail, not a list of webhook rows. Register
a repository by selecting forge/host, entering workspace and slug, and selecting accounts for the
roles that may act. A repository may exist with no webhook or no factory account; its state says so.
Its detail shows **Workspace**, **Accounts**, **Webhooks**, **Work sources** and **Autonomy**.

Gateway retains webhook ownership and its own admin API. Extend registrations with a logical
repository id (no cross-service FK), source id where applicable, canonical origin, revision and
event kind. UNIQUE `(repository_id, event_kind)` means one active registration for each product
kind, not one hook per low-level forge action. Proposed kinds:

| Kind | Accepted events and effect |
|---|---|
| `REVIEWER` | Existing review PR lifecycle and discussion commands, including `/fix`. |
| `FACTORY` | Branch pushes, PR human activity, PR approvals and delivery/merge observations for linked work items. |
| `ISSUE` | Tracker issue/label/comment/transfer events for the repository's work source; the future product-owner role is not introduced here. |

Separate normalized event types prevent a duplicated PR comment delivered to both hooks from
dispatching twice: the reviewer route parses commands; the factory route observes activity. Keep
source delivery identity across both routes and deduplicate logical effects. A command or gate
answer recognized on one channel must not later be interpreted as unrelated takeover (§8).

The gateway rejects a validly signed payload for the wrong repository, tracker project or event
kind before publishing anything. A Jira project is validated against its work-source scope and
mapping, not compared with an SCM workspace. Webhook keys are routing identifiers, never a
substitute for provider-supported signature/token verification. A Jira installation that cannot
authenticate deliveries safely requires polling; it does not get a secret-in-URL bypass.

The UI composes the two authenticated APIs; no browser or orchestrator receives a webhook secret
on read. Creation shows its secret once. Because registration spans two services, save the repository
first, then create each hook idempotently. Partial failure leaves an honest “webhook setup pending”
row with Retry, rather than rolling back a repository that may already be referenced. Legacy routes
and `?edit=` continue to open the matching repository or a clearly labelled legacy org registration.

## 4. People, handles and `/fix`

### 4.1 A stable id with a readable label

Add an account-scoped identity directory port alongside `IdentitySource`: exact handle lookup
and stable-id lookup return `{providerUserId, handle, displayName}` with typed not-found,
ambiguous, unavailable and unsupported outcomes. Composition belongs in `ProviderClients`; adapter
URLs, escaping and response parsing stay in the three SCM modules. Work-source identities use
their own adapter and source account. No email is persisted or logged.

Existing account policy entries are edited through an authenticated admin endpoint such as
`POST /api/providers/{id}/actors/resolve {handle}`. New accounts can first be saved as credentials
and then have policy entries added. Repository fix overrides resolve using its selected reviewer's
account. The server performs the resolution again on save, or verifies an account/revision-bound
resolution token; it does not trust a submitted id/handle pair from the browser. Failed or ambiguous
lookup returns 422 and writes nothing; upstream unavailability is a retryable 503, never a text entry.

Authorization stores and compares the stable id in the provider's actual namespace. An observed
handle and timestamp are display metadata, refreshed by id; a rename updates the display, a
reassigned handle never changes the stored id. If a refresh fails, show the last known handle as
stale or a labelled unresolved id, never `1` as a substitute for identity. Legacy raw numeric ids
remain valid ids; legacy raw handles are flagged for explicit re-resolution and do not acquire
`/fix` authority merely because the string now resolves to someone.

For GitHub and GitLab, exact `@handle` input is meaningful. Bitbucket privacy-era nicknames and
Jira display names need not be unique, resolvable handles. Do not implement a first-search-hit
fallback. Those forms need a credential-backed, disambiguated person selection when exact
resolution is unavailable. This is an explicit portability qualification for criterion 6 (§11).

### 4.2 Authorization decision

Use a pure `FixAuthorization` decision over actor identity, explicit repository override and a
measured `RepositoryPermission` result. Decision order:

1. Reject unknown actor, unresolved repository/account, self-command and ordinary existing
   observe/archive/target precondition failures. No author-equals-PR-author shortcut.
2. Explicit `DENY` refuses even a repository owner. Explicit `ALLOW` authorizes even a reader.
   Overrides grant the command only, never permission to bypass forks, trunk protection, caps,
   observe-only mode, entitlement or invalid findings.
3. Without an override, authorize only `CAN_PUSH`; `CANNOT_PUSH` refuses and `UNKNOWN` refuses
   with a permission-unavailable explanation. Use the repository's assigned reviewer credential,
   without falling back to a stronger factory token or another host's token.

`/fix` must leave the common legacy author-list path in `onManualCommand` and go through this
decision instead. The common self-loop and observe checks still apply. `/review`, `/finding`,
review eligibility and conversation policy retain their current behavior. Keep their old account
list separate from the new bidirectional fix overrides. Migrate verified stable-id entries as
explicit grants to repositories previously served by that reviewer; an empty list produces no
overrides, so actual push permission decides. Present those migrated grants in the repository UI.

Check permission at dispatch time, not merely during account Check. Cache display observations
only; a prior success during an outage is not new authority. Bind lookup to repository origin,
account id/revision and actor stable id. If the provider accepts a handle in its permission URL,
verify that the returned user is the same stable actor before accepting its permission.

### 4.3 Provider endpoints and limits

Checked against official API documentation on 2026-09-12; contract tests and live probes are still
required. Repository push permission means general code-write access, not a promise that a
particular protected branch accepts a push. The existing target and publisher guards still apply.

| Forge | Permission read | Interpretation |
|---|---|---|
| GitHub | `GET /repos/{owner}/{repo}/collaborators/{username}/permission` | Use the effective `permission` base role: `write` or `admin`; maintain maps to write, triage to read. Verify returned user id. [Official contract](https://docs.github.com/en/rest/collaborators/collaborators#get-repository-permissions-for-a-user). |
| GitLab | `GET /projects/{id}/members/all/{user_id}` | Includes inherited/invited membership. Known active Developer/Maintainer/Owner levels allow general push; read roles refuse. Unknown/custom capabilities require evidence, not numeric guesswork. [Official contract](https://docs.gitlab.com/api/project_members/#retrieve-a-member-of-a-project). |
| Bitbucket Cloud | `GET /workspaces/{workspace}/permissions/repositories/{repo_slug}` | Read the matching stable user through all pages; `write`/`admin` are effective rights including groups. This endpoint requires repository-admin access from the caller. The `permissions-config/users` endpoint measures explicit grants only and is unsuitable. [Effective-permission contract](https://developer.atlassian.com/cloud/bitbucket/rest/api-group-workspaces/#api-workspaces-workspace-permissions-repositories-repo-slug-get). |

403, timeout, rate limit, incomplete pagination and malformed/identity-mismatched responses cannot
grant the command. A 404 is not automatically proof that a person has no rights: adapters must
distinguish an unreadable repository from a known absent member where the API permits it. Both
refuse; the displayed reason differs. For Bitbucket a reviewer lacking the documented admin access
will report unknown; the accepted design requires an explicit capability error and an operator-facing credential prerequisite.
Do not increase any live account's rights as part of implementation.

## 5. Work sources and label evidence

Create pure SPI module `spire-worksource`, with arms `spire-worksource-github`,
`spire-worksource-gitlab` and `spire-worksource-jira`. Reuse each context adapter's client/auth
configuration and `spire-http` transport. Refactor common provider transport into a reusable
internal component and expose a distinct write-capable facade to the work-source arm. Context
providers must still be unable to comment or transition through their public read-only interface.
Use the existing module licensing split and add the new modules to build and architecture checks.

`WorkSource` offers capabilities, paginated candidates, fetch, comment, transition and paginated
`labelEvents`. A fetch returns transient ticket content and canonical identity. It is never a row
to persist wholesale. A source registration owns type, origin, external project/repository scope,
target repository id, explicit account reference, enabled state, revision, scan cursor and stable
tracker actor allowlist. Matching kind, origin and auth are checked like context sources. A forge
source can use its factory account for writes; an Atlassian source references the existing
Atlassian account. No new “product owner” role is needed to call a work-source port.

One work-source registration targets one repository in M3, matching one ISSUE webhook per
repository. GitHub/GitLab issue coordinates must agree with that source. Jira's project mapping
is operator-owned and may target an SCM repository on a different service; the tracker credential
never travels there. Multi-repository issue routing is a future schema extension, not a label trick.

### 5.1 Identity and bookkeeping

Derive workItemId from versioned, length-safe encoding of `(scm type, forge origin, workspace,
slug, work-source type, tracker origin, external project id, stable issue id)`. Do not use account
id, handle, mutable issue key, source display name or title in the key. Duplicate registrations
for the same source/target are refused. Re-admission of the same identity advances a generation;
run attempts never reset to an already used id. A bounded hash/encoded subject links through the
existing `RunIds` contract; add `factory_run.work_item_id`, generation and phase references, not
a second incompatible parser for legacy run ids.

`work_item` contains only coordinates, generation, admitted profile/version, selected/effective
policy references, phase, workflow state/reason, revision and timestamps. It has **no issue title,
body or tracker status column**. Workflow state is named `workflow_status` to prevent that
confusion. Branch/PR/run links and human-supplied artifact references are workflow bookkeeping.
An optional live title/body on detail is fetched from the tracker for that request; a failed fetch
shows “tracker unavailable” while the durable workflow remains inspectable.

Raw webhook bodies and fetched tickets must not leak into a generic durable inbox or timeline.
Persist only normalized control facts. Notes that may quote ticket/code text, gate notes and outbox
payloads carrying such text are Tink-encrypted with item/gate/effect AAD. Clear identifiers and
reason codes remain queryable. Existing run task storage retains its encryption boundary.

### 5.2 Labels have authors, removals and provenance

Proposed `LabelEvent`: stable source event id, issue ref, label, `ADD|REMOVE`, tracker actor id,
occurred time, provider ordering token when available and origin `WEBHOOK|AUDIT_TRAIL|UNATTRIBUTED`.
The earlier sketch omitted removal and event identity; both are needed to prevent a replayed old
addition from resurrecting authority. The **applier of the current addition** matters, not the
issue reporter, assignee, most recent issue editor or person who created the label definition.

Reconcile current labels with paginated label audit and verified webhook evidence. A later remove
invalidates earlier attribution; a re-add needs its own actor. An audit gap or ambiguous ordering
produces `UNATTRIBUTED`, never attribution borrowed from an earlier incarnation of the label.
Polling after downtime and initial backlog scan run the same policy path as webhook intake.
Checkpoint only after admission/reconciliation commits; redelivery is safe. Avoid resetting the
scan cursor on every restart or persisting fetched ticket content to make polling easier.

Adapter implementation must verify GitHub issue timeline label events, GitLab resource label
events, and Jira changelog label deltas (including pagination and attribution) against their
documented contracts. Source capabilities report where audit or transitions are unavailable.
Unsupported audit is an honest loss of automation: present labels without a proven applier select
nothing. Deletion/move is distinguished from token outage; inaccessible is suspended, not retired.
A confirmed transfer retires the old item, closes gates, cancels unstarted effects and requires
explicit admission under the new repository's policy; it never silently continues.

## 6. Policy, versions and the phase boundary

### 6.1 Named precedence and bounded vectors

Store `autonomy_profile` and immutable `autonomy_profile_version` rows, repository ceiling and
label mappings in the operator registry. Profiles carry the eight phase modes, gate TTL, run/step/
wall-clock/cost/call caps and protected paths. Omitted phases are `off`. Validate phase-specific
vocabulary: ordinary phases `off|approve|auto`, deliver `off|draft_pr|pr`, land
`off|approve|auto_if_green`. Reject unknown modes, invalid caps, absent referenced versions and
labels mapped to deleted profiles. Unknown wire modes render unknown/refused, never green.

“Lowest wins” needs an order; names are not comparable and vectors can be incomparable. Even the
published examples cross: suggest has `plan:auto`, assisted has `plan:approve`. A globally monotone
chain would reject those examples. Propose an explicit unique precedence number per profile,
owned/versioned by the operator, for selecting among labels and identifying an above-ceiling label.
The three examples order suggest, assisted, autonomous; their names are not dispatch cases.

Precedence never substitutes for a permission bound. Effective modes are the component-wise meet
of selected, pinned and ceiling vectors: `off < approve < auto` for ordinary phases,
`off < draft_pr < pr` for delivery and `off < approve < auto_if_green` for land. Numeric maxima
take the minimum; protected paths take the union plus the immutable CI floor. No glob containment
solver is required. Where the result is a composite vector, display the selected profile and the
limiting ceiling plus actual phase modes, not a claim that it equals an unmodified named profile.
Reject duplicate precedence, missing versions and invalid modes; accept cross-cutting vectors only
with this meet. The review accepted this ordering/composition rule. **The effective vector is
never above any applied label in any component.** Here applied means current mapped labels with
proven, allowed appliers; ignored labels have no authority. Meet every eligible label's vector,
not just the lowest-precedence display selection, plus the pinned admission vector and ceiling.

Initial examples explicitly declare `intake: auto`; copying the abbreviated AUTONOMY YAML without
that field would correctly default intake to off and admit nothing. Use these complete vectors:

| Profile | intake | spec | plan | build | verify | review | deliver | land |
|---|---|---|---|---|---|---|---|---|
| suggest | auto | auto | auto | off | off | off | off | off |
| assisted | auto | auto | approve | auto | auto | auto | draft_pr | approve |
| autonomous | auto | auto | auto | auto | auto | auto | pr | auto_if_green |

These are desired permissions, not claims that M4 executors exist. Missing verify/review/land
capabilities still block their transitions; the M3 journey proof must show that boundary honestly.

At admission pin the chosen profile id/version and the mapping revision used. Compute the most
restrictive eligible label selection and the current ceiling; persist requested/effective selection
and why a clamp occurred. At later transitions re-read current labels, source allowlist, enabled
states, mappings and ceiling. Intersect the admitted version with current restrictions. A removed
label, disallowed applier or new lower label can narrow/stop an item. A higher label, raised ceiling
or edited version cannot widen its admitted authority. An operator explicitly re-admits to move to
a new version/generation. A lower ceiling's current version restricts the pinned vector; it never
silently replaces the admitted version's more restrictive fields.

Store the applied policy revision and provenance at each decision so the screen can explain it.
Ceiling clamps produce a durable timeline entry and condition-based attention row while the
current selection is clamped. Deduplicate repeated observations of the same clamp. Invalid labels
each have an ignored reason; if another valid label remains, it can select a profile. If none
remain, `not_eligible` stops automation with the specific underlying reason visible.

### 6.2 Every transition is a real check

One `WorkItemTransitions` service is called for admission, phase completion, gate approval,
retry, run-result continuation, delivery, land, operator resume and explicit re-admission.
Expiry, takeover and retirement invalidate pending effects as well. Scheduled/outbox retries
cannot bypass this service. Fetch external evidence outside a DB lock, then compare its source/
repository/policy revisions under lock; stale or failed reads cause waiting, not permission.
External revocation and a local dispatch cannot be globally atomic: the guarantee is a fresh
observation at each transition, not instantaneous revocation of a push already accepted remotely.

A lowered ceiling stops advancement at the next phase. If its mode becomes `approve`, open a new
version-bound gate before proceeding; if `off`, record `not_eligible` and stop. It need not kill
the phase already running. A gate approved against old policy or an older artifact/head is stale
and cannot authorize the new transition. Failed verify is not item success; M4 owns retries and
step verification. Budget limits narrow existing SpendGate/FR-F32 checks and include call count
on unmetered deployments. Reserve a dispatch slot atomically and release it on refusal/expiry;
do not claim hard monetary reservations eliminate the documented in-flight spend softness.

### 6.3 M3 journeys versus M4 execution — accepted boundary

FR-F17 spans M3/M4. M4 explicitly owns generating specifications/plans, multi-step execution and
verification. M3 cannot label no-op phase handlers “complete” to manufacture three green journeys.
Accepted M3 boundary: implement the real phase state machine and manual tracker-artifact handoff,
then reuse M2 for **one already specified build task**. Humans can register references/digests to
a specification and a single-step plan actually present in the tracker. Those artifacts are fetched
and validated; they are not copied into `work_item`. Missing execution capabilities show
`awaiting_input` or `capability_unavailable`, never a fake successful phase.

With the same prepared task and three profile labels the runnable control-plane proof is:

| Profile | Visible journey in M3 |
|---|---|
| suggest | Admit; record the human-provided specification/plan references; stop before build (`off`), zero runs, no PR. |
| assisted | Admit; visibly wait on a durable plan gate with zero runs. Approval admits one build; then wait for any missing verification capability. Its eventual permitted delivery is a draft PR. |
| autonomous | Admit; plan proceeds without approval and starts one build immediately; then wait for any missing verification capability. Its eventual permitted delivery is a regular PR. |

No `auto_if_green` implementation or automatic tracker closure is implied by a green unit test.
Criterion 1 is proved at the real plan/build boundary: suggest stops, assisted waits for approval,
autonomous builds. After approval, assisted's history still records its distinct human decision.
Draft/regular delivery tests use an explicitly identified test phase driver to supply verification
evidence; this is adapter/control-plane coverage, not proof of a shipped M4 verifier. Production
with no verifier remains waiting. The review accepted this plan/build-boundary proof; generated
specification, multi-step planning and verification executors remain M4 work.

Delivery/review ordering is corrected by the review: the published eight-phase diagram places
review before deliver, but the existing reviewer requires a pushed PR. Proposed execution records
PR opening as the delivery effect, then observes the existing reviewer before any land decision;
it does not report a review that could not have run. Preserve phase identifiers in the policy
vector; record `intake → spec → plan → build → verify → deliver → review → land` in ADR-045 and
fix the eight-phase diagram in `docs/factory/AUTONOMY.md` in slice 8a, together with affected
architecture/PRD diagrams. The diagram is wrong; the implemented reviewer is not changed to fit it.

**Publication is part of that decision too.** M2 builds already push before `RunFinished`; merely
gating the later PR API call cannot enforce a deliver mode of off. Proposed item-linked execution
starts with publication held, checkpoints local work and emits a durable `RunWorkReady` integration
result before push. The worker releases active compute while preserving the workspace and a
durable awaiting-delivery record. Only a current, item/generation-bound delivery permit resumes
trusted publisher finalization; it cannot rerun the build or accept a repository-authored permit.
Standalone M2 runs retain their existing automatic push. Expiry/retirement/takeover leave work
preserved without publishing, and orphan recovery honors the hold. This introduces a run state
and control/result messages, not a run aggregate. The design must establish charge reporting at
work-ready/final completion without double counting, and the artifact/verification boundary before
granting delivery. This is its own slice **8b**, following 8a; slices 9 and 10 keep their numbers.
Its two-part exit requires item-linked publication hold through restart **and** a standalone
`/fix` still pushing automatically, re-proved live on `artyomsv/spire-test`. Unit tests cannot
replace that second proof. The current M2 worker does not already support the hold.

## 7. Durable approvals

`work_item_gate` records id, item/generation, phase, expected item/policy version, artifact digest
or PR head, opened/expiry timestamps, status (`OPEN|APPROVED|REJECTED|EXPIRED|SUPERSEDED`), resolver
stable identity/channel, deduplication key and encrypted note. One current open gate per item,
generation and phase. It is a synchronous transactionally maintained query of aggregate state;
the event log remains rebuildable truth. Concurrent responses use expected version and a
conditional OPEN transition. One wins; replay of its idempotency key returns the stored outcome;
a conflicting answer returns 409. At `now >= expiresAt` expiry wins even if a scheduler is late.

| Channel | Authority and binding |
|---|---|
| Dashboard | Existing authenticated operator authorization (`spire-admin` for gate mutations initially); server derives resolver from verified OIDC subject. Viewer can read only within existing access rules. |
| Tracker | Allowlisted actor in this work source; authenticated delivery; explicit command such as `/approve <gate-id>` or `/reject <gate-id>` binds the generation and artifact. Ordinary comments do not approve. |
| PR review | Current approval of this linked PR's current head by a human with measured repository push permission and no deny override. It can answer only a land gate, never a plan gate. Re-read review state; dismissed/stale approvals do not count. |

All channels become the same `ResolveGate` command and `GateResolved` milestone. Tracker label
answers are deferred unless a gate-specific label can carry unambiguous generation and attributable
actor; “a comment or a label” does not require implementing an unsafe generic approve label.
If a forge cannot prove a PR approval, its capabilities disable that channel visibly; dashboard
and tracker remain usable. Expiry is a persisted `WorkItemRefused(gate_expired)` with reservation
release in the same transaction. A restarted sweeper expires overdue gates. Retrying a refused
item needs explicit re-admission, not reopening the old approval.

## 8. Human takeover

Signed push/PR activity on an item-linked branch/PR records `human_takeover` and suspends new
automation until an operator resumes. Compare the actor's stable id against the item's recorded
factory identity and assigned reviewer identity, not display names, author strings in commits or
the current factory account after it has been rotated. Unknown origin suspends conservatively.
Repo/head links must match; unrelated branches cannot suspend an item. A bot's observed push
does not count as a person, even after an account has been renamed.

Gate answers and authorized `/fix` commands are deliberate workflow actions; classify and
deduplicate them before generic comment takeover. This is a proposed precedence rule resolving
FR-F22's literal “commenting” against FR-F25's tracker/PR answer channels; record it in ADR-045 with the FR-F22/FR-F25 conflict named.
Normal human comments and pushes take over. A PR approval is processed as a gate response only
when it actually matches an open gate; it cannot accidentally resume a suspended item.

Takeover cancels unstarted outbox effects, supersedes pending gates and requests active-run stop.
**Normal M1 cancel salvages and may push. It is insufficient for takeover.** Introduce a durable
publication hold for item-linked runs: preserve local work while suppressing further pushes and
PR creation after the hold is observed. Carry the hold through run control, worker durable state,
publisher finalization and orphan salvage; a restart must not restore publication authority.
Publication already in progress cannot be recalled; record its outcome and keep the item
suspended. Never claim atomic ordering between a remote human push and our webhook receipt.
The run-plane mechanics and the interaction with continuous checkpoints need explicit tests in
slice 9, not just a saga fake. Existing standalone cancellation retains its salvage contract.

Resume is an authenticated operator action with expected version and a note. Re-fetch repository/
issue/head, re-resolve policy and open any new gate before continuation. A retired item cannot
resume; it requires a new identity/admission. No automatic resume on a bot comment or on a new label.

## 9. Screens, resources and observability

| Route / API family | Behavior |
|---|---|
| `#/settings/repositories`, `/api/repositories` | Repository registration/detail, workspace, exact role bindings, per-kind hooks, source/policy setup and fix overrides. Non-secret account views. |
| `#/settings/accounts`, `/api/providers` | Identity, credential, scalar role and Used by; no workspace control. Handle entry renders people, not a count alone. |
| `/api/work-sources` | Admin source registration, actor allowlist, Check and bounded rescan. Uses an existing account, never a second token form. |
| `/api/autonomy-profiles`, repository policy subresource | Versioned profiles, mappings and ceiling; optimistic revisions on edits. |
| `#/work-items`, `/api/work-items` | Paged durable list by repository/source/workflow state/profile. Detail: tracker link, current phase, requested/effective profile, ignored labels, clamp reason, gates and links to real runs/PR/review. |
| `#/approvals`, `/api/approvals` | Open approvals with expiry, phase, artifact/head and decision note; authorized approve/reject. Separate history query for resolved gates. |
| Existing attention API | Current open gates, effective clamps, unknown permission/account mapping and failed source health. Resolving the condition removes its row. |

Resources enqueue durable commands and return 202 plus a command/item id; they do not hold an HTTP
request open for a phase. Configuration writes retain ordinary synchronous registry semantics.
Read APIs expose a revision for bounded polling/live updates and explicit errors on source fetch
failure. UI statuses, labels, pipeline renderer, filters and unknown-state handling land together.
Keep new React components below 250 lines and eight state hooks, using existing controls/icons.
Never label a scheduled test fixture or an unsupported phase as a live completed item.

## 10. Proof strategy and excluded work

The [plan's acceptance matrix](../plans/2026-09-12-factory-m3-work-items.md#acceptance-proof-matrix)
names an executable test for each of the seven ticket criteria and an isolated, compiling mutation
that must kill exactly one discriminating test. It also covers migration, replay, concurrency,
expiry, transfer, channels, takeover and missing capabilities. Test names are proposed additions;
none are represented as tests that exist or have passed today.

M3 excludes M4-generated specification/plan, multi-step continuity, repository verification runners,
automatic merge implementation without a separately accepted scope decision, model quality claims,
new runtime/harness arms, a product-owner role, context auto-discovery, account-role merging and
general remediation of historical review-id host collisions. It includes the seams and honest
unavailable states needed so those features can arrive without bypassing policy.

No production code, migrations, dev data, Gradle execution or runtime restarts belong to Round 1.
Commit only this design and its plan, push the existing branch, open the requested draft PR.

## 11. Review decisions — settled in Round 2

The [review summary and six inline comments](https://github.com/artyomsv/code-spire/pull/153#pullrequestreview-5188278775)
settled all five questions. These are requirements for implementation, not pending approvals.

1. **Journeys/order:** accepted real state machine, manual tracker-artifact handoff and one
   prepared M2 build, proved at plan/build. Missing later capabilities wait honestly. Correct
   delivery-before-review in ADR-045 and the eight-phase diagram in slice 8a.
2. **Profiles:** accepted operator precedence plus the meet of every eligible applied label,
   pinned vector and ceiling. ADR-045 states: **the effective vector is never above any applied
   label in any component.** Precedence selects display/clamp wording, never authority by itself.
3. **Identity/permission:** explicit capability errors and disambiguated selection are accepted;
   do not guess or raise token authority. State credential prerequisites in operator-facing text.
   Add each per-forge identity behavior as its own UNVERIFIED entry in its introducing slice,
   identifying the forge, measurement and remaining proof. This includes Bitbucket's admin-only
   effective-permission query and provider-specific handle resolution behavior.
4. **Repositories:** one source/ISSUE hook per repository, with REVIEWER/FACTORY/ISSUE kinds.
   Org auto-enrollment exists only during the bridge. At cutover an unregistered repository event
   raises attention naming repo, origin and incoming registration, with all three pre-filled in
   the Register action. Retain populated account workspace evidence, unused after slice 2, until
   slice 10. Back up the actual dev database before migrations and compare actual credential
   decryption after slice 2, using the exact commands in the plan.
5. **Drafts/takeover:** native drafts or explicit refusal, never a title prefix. Commands and
   gate answers precede generic comment takeover; ADR-045 names the FR-F22/FR-F25 conflict.
   Slice 8b separately owns publication hold and draft delivery after 8a. Its exit also requires
   a live standalone `/fix` on `artyomsv/spire-test` still pushing automatically. Slices 9 and 10
   retain their numbers.
