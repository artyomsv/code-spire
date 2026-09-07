# Accounts, roles and the screens that show them — design

**Date:** 2026-09-07
**Status:** draft, awaiting the operator's review. No code written.
**Scope:** Rename and reshape three Settings screens so that *who acts* (accounts) is separate from
*where it acts* (repositories, context sources). Add the one field the M2 factory needs and the UI
cannot set today: an account's **role**. Add one small read-only endpoint. No new tables.

---

## 1. Why now

Two things came together.

**The M2 factory cannot be registered from the UI.** `POST /api/runs` refuses a run with *"Register
the machine account under Settings -> Providers with role FACTORY"*
(`spire-orchestrator/.../factory/RunResource.java:188`). The provider form has no role field
(`spire-ui/src/api.ts` `ProviderInput`, lines 330–342, carries no `role`), and the nav has no screen
called "Providers" — that route is labelled **Repositories** (`spire-ui/src/App.tsx:53`). The
instruction cannot be followed.

**The operator read the screens the way they are labelled, and the labels are wrong.** The screen
called *Repositories* is the SCM **account** registry: one row is a bot token for one forge and one
workspace, with a PR-author allowlist hanging off it. The screen called *Webhooks* is the one that
actually lists repositories. So "we assign a reviewer account to a repository" is what the screen
says, and it is not what the code does.

## 2. What the code does today (verified 2026-09-07)

| Fact | Where |
|---|---|
| No table links a repository to an account. The gateway's `webhook_repo` row holds `{provider_type, scope, target}` and its migration says the orchestrator never reads it. | `spire-gateway/src/main/resources/db/migration/V1__webhook_repo.sql:12-14` |
| The account for a review is chosen at run time by **(forge type, workspace, role)**. The repository slug is never an input. | `ProviderRegistry.resolve(type, workspace, role)`, `spire-orchestrator/.../provider/ProviderRegistry.java:199-212`; callers in `IntegrationSaga.java:718-726`, `ReviewProviderResolver.java:31-37`, `MachineAccounts.java:37-40` |
| One account per (forge type, workspace, role) is a database rule. | `V44__scm_provider_role.sql`, `UNIQUE (type, workspace, role)` |
| The "Provider" picker on the Webhooks form fills in forge and owner, then is **discarded on save**. The list shows the bare forge type. | `SettingsWebhookRepos.tsx:273-287`, `:143` |
| A context source holds **its own token**. It has no role, no workspace, no link to an SCM account. Every enabled source is brokered to the review worker and to nothing else. The factory reads no context source. | `V12__context_provider.sql`; `ContextProviderRegistry.java:166-171`; `ResultSaga.java:159-174`; zero hits for context in `orchestrator/factory/` and `spire-run-worker` |
| Dashboard users are **not stored as accounts**. The OIDC token is the identity; roles come from the identity provider. `operator_seen` grants nothing. | `V41__operator_directory.sql:9-12`; `docs/SECURITY.md:39-42` |
| The PR-author allowlist lives **on the reviewer account row**. The form says "username". `/fix` accepts only the stable user id and refuses the rest. | `V3__scm_provider.sql:23-27`; `SettingsProviders.tsx:480-494`; `IntegrationSaga.java:377-403` |
| The self-loop guard knows only the REVIEWER row's `bot_account_id`. | `IntegrationSaga.java:693-708` |
| Server side, `role` exists end to end: column, `ProviderInput.role`, `ProviderView.role`, `COALESCE(?, role)` on update so a role-less PUT keeps the stored value. | `ProviderRegistry.java` create/update; `ProviderView.java` |
| Nothing called an "analyst" is designed. The nearest is FR-F18 (M4): one model call that writes a comment to the tracker. It needs a tracker account that can **write**. No such account kind exists. | `docs/factory/PRD.md:162`; `docs/factory/PACKAGING.md:61-68` |

## 3. The operator's proposal, and what changes in it

The proposal: one Accounts page listing every account, human and machine (reviewer, builder,
analyst); repository and context pages verify that accounts can reach them, and assign nothing.

**Kept.** Accounts get their own page. Repository and context screens show and verify; they store
nothing about accounts. This is what the backend already does; the screens will finally say so.

**Changed — humans and machine accounts do not share a list.** A machine account holds a token and a
role. A human holds nothing here; their authority is the identity provider's. One mixed list is the
shape ADR-038 exists to prevent: a bot listed where a person is expected, and then allowlisted as
one. They share a **page** with two tabs. They never share a table or a form.

**Changed — roles depend on the kind of account.** Forge accounts are *Reviewer* or *Factory*
(the operator's "builder"). Tracker and knowledge accounts are *Read* today. *Write* arrives with
M3's work-source arms (ADR-035: "rights, not transport", enforced at the adapter), and the "analyst"
is a tracker account with write rights, built then. This design leaves the slot and builds nothing
for it.

**Changed — nothing is re-modelled.** The repository↔account binding is already a rule, not a row.
The fix is to show the rule. A full account/credential/scope re-model (many accounts per workspace,
repo-scoped tokens) is recorded as a follow-up in §11, not done.

## 4. Decisions

1. **Rename, do not invent.** `/settings/providers` becomes **Accounts**. `/settings/webhooks`
   becomes **Repositories**. `/settings/operators` becomes the **People** tab of Accounts. Old routes
   redirect. Every server message and attention link that names a screen is updated to a screen
   that exists.
2. **A forge account's role is set at registration and cannot be edited.** The edit form shows it
   read-only. The server refuses a `PUT` that carries a role different from the stored one
   (`409`); an absent or equal role is accepted. Why: a silent role change once handed the review
   pipeline the push token (`docs/HISTORY.md:1391,1397`). Changing a role is "register a new
   account, delete the old one", which is what an operator would do with a real service account.
   The operator's view: a reviewer stays a reviewer in 99 % of cases, so the edit is not missed.
3. **Tracker and knowledge accounts are listed on the Accounts page, read-only.** They are managed
   on Context, where their source-specific fields (project keys, host) live. One row, two views: the
   Accounts page answers "who acts as what"; Context answers "what is read".
4. **The PR-author allowlist stays on the reviewer account.** One reviewer per (forge, workspace)
   means "the reviewer's allowlist" and "the workspace's allowlist" are the same set. It is
   relabelled to say what it gates and asks for the **stable user id**. Moving it to a workspace
   policy object is a follow-up (§11).
5. **Resolution stays server-side.** The Repositories screen asks the orchestrator which accounts
   serve a (forge, workspace). The endpoint calls the same resolvers the pipeline calls, so the
   screen cannot say one thing while the pipeline does another.
6. **No new ADR.** Decision 2 is recorded here. If the operator wants it in `docs/DECISIONS.md`, it
   is a five-line ADR-041; this document does not assume it.

## 5. Screens

### 5.1 Settings → Accounts (`/settings/accounts`)

Two tabs. A small tab strip with two links — the first tab strip in the app; keep it to two anchors
and an `aria-selected` state, nothing more.

**Tab: Machine accounts** (default).

Columns: **Name · Kind · Role · Identity · Scope · Connection · Enabled · May command · Conversation ·
actions**.

- Forge rows (from `GET /api/providers`): Kind `Forge · github`; Role `Reviewer` or `Factory`;
  Identity the resolved `botUsername`, or *not resolved*; Scope the workspace; Connection the
  existing live check cell; May command the allowlist count (Reviewer rows only, `—` on Factory);
  Conversation the level (Reviewer only, `—` on Factory). Actions: Edit, Delete, Check — as today.
- Tracker/knowledge rows (from `GET /api/context-providers`): Kind `Tracker · jira` /
  `Tracker · confluence` / `Tracker · github-issues` / `Tracker · gitlab-issues` / `Knowledge ·
  code`; Role `Read`; Identity the stored `username` or `—`; Scope the `baseUrl` host; Connection
  from `lastCheckOk/lastCheckAt`; Enabled. One action: **Manage on Context** (link to
  `/settings/context`). No edit or delete here. The link carries no `?edit=`: no screen reads that
  parameter today (§11), so pretending it opens the row would be a lie.
- Sort: forge rows first, then tracker rows; within each, `createdAt`.
- Empty state: "No machine accounts yet. Add a reviewer account to start reviewing."

**Add / Edit forge account** — the existing modal, with these changes:

- A **Role** select as the first field: `Reviewer` (default) / `Factory`. On edit: read-only text
  with the hint *"Set at registration. To change it, register a new account and delete this one."*
- When Role is `Factory`: the **May command** (allowlist) and **Conversation level** fields are
  hidden. They belong to the reviewer's job and are dead data on a factory row (the allowlist is
  read only through `resolveForReview`, which is REVIEWER-only).
- The bot-account hint gains one sentence for Factory: *"A Factory account must resolve to a login.
  A workspace access token with no user cannot push."* This mirrors
  `MachineAccounts.canAuthenticateAPush`.
- The allowlist field: label **May command this bot**, placeholder `stable user id`, hint *"The id
  the forge reports for the user, not the handle. `/fix` accepts ids only; a handle can change
  hands."* Existing entries are shown unchanged; nothing is migrated.
- Submit sends `role` on create. On edit it sends the stored role unchanged (never a different one).

The screen title is **Accounts**. The `<h2>` at `SettingsProviders.tsx:110` says "Repositories"
today.

**Tab: People** (`/settings/accounts/people`).

The current `SettingsOperators` screen, unchanged in content: the OAuth applications per platform
(`ScmConnections`), the seen-operators directory, and the admin repair form. It moves; it does not
change. `/settings/operators` redirects here.

### 5.2 Settings → Repositories (`/settings/repositories`)

The current Webhooks screen, renamed. The gateway API (`/gw/webhook-repos`) is unchanged.

List columns today: **Scope · Target · Provider · Payload URL (path) · Secret · Enabled**. They become
**Scope · Target · Forge · Reviewed by · Pushed by · Payload URL (path) · Secret · Enabled**.

- **Forge**: the bare `providerType` (today's "Provider" cell, honestly named).
- **Reviewed by** / **Pushed by**: the account that serves this row's (forge, owner) for the
  REVIEWER / FACTORY role, from the endpoint in §6.1. Rendered as a chip with a state and the
  account name:

  | state | chip | meaning |
  |---|---|---|
  | `ok` | green, account name | enabled and usable |
  | `no-identity` | amber, account name | Reviewer: enabled, but no `botAccountId` resolved — it reviews, cannot recognise its own comments; conversation follow-ups are skipped (existing `botIdentityUnknown` behaviour) |
  | `no-login` | amber, account name | Factory: enabled, but no `botUsername` — cannot push; `POST /api/runs` will answer 409 |
  | `disabled` | grey, account name | registered, disabled |
  | `missing` | grey, "none" | no registration for this role |

  One request per distinct (forge, owner) pair on the page, not per row.
- A **Verify** action per chip (states other than `missing`): repo-scope rows call the existing
  `POST /api/providers/{id}/verify-repo {repo: target}`; org-scope rows call the existing
  `POST /api/providers/{id}/check`. Result renders inline as today's `ConnState` pattern. For the
  Factory chip the label reads *"reachable (read). Push rights are not checked."* — the verify
  is a repository GET and a false "can push" would be worse than none.

Form changes (`WebhookRepoFormModal`): the picker's label changes from "Provider" to
**Workspace** and its options read `github · acme (Acme Bot)`. It still fixes forge and owner and is
still discarded on save; a one-line hint says so: *"Which accounts review and push here is decided
by the forge and workspace, not stored on this row."* The empty-state copy becomes *"Register a
reviewer account first (Settings → Accounts)."* The picker keeps offering **Reviewer** accounts
only — a workspace with only a Factory account has nothing to review with, and this form registers
what will be reviewed.

The attention link `/settings/webhooks?edit=<id>` (`WebhookAttentionRows.java:56`) becomes
`/settings/repositories?edit=<id>`; the old form still works via the redirect.

### 5.3 Settings → Context (`/settings/context`)

One added column: **Used by** — static text `Reviewer · read` on every row. It becomes data when
M3 registers a second, write-capable account against the same host. Nothing else changes.

## 6. Backend

### 6.1 `GET /api/providers/serving?type={providerType}&workspace={workspace}`

`@RolesAllowed("spire-admin")`, like every registry read. `400` when either parameter is blank.

```json
{
  "type": "github",
  "workspace": "acme",
  "reviewer": { "state": "ok", "id": "…", "name": "Acme Bot", "botUsername": "acme-bot", "botAccountId": "12345" },
  "factory":  { "state": "missing" }
}
```

`state ∈ ok | no-identity | no-login | disabled | missing`. `id/name/botUsername/botAccountId` are
present for every state except `missing`. No secret, ever.

How each state is derived, and from which existing rule:

- Look up the registration regardless of `enabled` — a new `ProviderRegistry.registration(type,
  workspace, role)` returning a `ProviderView` (no secret). None → `missing`. `enabled = false` →
  `disabled`.
- Reviewer, enabled: `ProviderRegistry.resolve(type, workspace, REVIEWER)` present → `ok` if
  `botAccountId` is non-blank, else `no-identity`. The blank check is the one
  `ConversationSaga.botIdentityUnknown` makes.
- Factory, enabled: `MachineAccounts.resolve(scmType, workspace)` present → `ok`; else `no-login`.
  This is the exact filter (`canAuthenticateAPush`) the dispatch path uses, so the chip and the 409
  agree by construction.

Lives in `ProviderResource` beside `check` and `verify-repo`. Provider-neutral: it never names a
forge (ADR-020).

### 6.2 Role is fixed after registration

`ProviderRegistry.update`: if `in.role()` is non-blank and `ProviderRole.of(in.role())` differs from
the stored role, throw a conflict that `ProviderResource.update` maps to `409` with *"role is set
at registration; register a new account for the other role"*. Absent/blank keeps the stored role
(today's `COALESCE`). Equal is a no-op.

### 6.3 Strings that name a screen

| File:line | today | becomes |
|---|---|---|
| `factory/RunResource.java:188` | `Settings -> Providers with role FACTORY` | `Settings -> Accounts, role Factory` |
| `ingress/ManualRegisterResource.java:91` | `Add one under Settings -> Providers.` | `… Settings -> Accounts.` |
| `pipeline/ReviewRerunService.java:69` | same | same change |
| `prompt/PromptSampleRenderer.java:72` | same | same change |
| `attention/AttentionQueries.java:159,176,348` | `/settings/providers` (+`?edit=`) | `/settings/accounts` (+`?edit=`) |
| `provider/ProviderResource.java:29` (javadoc) | `Settings -> Providers` | `Settings -> Accounts` |
| `spire-gateway/.../WebhookAttentionRows.java:56` | `/settings/webhooks?edit=` | `/settings/repositories?edit=` |
| `spire-gateway/.../WebhookRepoResource.java:25` (javadoc) | `Settings -> Webhooks` | `Settings -> Repositories` |
| `docker-compose.dev.yml:233` (comment) | `Settings -> Webhooks` | `Settings -> Repositories` |

Tests asserting the old strings (`AttentionQueriesTest.java:411`, `WebhookAttentionResourceTest.java:108`)
change with them.

### 6.4 TypeScript types

`ProviderView` gains `role: 'REVIEWER' | 'FACTORY'`; `ProviderInput` gains `role?: 'REVIEWER' |
'FACTORY'`. New `ServingAccounts` and `ServingAccount` interfaces for §6.1, with `state` as a
closed union **and** a reader that maps any unlisted value to `missing`-styled grey — the
`refused`-as-five-green-segments lesson (CLAUDE.md, Gotchas).

## 7. Routes and navigation

| today | becomes | note |
|---|---|---|
| `/settings/providers` · "Repositories" | `/settings/accounts` · "Accounts" | redirect old → new, preserving `?edit=` |
| `/settings/operators` · "Operators" | `/settings/accounts/people` · "Accounts" (tab People) | redirect |
| `/settings/webhooks` · "Webhooks" | `/settings/repositories` · "Repositories" | redirect, preserving `?edit=` |
| `/settings/context` · "Context" | unchanged | |

Nav rail order under **Configure** today (`App.tsx:207-286`): Memory · Operators · General · Context
· Repositories(=providers) · Webhooks · LLM · Prompts · Dead-letter. It becomes: Memory · **Accounts**
· General · Context · **Repositories** · LLM · Prompts · Dead-letter — Accounts takes the Operators
slot and absorbs the old providers entry; Repositories takes the Webhooks slot. Icons stay
lucide-react (`UsersRound` for Accounts; the existing branch glyph moves to Repositories).

`App.tsx` `TITLES` and `App.routes.test.tsx` `ROUTES` change together — the test file's own comment
says a route added without a row is a screen with no coverage.

## 8. Error and edge handling

- Serving endpoint unreachable → both chips render *"unknown"* in grey with the fetch error in the
  title; never green, never "none" (unknown is never zero — ADR-023's shape).
- A row whose (forge, owner) has no reviewer account still lists; its chip says `missing`, and the
  row's Verify is disabled. This is the legacy-edit case the form already handles.
- Two enabled reviewer rows for one (type, workspace) cannot exist (unique key); the endpoint does
  not handle it and says so in a comment.
- Redirects preserve the query string. Attention-panel links carry `?edit=<id>` today and land on
  the right screen; no screen opens the row from it (verified 2026-09-07: none of
  `SettingsProviders`, `SettingsContextProviders`, `SettingsWebhookRepos` reads the query string).
  This change keeps that behaviour as it is — it neither adds nor removes the parameter's effect.
- The factory-role form path never shows or sends `authors` / `conversationLevel`; on the server
  they remain accepted and ignored for FACTORY rows, as today. No migration touches existing rows.

## 9. Testing

**UI (vitest).**
- `App.routes.test.tsx`: the new `ROUTES` rows; each old route redirects to its new one and keeps
  `?edit=x`.
- `SettingsProviders.form.test.tsx`: *sends the FACTORY role*; *defaults a new account to REVIEWER*;
  *edit sends the stored role, never another*; *Factory hides May-command and Conversation*; *the
  allowlist field asks for a stable user id*.
- `SettingsProviders.test.ts` / a new list test: tracker rows appear read-only with a Manage link;
  forge rows show Kind and Role; sort order.
- `SettingsWebhookRepos.form.test.tsx`: chips render each of the five states from a mocked
  `/api/providers/serving`; an unlisted state renders grey; one request per distinct (forge,
  owner); Verify calls `verify-repo` with the *chip's* account id (Factory verify uses the factory
  id, not the reviewer's); the Factory verify label says push rights are not checked.
- `SettingsContextProviders`: the Used-by column renders `Reviewer · read` on every row.
- `styles.contract.test.ts` gains any new class the chips introduce.
- `tsc --noEmit` silent.

**Java.**
- `ProviderResourceTest` (or a sibling): serving returns each of the five states from real rows;
  `400` on blank params; viewer gets `403`; response carries no secret field.
- `ProviderRegistryTest`: update with a different role → conflict; same role → ok; absent → kept.
- Existing tests that assert screen strings, updated per §6.3.

**Mutation checks** (break the line, exactly one test fails):
- drop `ps.setString(13, …role…)` on create → *sends the FACTORY role* fails server-side.
- swap the `no-login` / `ok` branch → the state test fails.
- delete the role-differs guard in `update` → the conflict test fails.
- make the chip reader default to `ok` → the unlisted-state test fails.

## 10. Documentation to update in the same change

- `README.md:80-81` — Providers/Webhooks → Accounts/Repositories.
- `docs/ROADMAP.md:53` — "Provider registry (Settings → Providers)".
- `docs/SMOKE-TEST.md` lines 63, 87, 198, 265, 608, 808, 1589, 1615; and `1646` — remove *"the
  Providers screen does not expose the role yet, so use the API"* and describe the Role field.
- `docs/HISTORY.md` — an entry when delivered; `CLAUDE.md` Status line if it names screens.
- `techdebt/spire-ui/4-3-three-factory-surfaces-still-have-no-screen.md` — unchanged: the three
  surfaces it lists (dispatch resolution, harness pool, cancel/steer) are not touched here.

## 11. Out of scope, with the reason each stays out

- **Tracker accounts with write rights; the "analyst".** M3's work-source arms (ADR-035) and M4's
  spec phase. The Accounts page leaves the Role column ready for `Write`.
- **Many accounts per (forge, workspace, role); repo-scoped tokens.** Nothing needs it yet.
  Direction if it does: an `account` row separate from `credential`, with a role assignment carrying
  a scope, and "most specific scope wins, ambiguity refuses" — never first-wins. Recorded here so it
  is not re-derived.
- **Verifying push rights for the Factory chip.** Forge-specific (GitHub `permissions.push`, GitLab
  `permissions.project_access`, Bitbucket differs). Adapter work; the chip says plainly it is not
  checked.
- **A "let the reviewer review this account's pull requests" toggle on the Factory account.**
  Tempting, and unsafe as a simple add: on a deployment with an **empty** allowlist ("everyone"),
  adding the factory's id would turn "everyone" into "only the factory". It needs the allowlist to
  distinguish "no list" from "a list", which is the workspace-policy follow-up below.
- **Moving the allowlist to a workspace policy object.** Cleaner, and it is where the toggle above
  and the `/fix` deny-by-default rule would live. Not needed to fix the labels.
- **Resolving a handle to a stable id in the allowlist field.** `IdentitySource` has `whoami` only;
  a lookup-by-handle is a new port method on three adapters.
- **Teaching the self-loop guard the Factory identity.** FR-F22 (M3) territory; the guard's
  REVIEWER-only behaviour is intentional for pull-request events (ADR-038).
- **Making `?edit=<id>` open the row.** The attention panel has linked to
  `/settings/providers?edit=` and `/settings/webhooks?edit=` since 2026-07-27, and no screen has
  ever read it. Small, real, and its own change: three screens, one shared hook, one test each.
  Worth a `techdebt/spire-ui/` entry when this spec is approved.
- **The stale identity section of `docs/DATA-MODEL.md`** (`operator_seen`, `scm_oauth_app`,
  `scm_provider`, `factory_run` absent; "secrets never stored" contradicted by the encrypted
  registry). A doc-only follow-up; noted so it is not lost.

## 12. Risks

- **A rename is a migration for people.** Every runbook line that says "Providers" or "Webhooks"
  must move (§10). The redirects make old links work; they do not update anyone's memory.
- **Two views of one context row** can look like two objects. The Manage link and the absence of
  edit/delete on the Accounts side are what keep it one.
- **A `409` on role change is new server behaviour.** Any script that PUTs a full view back (as the
  dashboard used to) sends the *same* role and passes. Only a genuine change is refused.

## 13. Success criteria

1. An operator registers a **Factory** account from Settings → Accounts, and `POST /api/runs` for
   that (forge, workspace) no longer answers *"No FACTORY-role provider is registered"*.
2. Settings → Repositories shows, for every row, which account reviews it and which pushes, in one
   of the five states, and Verify works per chip with that chip's account.
3. `/settings/providers`, `/settings/webhooks`, `/settings/operators` redirect, keeping `?edit=`.
   Every attention-panel action lands on a screen that exists.
4. A `PUT /api/providers/{id}` that changes the role is refused with `409`; one that repeats or
   omits it succeeds.
5. No server message or doc names a screen that does not exist.
6. Every test in §9 is green, the four mutation checks each kill exactly their test, and
   `tsc --noEmit` is silent.
