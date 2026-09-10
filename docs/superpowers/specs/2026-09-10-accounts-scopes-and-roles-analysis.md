# Accounts, scopes and roles — analysis

**Date:** 2026-09-10
**Status:** analysis. No plan, no tasks; it exists to decide a direction.
**Scope:** the two machine-account registries, what each token is for, whether a role can be
read off a token, what ADR-038's separation buys, and two ways to simplify — with a
recommendation and its relation to the repository-centred model the operator chose on 2026-09-08.

Everything marked *measured* was read from this worktree or the dev database on 2026-09-10;
everything marked *unmeasured* is a claim no test or query here establishes. Vendor statements
carry the page they were read from and the retrieval date, the way `docs/factory/EXECUTION-LAYER.md`
quotes terms.

---

## 1. The complaint

> "some account I can manage there and some I cannot … we provide duplicated tokens (maybe
> different scopes) for the same accounts … it would be easier … when I register account to provide
> the roles based on their scopes … account have scopes to read code, comment PR and read context, so
> that account can have access as a reviewer and as a context reader. If scopes has access to write
> code, update PR comments and code, read context then most probably it should have roles as a
> factory worker and context reader."

Three things are being asked for, and they have different answers:

1. **One place to manage every machine account.** Yes, and cheaply — §6.1.
2. **Stop pasting the same token several times.** Yes — the duplicates are the *context sources'*
   tokens, and they can reference an account instead of holding one — §6.1.
3. **Derive the roles from the token's scopes.** Only partly, and never as the deciding input: two
   of the four credential families the product accepts do not report their scopes at all, and
   scope is necessary-not-sufficient on every forge — §4. What the operator can have is the
   *reading* of the account they describe ("reviewer and context reader", "factory worker and
   context reader"): both are the same account used in two places, and the product should show it
   that way. The one pair that must stay two accounts is reviewer + factory — §5.

## 2. What exists today

### 2.1 Two registries, and why

Machine credentials live in two tables of the orchestrator's schema, `scm_provider` (V3, 2026-07)
and `context_provider` (V12, 2026-07), each Tink-encrypted with its own AAD prefix, each with its
own CRUD resource, its own connectivity check, and its own settings screen. The 2026-09-07 design
(PR #120) put both on one **Accounts** page but could edit only one of them: forge rows are edited
there, context rows carry a *Manage on Context* link and no other action
(`spire-ui/src/components/SettingsProviders.tsx:22-24,68-74`; spec §5.1). That is the "some I can
manage and some I cannot" the operator saw, and the spec knew it: §3 says "one row, two views" and
§11 defers the re-model.

**Why two: one is a design reason, one is an accident of build order, and they are separable.**

The design reason is the plugin axis. ADR-020 names *two* plugin axes — the SCM a review runs on and
the context sources it pulls from — with different SPIs (`DiffSource`/`CommentSink`/`IdentitySource`
against `ContextProvider`) because they answer different questions. That split is real and this
analysis does not touch it.

The accident is that the *credential* followed the SPI. `context_provider` was cloned from
`llm_provider`, not from `scm_provider`: `V12__context_provider.sql` says "mirroring `llm_provider`
… One global default, like the LLM provider", and `ContextProviderRegistry.java:22` says "Mirrors
`LlmProviderRegistry`". An LLM provider has an API key and no identity, so the context registry got
an API key and no identity — no `bot_account_id`, no `workspace`, no role, no link to the forge
account that, on GitHub and GitLab, is the same bot at the same host. The `is_default` column that
came with the clone is dead: `create` always writes `FALSE` with the comment "No default concept for
context: every enabled provider participates" (`ContextProviderRegistry.java:75-78`), and exactly
one live row still carries `TRUE` from before (§3). Nothing in `docs/DECISIONS.md` decides that
context sources hold their own credentials; ADR-035 argues the opposite — Knowledge and Build
"share connectors" and differ in "rights, not transport", enforced at the adapter — which is a
description of one credential handed to adapters of different authority, not of two registries.

### 2.2 Who resolves what, by which key (measured)

| Caller | Registry call | Key | What it gets |
|---|---|---|---|
| `IntegrationSaga.onPullRequestEvent` (`IntegrationSaga.java:718-729`) | `ProviderRegistry.resolve(type, workspace)` → REVIEWER; `resolveByWorkspace` when the event has no type | (forge type, workspace, `REVIEWER`) | the reviewer's decrypted token + author allowlist |
| `ReviewProviderResolver.resolveForReview` → `WorkerCredentials.packForReview` (ResultSaga, conversation saga, self-loop guard `IntegrationSaga.java:701`) | `resolve(storedType, workspace)` | (review's stored forge type, workspace, `REVIEWER`) | the same row, packed under `ScmCredential.aad(workspace)` for the worker |
| `MachineAccounts.resolve` (`RunResource.java:167`, the `/fix` dispatcher) | `resolve(type, workspace, FACTORY)` filtered by `canAuthenticateAPush` | (forge type, workspace, `FACTORY`) + a non-blank login | the push token; empty means "cannot dispatch", never "use the reviewer" |
| `ProviderClients.pullRequestSink` (`ProviderClients.java:103-104`) | — (checks the row it is handed) | `role == FACTORY` or throws | the client that opens a pull request |
| `ResultSaga` (`ResultSaga.java:159-171`) → `WorkerContextCredentials.packAll` | `ContextProviderRegistry.resolveAllEnabled()` (`:166`) | **no key** — every enabled row, oldest first | every context token, as one list under `worker-context-cred:<workspace>` |
| `WorkerContextClients` (`spire-review-worker/…/WorkerContextClients.java:88-98`) | — | `cred.type()`; for `code`, the **host substring** of `baseUrl` (`:157-164`) | one `ContextProvider` per credential |
| `ProviderResource.check` / `verifyRepo` (`ProviderResource.java:204-206,239-240`), `ContextProviderResource.check` (`:176-184`) | `resolveById` | row id | the decrypted token, for a live probe |

Two consequences the merge options in §6 must respect. **The reviewer/factory key is
`(type, workspace, role)`** — `V44` widened `UNIQUE (type, workspace)` to include the role so that a
workspace can hold one of each, and `ProviderRegistry.resolve` filters on the role because "an
unfiltered `SELECT *` returns whichever the planner yields first — which could hand the review path
the factory's push token" (`ProviderRegistry.java:199-205`). **The context path has no key at all**:
a Jira token registered for one project is packed onto every review on every forge, and the worker
decides relevance by matching references. That is why a context row has no workspace to lose: it
never had one.

### 2.3 What each token actually does (measured against the adapters)

Read as "the scope a token needs is the union of the rows it is used for".

| Use | Calls | Where | Who holds the token |
|---|---|---|---|
| Identify the bot | `GET /user` (GitHub, GitLab), `GET /2.0/user` with a `GET /repositories/{ws}` fallback for account-less Bitbucket tokens | `GitHubDiffSource.java:44-47`, `GitLabDiffSource.java:47-50`, `BitbucketCloudDiffSource.java:42-62` | reviewer and factory rows, at registration and on **Check** |
| Reach a repository | `GET /repos/{o}/{r}`, `GET /projects/{p}`, `GET /repositories/{ws}/{slug}` | `GitHubDiffSource.java:54`, `GitLabDiffSource.java:57`, `BitbucketCloudDiffSource.java:97` | reviewer row, on **Verify** |
| Read the change | PR/MR metadata, `compare/{base}...{head}`, `/changes`, `/diff` | `GitHubDiffSource.java:84`, `GitLabDiffSource.java:80,103`, `BitbucketCloudDiffSource.java:120,132` | reviewer row, every review |
| Read a file at a ref | `GET /repos/{o}/{r}/contents/{path}?ref=` and the GitLab/Bitbucket equivalents — for `.codespire` on the target branch | `GitHubDiffSource.java:88-96`, `GitLabDiffSource.java:165`, `BitbucketCloudDiffSource.java:144` | reviewer row |
| Read a file at a commit | **the same three endpoints** | `GitHubSourceFileReader.java:42`, `GitLabSourceFileReader.java:38`, `BitbucketSourceFileReader.java:37` | the `code` **context** row — a second token for a call the reviewer's token already makes |
| Comment, reply, edit, resolve | review comments, replies, issue comments, notes/discussions, `/resolve` | `GitHubCommentSink.java:111,193,217,221`, `GitLabCommentSink.java:70,96,135,157,190`, `BitbucketCloudCommentSink.java:109,124,128` | reviewer row, from the review worker |
| Read an issue and its comments | `GET /repos/{o}/{r}/issues/{n}` + `/comments`; `GET /api/v4/projects/{p}/issues/{n}` + `/notes`, epics | `GitHubIssueContextProvider.java:139,285`, `GitLabIssueContextProvider.java:165-193` | the `github-issues` / `gitlab-issues` context rows — on the **same host** as the reviewer row |
| Read a ticket / a page | `GET /rest/api/2/issue/{key}`; `GET /rest/api/content/{id}?expand=…` | `JiraContextProvider.java:95`, `ConfluenceContextProvider.java:92` | the `jira` / `confluence` context rows — a different identity system (an Atlassian account) |
| Clone and push | git over HTTPS; one token in both the read slot and the write slot | `spire-run-worker/…/Credentials.java:66-79`; `docs/UNVERIFIED.md:184-189` | factory row only; reaches the init and publisher containers, never the agent |
| Open a pull request | `POST /repos/{o}/{r}/pulls`, `POST …/merge_requests`, `POST …/pullrequests` | `GitHubPullRequestSink.java:166`, `GitLabPullRequestSink.java:160`, `BitbucketCloudPullRequestSink.java:147` | factory row only (`ProviderClients.java:103-104` refuses any other) |

So, in the operator's words: **"read code, comment PR, read context"** is exactly the reviewer row
plus the `github-issues`/`code` rows on the same host — three tokens for one authority set on
GitHub, two on GitLab. **"write code, update PR, read context"** is the factory row plus, from M3,
the work source's read — which the roadmap already plans to be "the factory machine account itself"
on GitHub/GitLab (memory of 2026-09-08; `docs/factory/ROADMAP.md:364-372`). The reviewer never
pushes or opens a pull request; the factory never comments as the reviewer. Those are the two
authority sets ADR-038 keeps apart, and nothing in the table crosses them.

### 2.4 Storage and checks (measured)

Both registries encrypt the secret with Tink under an AAD bound to the row: `provider:<id>`
(`ProviderRegistry.java:406`) and `context-provider:<id>` (`ContextProviderRegistry.java:233`). A
ciphertext therefore cannot be moved between tables, or between rows, by SQL — a migration that
consolidates tokens must decrypt and re-encrypt, which means a Java migration or a start-up backfill
holding the keyset, not a Flyway SQL script. Neither registry ever returns a secret; `ProviderView`
and `ContextProviderView` carry `hasSecret` only.

The checks are shallow on purpose and neither reads a scope. The SCM check is
`IdentitySource.whoamiOrValidate` (`ProviderResource.java:204-210`) — "does `/user` answer, or, on
Bitbucket, can the token list the workspace" — plus `verify-repo` = "does `GET /repos/{o}/{r}`
answer" (`:239-248`). The context check probes one URL per type: `/rest/api/2/myself`,
`/rest/api/user/current`, `/user`, `/api/v4/user`, and for `code` a fixed public file
(`ContextKeyValidator.java:173-176,58,261-264`), choosing the platform for `code` by the same host
substring the worker uses (`techdebt/global/3-2-…`). Both checks write `last_check_at/ok/error` and
push an attention refresh (V28 for `scm_provider`; the context columns are in the same shape). Both
failure messages already say "check the token and its scopes" (`ProviderResource.java:280`,
`ContextProviderResource.java:378`) — the product tells the operator scopes matter and never reads
one. The account the context check identifies is returned in the check response and **not stored**
(`ContextKeyValidator.java:145,160`), which is why a context row cannot say whose token it holds.

**Nothing re-checks on its own.** There is no `@Scheduled` under `orchestrator/provider` or
`orchestrator/context`; a token narrowed or revoked after registration is discovered by the next
review failing (or, for the `code` provider, not even then —
`techdebt/spire-context-code/3-3-credential-rejection-never-reaches-an-attention-row.md`).

## 3. What the operator's rows show (measured 2026-09-10, read-only psql)

`scm_provider`: six rows, **all `REVIEWER`, zero `FACTORY`**. Enabled: `public-github` (github /
artyomsv, bot `code-spire-bot` id 305915232, **check failed 401**), `public-gitlab` (gitlab /
artyomsv-group, bot `code-spire-bot` id 40634095, check ok), `public-bitbucket` (bitbucket-cloud /
artyomsv, `basic` with an e-mail username, bot `code-spire-admin`, check ok). Disabled:
`crypto-finance` (bitbucket, 401), `gitlab-epam-1` and `gitlab-epam-2-vpn` (never checked —
`last_check_ok` is null). One allowlist entry exists, on one provider. `gateway.webhook_repo` has
three rows, one repository per forge.

`context_provider`: five rows. Enabled: `github-issues` (api.github.com, bearer, **401**),
`gitlub-issues` (gitlab.com, bearer, ok), `github-repository-provider` (`code`, api.github.com,
bearer, **401**). Disabled: `Crypto Finance Jira` (401) and `Crypto Finance Confluence` (403), both
`basic` under one **person's** corporate e-mail — a human's token standing in for a service account,
which is the "which account is this" confusion in its purest form. The Confluence row is the single
`is_default = TRUE` row the dead column still carries.

What that adds up to per host:

| Host | Rows holding a token | Failing together | Would need under §6.1 |
|---|---|---|---|
| api.github.com | `public-github` (07-07), `github-issues` (07-30), `github-repository-provider` (08-27) | all three, 401 | two: one reviewer account referenced by both sources, one factory account |
| gitlab.com | `public-gitlab`, `gitlub-issues` | none | two: reviewer (referenced by the issues source), factory |
| bitbucket.org | `public-bitbucket` | none | one, plus a factory account if Bitbucket is to be built on |
| cryptofinance.atlassian.net | Jira row, Confluence row | both | one Atlassian account referenced by both sources |

**Whether the three GitHub rows hold one secret is unmeasured.** The ciphertexts sit under three
different AADs and were not decrypted for this analysis. Two readings fit the data: one token pasted
three times and since expired, or three tokens that each expired (GitHub lets a classic token's
expiry be chosen at creation and removes any token unused for a year — *Managing your personal
access tokens*, docs.github.com, retrieved 2026-09-10). Either way the operator maintains three
registrations to keep one bot alive on one host, and is about to add a fourth for the factory.
Whether `gitlub-issues` is the same GitLab bot as `public-gitlab` is likewise unmeasured — the
context row records no identity (§2.4).

One date matters for Bitbucket: app passwords stopped being created on 2025-09-09 and **ceased to
function on 2026-06-09**, with full removal on 2026-07-28 (*Bitbucket Cloud transitions to API
tokens*, atlassian.com/blog, and the brownout notice on community.atlassian.com, both retrieved
2026-09-10). `public-bitbucket`'s check passes today, so its `basic` credential is an Atlassian API
token used with the account e-mail, which is the only Basic form that still works. Whatever the
product does for Bitbucket scopes must target API tokens, not app passwords.

## 4. Can a role be derived from a token's scopes?

The operator's proposal rests on this. Per provider, per credential kind the product can hold today:

| Provider | Credential kind | Product holds it? | Scopes readable? | How | Source (retrieved 2026-09-10) |
|---|---|---|---|---|---|
| GitHub | OAuth token / **classic PAT** | yes (`bearer`) | **yes** | response headers on any call: "`X-OAuth-Scopes` lists the scopes your token has authorized. `X-Accepted-OAuth-Scopes` lists the scopes that the action checks for." | docs.github.com *Scopes for OAuth apps*. The example uses an OAuth token; that a classic PAT returns the same header is the community's account (discussion #156115), **unmeasured here** |
| GitHub | **fine-grained PAT** | yes (`bearer`) | **no** | no header, no endpoint. On a refusal, "`X-Accepted-GitHub-Permissions` … is a comma separated list of the permissions that are required to use the endpoint" — the endpoint's need, not the token's grant. "GitHub currently doesn't provide a way to query the scopes or repository permissions of a fine-grained personal access token" (community member, 2025-06-08; unanswered by GitHub) | docs.github.com *Troubleshooting the REST API*; github.com/orgs/community/discussions/156115 |
| GitHub | App installation token | only as a pasted bearer (`GitHubConfig.java:9`); the product has no App flow and the token lives an hour | yes at mint (`POST /app/installations/{id}/access_tokens` returns `permissions`, `repository_selection`, `repositories`) | — | docs.github.com *REST API endpoints for GitHub Apps*. Moot until an App adapter exists |
| GitLab | **PAT** | yes (`bearer`) | **yes** | `GET /personal_access_tokens/self` — "the personal access token you used to authenticate the request" — returns `scopes` (and `granular_scopes` since 19.2) | docs.gitlab.com *Personal access tokens API*. The version that introduced `self` is not stated on the page — unmeasured |
| GitLab | project / group access token | yes (it is a bearer token) | **documented for PATs only** | "When you create a project access token, GitLab creates a bot user and associates it with the token." Whether `…/self` answers for a bot user's token is not stated; `GET /projects/:id/access_tokens/self` does not exist (only `…/self/rotate`) | docs.gitlab.com *Project access tokens* and *Project access tokens API*. **Unmeasured** — test with one before relying on it |
| Bitbucket Cloud | repository / workspace / project access token, OAuth token | yes (`bearer`) | **yes** | headers on any call: `x-oauth-scopes` (the token's) and `x-accepted-oauth-scopes` (the endpoint's); example `curl -I -H "Authorization: Bearer <token>" https://api.bitbucket.org/2.0/repositories` on a repository access token | support.atlassian.com KB *How to fetch the scope of a token via command line* (updated 2025-09-25) |
| Bitbucket Cloud | **API token** (Basic e-mail + token, or Bearer) | yes (`basic`) — the only kind `public-bitbucket` can be | **probably, unmeasured** | the KB speaks of "access tokens" generically; whether the header appears on a Basic request, and whether it reports the classic vocabulary (`repository`, `pullrequest:write`, `account` — *Bitbucket Cloud REST API scopes*) or the "modern identity scopes" the deprecation post promises, is not stated | as above, plus atlassian.com/blog deprecation post |
| Atlassian (Jira, Confluence) | **classic API token** (Basic) | yes (`basic`) | **nothing to read** | there are no scopes: "Unlike classic tokens, which grant all permissions available to the user, scoped tokens restrict access to only the selected scopes." The authority is the *user's*; `GET /rest/api/3/mypermissions` reports that, per project, and is not a token property. New tokens expire in one year by default since 2024-12-15 | support.atlassian.com *Scoped API tokens in Confluence Cloud*; *Manage API tokens for your Atlassian account* |
| Atlassian | **scoped API token** (Basic) | **not with today's adapters** | no read-back documented | scoped tokens are honoured only at `https://api.atlassian.com/ex/jira/{cloudId}` / `/ex/confluence/{cloudId}`; the adapters call `{baseUrl}/rest/api/2/…` on the site host (`JiraContextProvider.java:95`). Unmeasured whether they work when the base URL is the gateway form | same two pages |
| Atlassian | OAuth 2.0 (3LO) | no | yes — `GET /oauth/token/accessible-resources` returns `scopes` per site | — | developer.atlassian.com *OAuth 2.0 (3LO) apps*. Moot without a 3LO flow |

**The verdict.** Scopes are readable for GitHub classic PATs, GitLab PATs and Bitbucket access
tokens; they are **not readable** for GitHub fine-grained PATs and **do not exist** for Atlassian
classic API tokens; two further kinds are unmeasured. A design whose row *means* "what the token's
scopes allow" cannot be built for two of the families the product accepts today, and GitHub's
fine-grained token is the kind GitHub steers new users toward. Detection can inform a registration;
it cannot decide one.

**Scope is necessary, not sufficient, on every forge.** A GitHub fine-grained token "is limited to
access resources owned by a single user or organization" and "organization owners can require
approval" of it; classic tokens can be restricted by organisation policy (*Managing your personal
access tokens*). A GitLab token's scopes say nothing about the bot user's **role** on the project,
which is what decides whether `write_repository` can actually push. A Bitbucket token's `repository:write`
is bounded by the account's per-repository permission. So "this token reports write" answers "may
it push *somewhere*", and only a per-repository probe answers "may it push *here*" — GitHub
`GET /repos/{o}/{r}` → `permissions.push`, GitLab `GET /projects/:id` → `permissions`, Bitbucket
`GET /user/permissions/repositories` — the verification the 2026-09-07 spec deferred in §11 as
adapter work. Those are repository questions and belong on the repository screen, which is where
the operator wants the centre to be.

**The three failure directions.**

- *Scopes cannot be read.* Refusing the registration would refuse every fine-grained GitHub token
  and every Atlassian token, i.e. the modern kinds. The only honest behaviour is the one the product
  already has: the operator declares the role, the product probes what a probe can prove (whoami,
  repository reachable, a login present for Factory — `MachineAccounts.canAuthenticateAPush`), and
  reports "this token kind does not report its scopes" rather than a green tick it has not earned.
- *Scopes say write, the repository says no.* Undetectable at the account; detectable per
  repository by the permission endpoints above. An account-level "can push" chip would be the
  false green the spec refused (§5.2: "a false 'can push' would be worse than none").
- *Scopes change after registration.* Nothing re-checks (§2.4). Rotation is the operator re-pasting;
  narrowing by an admin is discovered by a failing review, and for the `code` provider not even
  then. A scheduled re-probe is a separate, small item and is independent of the registry shape.

## 5. The security argument: what ADR-038's separation buys

ADR-038 says the factory pushes as a **dedicated machine account**, "never the review bot's",
and `V44`'s `UNIQUE (type, workspace, role)` is its schema. The operator's "one account, roles from
scopes" would allow one identity to be reviewer and factory. Read plainly, here is what the
separation prevents.

**The concrete failure: with one identity, the allowlist and the self-loop guard contradict each
other.** Comment-derived events are dropped when their author is the reviewer's `bot_account_id`
(`IntegrationSaga.isBotAuthored`, `:701-708`) — ADR-013's loop guard. Pull-request events and
commands are admitted only when the author is on the provider's allowlist (`:729`, `:265`, `:302`),
an empty list meaning everyone; `/fix` additionally refuses an empty list and matches on the stable
id only (`:388-403`), because its output is "a branch pushed as the machine account". Now give the
factory the reviewer's identity and set a non-empty allowlist, which is the configuration the
factory's own threat model recommends. To have the factory's pull requests reviewed, its identity
must be listed — but it is the reviewer's identity, so every comment the reviewer posts is now
authored by an allowed commander, and the only thing standing between a fenced-but-injected review
comment and a `/fix` is the loop guard. Two identities dissolve the contradiction: the factory's id
is listed, the reviewer's is not, and neither rule needs a special case. (ADR-038 phrases this as
"the bot could then command itself"; `ProviderClients.java:85-95` later corrected a *different*
claim — that the reviewer would skip its own pull requests by default — and the correction leaves
this one standing.)

**The audit trail.** `factory_run` records the identity it pushed as and the review row records
that its pull request is factory-authored, "neither inferred from an account name" (ADR-038). FR-F22
human takeover, "a person pushed to this branch", is decidable because "not the machine account,
not the bot" is a fact. With one identity that fact still exists, so this argument alone does not
force two accounts; it is the allowlist argument that does.

**What a leaked token reaches, and where each token lives.** The reviewer's token is packed onto
`cs.commands` for **every review** (KEK-encrypted), held in the review worker's memory while it
processes untrusted pull-request text, and sits in any dead-letter row — which is why
`GET /api/dlq` is admin-only: "a dead-letter row carries the raw wire record … or carrying a brokered
credential" (`docs/SECURITY.md:44-46`). The factory's token is packed **per run** under an AAD of
run id and slot, reaches the init and publisher containers and never the agent
(`Credentials.java:66-79`; `docs/UNVERIFIED.md:184-189`). One identity would put a push-capable
token on every review's command record and in the worker that reads the internet. That is a
widening ADR-036 forbids, and it is the second reason the pair must stay apart.

**Which pairs may share one identity.** Tested against the table in §2.3:

| Pair | Verdict | Why |
|---|---|---|
| reviewer + context reader (same host) | **safe** | both read; the comment identity is the bot humans already see; the issue and file reads are on the same host with scopes the reviewer's token already needs (`repo` / `read_api`). The `code` source is not merely shareable — it calls the endpoint the reviewer already calls (§2.3), so it is redundant with the reviewer's token |
| factory + context reader / work-source writer (same host) | **safe** | M3's work source is designed to be the factory account on GitHub/GitLab; ADR-035's "rights, not transport" holds because a factory token handed to a read-only `ContextProvider` adapter can only read — "an absent method cannot be bypassed" |
| Jira + Confluence (one Atlassian account) | **safe** | one identity system, one site, one e-mail already (§3); classic tokens have no scopes to differ by |
| reviewer + factory | **must stay two identities** | the allowlist contradiction and the token blast radius above; ADR-038 already pays the cost ("on Bitbucket and GitLab it costs the operator one additional account and token … documented as a prerequisite") |

The lead's prior is confirmed with one sharpening: "context reader" is not a *role* an account
holds beside reviewer or factory; it is a *use* — a source that reads through this account. Modelling
it as a role would put a third value into the key ADR-038 built, for a thing that needs no key.

**"Separable" means two accounts, not merely two rows.** The identity is the token's account
(`bot_account_id`), and the allowlist and loop guard key on it. A row that holds both roles is one
token, one identity, and the contradiction returns whether or not the operator "explicitly" ticked
both boxes. So the product should keep refusing it at the row: keep `role` a scalar (REVIEWER |
FACTORY, plus a value for accounts with no forge authority — §6.1) rather than a set with a CHECK,
because a scalar cannot express the forbidden combination at all. A deployment that genuinely wants
one identity for both is asking for the workspace-policy object of spec §11 (a list that can tell
"the bot as commenter" from "the bot as author"), and that does not exist. GitHub App deployments
remain the cheap case ADR-038 named: the installation is its own identity, so the second account
costs no second human-managed account — but only once an App adapter exists (§4).

## 6. Two options, and a recommendation

Shapes considered: *one registry with capabilities per account*, *two registries linked by
reference*, and *detected-scope advisory*. The third is not a shape — it is a feature either shape
carries (§6.3) — so the choice is between the first two. Both are written against the direction
the operator fixed on 2026-09-08: the workspace belongs to the repository; a repository is the
centre; **context sources should use accounts**. Neither option moves the workspace; §6.4 says why.

### 6.1 Option A — accounts own every credential; a context source references an account (recommended)

**What the operator does.** *Accounts → Add*: choose a kind (a forge: `github` / `gitlab` /
`bitbucket-cloud`; or an Atlassian site), the base URL, the auth kind and **one token**. For a forge
account, choose **Role** Reviewer or Factory, fixed at registration as today. For an Atlassian
account there is no forge role. The product runs the check it runs now and, where the kind allows,
reads the scopes and shows them (§6.3). *Context → Add source*: choose the type (`jira`,
`confluence`, `github-issues`, `gitlab-issues`, `code`), its source-specific fields (project keys,
path allowlist) and **an account** from a picker filtered to compatible kinds. The source has no
token field.

**What one row means.** An Accounts row: *this identity, this token, at this host, with this forge
authority if any*. A Context row: *read this kind of thing at this host, as that account*. The
Accounts table gains a computed **Used by** column — `Reviewer`, `Factory`, and the sources that
reference the row — which is the operator's "reviewer and context reader" reading of an account,
derived from references rather than declared.

**Schema.** `context_provider` loses `auth_kind`, `auth_username`, `auth_secret` and gains
`account_id` → `scm_provider(id)` (rename the table to `account` later or never; the name is
internal). `scm_provider` admits kind `atlassian` with `workspace` NULL and a third `role` value for
"no forge authority" (call it `CONTEXT`; a nullable role would let a forge row lose its role by
accident, which is the incident `docs/HISTORY.md:1391,1397` records). `UNIQUE (type, workspace,
role)` keeps working unchanged — NULL workspaces do not collide, so several Atlassian accounts per
site are allowed and a source picks one. The dead `is_default` goes.

**Migration of the eleven live rows, without discarding or re-pasting a secret.** The six
`scm_provider` rows are untouched. Each of the five `context_provider` rows becomes a source
pointing at a **new account created from its own credential** — a Java migration, because the
ciphertext moves from AAD `context-provider:<id>` to `provider:<newId>` and needs the keyset (§2.4).
Kind by type: `jira`/`confluence` → `atlassian`; `github-issues`/`code` at a GitHub host → `github`;
`gitlab-issues` → `gitlab`; role `CONTEXT`, workspace NULL. The same migration *may* collapse two
rows on one host whose decrypted secrets are byte-equal into one account, because equality of
plaintext is decidable there and nowhere else; whether any of the operator's rows collapse is
unmeasured (§3). Rows that differ stay separate, and the operator dedupes by hand from the screen:
point `github-issues` and `github-repository-provider` at `public-github` (after rotating its dead
token), delete the two migrated accounts, and likewise on GitLab and the Atlassian site. Nothing is
lost that the operator did not choose to delete.

**Lookups that change.** `ContextProviderRegistry.resolveAllEnabled` joins the account for the
credential; `ContextCredential` gains the account's `type` so the worker's `code` reader is chosen
by the account's platform rather than by host substring — one wire component, added with a wither
and a snapshot update (CLAUDE.md's wire-record trap), and `techdebt/global/3-2-…` closes.
`ContextProviderResource.check` reads the account's secret; `ContextKeyValidator` loses
`codePlatform`. `ProviderResource.TYPES` (`:52`) widens past `ProviderClients.SUPPORTED_TYPES`, and
the registration check dispatches by kind in `ProviderClients` — a composition-root selection, which
is what ADR-020 permits — using the existing `/myself` and `/user/current` probes for Atlassian.
**`ProviderRegistry.resolve(type, workspace, role)` and `MachineAccounts.resolve` do not change**;
the review and run pipelines never learn this happened. The review worker changes one `switch`
arm; the gateway changes nothing.

**ADRs.** One new record: *credentials live on accounts; a source references an account; `role`
stays a scalar so REVIEWER and FACTORY cannot share a row*. ADR-038 stands unamended. ADR-035 is
satisfied rather than touched — the same account narrowed by the adapter it is handed is its
"rights, not transport". ADR-020 is respected by where the kind dispatch lives.

**Size.** Medium: one Java migration, three orchestrator classes, one contract component, one
worker arm, two screens (the Context form's token fields become a picker; the Accounts form gains a
kind and the table a column), the check dispatch. Tests along the lines of spec §9, plus the
mutation checks the migration deserves (a source whose account is disabled must resolve to nothing,
not to the stale token).

**What it does not fix.** The workspace still sits on the account for forge rows (M3, §6.4).
Push rights are still not verified per repository (§4). Many accounts per (forge, workspace, role)
still cannot exist. Nothing re-checks on a schedule. A `code` source is still a row even though the
reviewer's token could serve it with no row at all — whether `code` becomes "any account with
repository read on the review's host" is an M3 question, because it depends on the repository
becoming the centre.

### 6.2 Option B — one table, capabilities per row, sources derived from accounts

**What the operator does.** *Accounts → Add*: kind, host, token, and tick **capabilities** —
Review, Push, Read issues, Read code, Read pages — with the source-specific fields (Jira project
keys, code path allowlist) on the account. There is no Context screen; sources are what the
capabilities imply: every enabled GitHub account with *Read issues* is a `github-issues` provider for
its host, and so on.

**What one row means.** *This identity may do these things at this host.* That is the literal form
of "roles based on their scopes".

**Migration.** Forge rows keep. GitHub/GitLab context rows either merge into the forge account on
the same host — discarding their token, since the product cannot know it equals the forge token,
and requiring the operator to tick the capability on the forge account and make sure that token has
the scope — or become read-only accounts, which is Option A with a different label. Atlassian rows
become accounts carrying `project_keys`.

**Lookups that change.** Reviewer and factory as in A. The context path becomes a mapping from
(kind, capability) to provider type — `github` + issues → `github-issues`, `github` + code → `code`
— which is a provider-shaped decision the core must make; ADR-020 allows it only in a composition
root, and the `spire-arch` name scan will object anywhere else. `ContextCredential` changes shape
(source fields move onto the account). The per-source `project_keys` and the `code` path allowlist
become per-account, which is the wrong grain: one Jira account serving two projects wants two key
lists, and one GitHub account may reasonably read code under one path allowlist and issues under
another repository list.

**ADRs.** A new record for the merge; ADR-035 needs a note distinguishing "capability on an account"
from "capability pack"; ADR-020 needs the derivation allowlisted.

**Size.** Large: two screens collapse into one with more fields, the contract and worker packing
change shape, the migration discards or duplicates tokens rather than moving them.

**What it does not fix.** Everything A does not, plus one thing it makes worse: it binds
capabilities to a *host* just before M3 binds accounts to a *repository*, so M3 would have to move
what B just built.

**What B would buy over A.** One fewer screen, and a row whose meaning is the operator's sentence.
But §4 shows the ticks would be the operator's declaration anyway — for GitHub fine-grained and
Atlassian tokens there is nothing to read them from — so B is A with a larger migration, a
wrong-grained allowlist and no additional truth in the row.

### 6.3 Detected scopes: advisory, inside either option

At registration and on **Check**, read what the token kind reports: `X-OAuth-Scopes` from the
`/user` response (GitHub classic), `x-oauth-scopes` from `/user` or `/repositories/{ws}` (Bitbucket),
`GET /personal_access_tokens/self` (GitLab PAT). Store it as text with a timestamp on the account,
show it beside the row ("Token reports: `repo`, `read:org`" / "This token kind does not report its
scopes"), and raise an attention row when a Factory account reports scopes that exclude write or a
Reviewer account reports none that read. **Never refuse on it**, and never render green from it; the
hard refusals stay the ones a probe proves (`no-login` for Factory; an unreachable host). This is
what "detection informs, never decides" costs: a header read the adapters already receive, one
column, one attention rule. It is a small addition to A and unchanged under B.

### 6.4 Relation to the repository-centred model (M3)

**This work is a prerequisite for that one, not the same change and not independent of it.** The
model the operator chose has a repository "list the accounts that may act on it". That list is only
meaningful if accounts are the *only* credential holders — otherwise the repository must also list
context tokens, and the split the operator complained about reappears one screen over. It also
moves the workspace off `scm_provider`, which is the same table A touches; doing A first means M3's
migration changes the key and nothing else, and a failure in either is attributable to one of them.
Doing both as one migration would hide which half broke, which is the shape three milestones paid
for. A already anticipates M3 in one respect — Atlassian accounts have no workspace at all — and
leaves `resolve(type, workspace, role)` alone so M3 can replace it once.

**Recommendation: Option A, with §6.3 folded in, before M3's re-model.** It is the direction the
operator already set for context sources, it removes every duplicated token the data shows without
discarding one, it needs no scope introspection to be correct and uses it where it exists, and it
keeps ADR-038's two identities by keeping `role` a scalar. B is what one would build if every token
reported its scopes; two of the four families do not, so B's row would say what the operator ticked
while claiming to say what the token can do.

## 7. What stays open, and what is unmeasured

- Whether the three GitHub rows hold one secret; whether `gitlub-issues` is the `public-gitlab` bot
  (§3). Decidable only by decrypting, which the migration in §6.1 may do and this analysis did not.
- Whether a GitHub **classic** PAT returns `X-OAuth-Scopes` (documented for OAuth tokens; relied on
  by the community for classic PATs; not measured here — one `curl -I` with a live token settles it).
- Whether `GET /personal_access_tokens/self` answers for a GitLab **project/group** access token,
  and which version introduced `self` (§4).
- Whether Bitbucket returns `x-oauth-scopes` on a **Basic** request with an API token, and in which
  vocabulary (§4).
- Whether the Jira/Confluence adapters work at all with a **scoped** Atlassian token via the
  `api.atlassian.com/ex/…` base URL (§4). Today's adapters assume classic tokens on the site host.
- Per-repository push-rights verification (spec §11) — belongs to the repository screen and to M3.
- A scheduled re-check of stored credentials; the `code` provider's silent credential rejection
  (`techdebt/spire-context-code/3-3-…`).
- Whether `code` survives as a source row or becomes a capability of any repository-reading account
  on the review's host — an M3 question (§6.1).
- `docs/DATA-MODEL.md` §5 still describes neither registry (spec §11 noted it); whichever option is
  built should write the account table there.
- Nothing above was run: no migration was written, no token was probed, no Gradle task executed.
  The line references are as of this worktree on 2026-09-10 (`feat/accounts-and-roles`, merged to
  master as PR #120).
