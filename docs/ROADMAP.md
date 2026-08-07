# Roadmap

Phased plan. Estimates are rough person-weeks for one developer in private time; treat as relative
sizing, not commitments.

---

## Current status & next-up backlog (updated 2026-08-06)

This is the **live view** — what is actually built and what to pick next. The Phase 0–4 plan further
down is the original design-time roadmap (kept for reference).

### Delivered
- **P0 + P1**: event backbone, 3 services over Redpanda, real Bitbucket adapter set, event store,
  idempotent posting + stale-run guard, live operator UI (`spire-ui`).
- **Encryption at rest** (ADR-009): `EncryptionService`/Tink in the shared `spire-encryption` module.
- **Provider registry** (Settings → Providers): encrypted credentials in the DB, no `.env` tokens.
- **ADR-015**: active-mode worker gets per-command SCM credentials brokered (encrypted) over the bus.
- **GitHub adapter** (`spire-scm-github`): registry is type-aware end-to-end; GitHub PRs register and
  observe live (verified against `github.com/artyomsv/spire-test`). GitHub/GitLab were built out first
  because Bitbucket API access was blocked at the time; that constraint has since lifted and all three
  are live-verified to parity (2026-07-26).
- **UI**: provider badge, dedicated Title/Author columns, truncate-plus-copy (`CopyableValue`),
  provider-aware "Open in …", non-overflowing metadata card.
- **Correctness pass (C7/C8/C9, 2026-07-07)**: provider type persisted on the review row (badge is now
  the real registered type, fixing self-hosted GitLab/Bitbucket); author numeric id surfaced in the list
  + detail; **bounded auto-retry** (ADR-016) — a transient failure restarts the pipeline up to
  `spire.review.max-attempts` (default 3) then fails terminally, ending the "stuck in REVIEWING" stall.
  Covered by orchestrator unit + Postgres tests and a new `spire-ui` vitest suite.
- **Provider token auto-resolve/validate (2026-07-07)**: registering a provider now calls the SCM's
  "who am I" (`IdentitySource`) to fill the bot account id from the token owner and validate the token
  up front — no more manual `curl … /user`. GitHub/Bitbucket adapters + WireMock tests.
- **GitHub active mode verified live (2026-07-07)**: the worker posts a real review to a GitHub PR —
  inline comments anchored to changed lines + a summary — proven end-to-end against
  `github.com/artyomsv/spire-test` via the manual **Register PR** path (no webhook). Closes backlog
  item 1. The no-tunnel runbook is documented in SMOKE-TEST.md (Mode C).
- **Per-model LLM parameter profiles + failure detail + review deletion (2026-07-08)**: each catalog
  model declares its API dialect (output token param `max_tokens`/`max_completion_tokens`/none, custom
  temperature, reasoning effort, extra-params passthrough), brokered to the worker keyed by model name,
  so reasoning models (o1/o3/gpt-5) no longer fail on `max_tokens` or a rejected temperature (ADR-018).
  Terminal-failure errors are persisted (encrypted) and shown on the detail page. A review and all of
  its data can be deleted from the detail page behind a confirmation dialog, broadcast live.
- **GitLab adapter (`spire-scm-gitlab`) built (2026-07-08)** · read + write over the manual Register PR
  path, baseUrl-driven (gitlab.com default, self-managed override). `GitLabDiffSource` (MR by `iid`,
  full `DiffRefs{base,start,head}` from `diff_refs`, header-less per-file diffs re-wrapped for the
  shared parser), `GitLabCommentSink` (summary note, inline discussion `position`, `discussion_id`
  reply), URL-encoded nested-group project paths. Registry/worker/UI wired for the `gitlab` type; the
  manual-register slug + MR-URL (`/-/merge_requests/`) parsers now accept nested groups. WireMock unit
  suite (18) + an orchestrator nested-group register test green. **Verified live** against a real
  GitLab MR (diff → LLM → inline discussions + summary note) via the manual Register PR path; the
  no-tunnel runbook is documented in SMOKE-TEST.md (Mode D). Closes backlog item 2.
- **Native Anthropic + Gemini LLM providers (2026-07-08)**: `spire-llm` gains first-class
  `anthropic` and `gemini` provider types alongside the OpenAI-compatible path, via the native
  LangChain4j clients (`AnthropicChatModel`, `GoogleAiGeminiChatModel`). The one `LangChain4jLlmProvider`
  serves all three — the wrapped `ChatModel` and the request-parameter factory vary: OpenAI keeps the
  per-model `ModelParamProfile` dialect, the native clients take a profile-free shape (temperature +
  output cap). baseUrl-driven (gitlab-style self-managed/proxy override), per-type key validation on
  save (`Authorization: Bearer` / `x-api-key` / `x-goog-api-key` against `/models`), registry/worker/UI
  wired, and the model-catalog form hides the OpenAI-only dialect knobs for native types. Unit +
  WireMock tests green.
- **Jira context provider (`spire-context-jira`) — first ContextProvider (B6, 2026-07-08)**: the P1
  context-pipeline stub is now real. The `ContextProvider` SPI, event chain (`ContextRequested` →
  `ContextContributed` → `ContextAssembled`) and the `ReviewPromptBuilder` context slot already existed;
  this wires them end to end. Issue keys (`PROJ-123`) are parsed from the PR title/branch at diff-fetch
  time and threaded through `GatherContext`; `JiraContextProvider` (v2 REST, baseUrl-driven Cloud +
  Data Center, basic/bearer auth, SSRF-guarded like the SCM adapters) resolves each into a
  `ContextItem` (summary + description + status + type). `ContextWorker` is now the real aggregator —
  `@All`-style per-command fan-out with a bounded 20s timeout — persisting the assembled context
  encrypted (Tink, AAD=reviewId) to a **Postgres `BlobStore`** (`worker.context_blob`), threading its
  `contextRef` into `GenerateReview`, where `ReviewWorker` loads it into the prompt (untrusted-fenced).
  Credentials live in a new encrypted **context-provider registry** (Settings → Context, `/api/context-providers`)
  brokered per-command exactly like SCM/LLM creds (ADR-015). Blob deletion is wired at
  all three sites (review delete, re-run, re-assembly) keyed by `review_id` — no orphaned blobs.
  Unit + WireMock + REST-layer tests green. **Confluence** followed as the second ContextProvider — see below.
- **Confluence context provider (`spire-context-confluence`) — second ContextProvider (B6, 2026-07-09)**:
  the SPI proven generic by adding a second source with zero core changes to the contract, registry,
  encryption, or aggregator. Where Jira is ticket-key-driven, Confluence is **link-driven** (EVENT-MODEL
  S4): `DiffWorker` now also extracts candidate URLs from the PR title/branch/description onto a new
  `DiffFetched.links`, threaded through `GatherContext` into `ContextRequest.links`; `ConfluenceContextProvider`
  narrows them to its configured host, pulls the numeric page id out of each (`.../pages/123/…` or
  `?pageId=123`), and fetches `/rest/api/content/{id}?expand=body.storage,space,version` — baseUrl-driven
  Cloud (`…/wiki`) + Data Center, basic/bearer auth, SSRF-guarded like the SCM adapters. Storage-format
  XHTML is stripped to plain text into a `ContextItem` (`kind=CONFLUENCE_PAGE`, title + space + body).
  The registry's generic `projectKeys` column doubles as an optional Confluence **space-key** allow-list,
  so no DB migration was needed; the worker dispatch (`case "confluence"`), the REST type allow-list,
  the connectivity check (`/rest/api/user/current`), and the Settings preview (paste a page URL/id) all
  gained a Confluence branch. Settings → Context now has a Jira/Confluence type selector with type-aware
  copy. Unit + WireMock + REST-layer tests green.
- **Context: all-provider matching + two-level collection (2026-07-09)**: context providers dropped the
  single-"default" model (unlike LLM, where one active model is right) — **every enabled** provider is
  brokered to the worker (`WorkerContextCredentials.packAll`, encrypted `List<ContextCredential>`), and a
  PR's references are matched against all of them, so one review can pull Jira **and** Confluence at once.
  `ContextWorker` now does a **bounded two-level** fetch: level 1 resolves the PR's own refs, level 2 mines
  the retrieved text (e.g. a Jira ticket body) for NEW Jira keys / Confluence links and fetches those once —
  then stops (`MAX_DEPTH=2`), which breaks a jira→confluence→jira cycle. A page linked from both the PR and a
  fetched ticket is de-duplicated (keys case-insensitively, links by page id), not re-fetched. The UI lost
  the Default column / Set-default action and the `/default` endpoint. New aggregator + credential-list tests green.
- **GitHub webhook ingress — first real auto-register edge (D3, 2026-07-09)**: PRs now register on their
  own. A single endpoint `POST /webhooks/github/{key}` routes each delivery by an unguessable per-repo key
  to its `webhook_repo` row. `GitHubIngress` verifies `X-Hub-Signature-256` (constant-time) and translates
  `pull_request` (opened/reopened→OPENED, synchronize→UPDATED, closed→MERGED/DECLINED) and `/review`
  issue-comments into the same `PullRequestEventReceived`/`PullRequestClosed`/`ManualCommandReceived` the
  manual path emits — so the whole saga (incl. the decider's same-commit re-delivery no-op) is untouched.
  **The gateway OWNS the webhook registry** (schema-per-service): its own `gateway` Postgres schema +
  Flyway + `WebhookRepoRegistry` + `/api/webhook-repos` CRUD + Settings → **Webhooks** UI. Secrets are
  Tink-encrypted under a **dedicated webhook keyset the gateway alone holds** (never the master keyset), and
  the gateway's DB role is **scoped to its own schema** — so a compromised internet-facing edge can verify
  signatures but cannot read (or reach) the SCM/LLM API-token registry, the event store, or anything else.
  The orchestrator never sees webhook secrets; provider resolution downstream is unchanged (by PR-owner
  against `scm_provider`). Publish tail shared with the Bitbucket edge (`IntegrationPublisher`). Ingress +
  gateway CRUD/verify + saga-idempotency + UI tests green. Live runbook: SMOKE-TEST.md **Mode E** (Tailscale Funnel).

- **Conversational replies (B5, S8) — delivered (2026-07-16..18)**: author replies in a bot thread get
  in-thread LLM answers (`AnswerFollowUp`, per-comment idempotency, GitHub-first via `ThreadSource`);
  per-provider conversation levels (report-only / explain / interactive) in Settings; finding-linked
  threads nested under their findings on the detail page with markdown rendering and live updates.
- **Unified keyed webhook ingress — all three SCMs (2026-07-16)**: GitLab (`X-Gitlab-Token`) and
  Bitbucket folded onto the per-repo registry edge `POST /webhooks/{provider}/{key}`; the legacy
  single-secret Bitbucket edge removed. Shared `RegistryWebhookEdge` (resolve→verify→translate→scope→
  publish). Dev exposure via opt-in Cloudflare quick-tunnel.
- **Re-review reconciliation (ADR-019) — delivered + hardened (2026-07-18..20)**: follow-up commits
  reconcile instead of blind re-review — command-carried prior-run snapshot, two claim-guarded LLM
  calls (reconcile verdicts on the incremental diff + review with an exclusion list), per-verdict
  posting (reply/auto-resolve fixed findings, quiet `UNCHANGED` on untouched ones, in-place summary
  update), `resolveThread`/`updateComment`/`fetchCompareDiff` SPI capabilities (all three SCMs resolve
  since 2026-07-25; an unresolvable thread degrades to reply-only). Hardened through live multi-round
  testing: carry-forward open-findings baseline, file
  rename following, same-anchor merging + ghost cleanup, unified findings list in the UI, live
  dashboard updates on conversation activity. V17–V20; verified live on GitHub (`artyomsv/spire-test`
  PRs #8–#11).
- **Operator-controlled prompts — delivered (2026-07-21..23)**: closes item 15, which this doc still
  listed as needing a design pass. DB-backed versioned templates (V23 `prompt_template`) seeded from
  the built-in `PromptCatalog`, with reset-to-default; `PromptTemplate`/`PromptVariable`/
  `PromptValidation` in `spire-contract` keep the structural invariants (untrusted-data fencing,
  sentinel neutralization, token clipping, JSON output contract) unbreakable by customization;
  `PromptRegistry`/`PromptResource` in the orchestrator broker templates to the worker
  (`WorkerPromptTemplates`); Settings → **Prompts** (`PromptsSettings.tsx` + `PromptDetail.tsx`) is a
  slot-aware editor, not a raw textarea. Scope is global (per-repo deferred).
- **GitHub finalized + PR-12 fix batch (2026-07-21..23)**: 403/GraphQL `RATE_LIMITED` classify as
  retryable with a Retry-After-aware posting backoff; `/review` forces a re-run; draft-PR skip;
  OLD-side/multi-line anchors; plain PR comments get conversational answers. Reviews-list rows show
  reconciled open-finding counts and cumulative cost instead of overwritten last-run columns;
  `STILL_OPEN` downgrades at hunk not file granularity; a transient `answering` flag (V21) drives a
  responding indicator; distinct `pr_state` badge (V22) from the open/close webhook on all three SCMs.
- **Reusable Select component (2026-07-23)**: all 13 native `<select>` elements across 5 files replaced
  by a hand-rolled accessible, theme-aware `Select` that escapes modal clipping and handles long
  `name · type · workspace` labels.
- **Scheduled retry backoff (2026-07-23)**: the ADR-016 retry budget became an operator Settings field
  and retries now back off via a scheduled re-drive (V25 `review_retry_at`) instead of restarting
  immediately.
- **Three-provider parity verified live (2026-07-25..26)**: closes item 13. S1–S11 run end to end on a
  real GitHub PR, GitLab MR **and** Bitbucket PR — 11/11 on all three (SMOKE-TEST.md **Mode G**, now the
  reusable regression script). 13 defects the runs exposed are fixed with tests: cross-provider
  resolution by stored SCM type, conversation root-keying (V24), Bitbucket thread resolve, GitHub's
  false-success `resolveThread`, re-posted-finding reconciliation, Bitbucket `@{account_id}` mentions,
  fenced code in follow-ups, GitLab's compare diff parsing to zero files (silently disabling
  reconciliation on GitLab alone), the silent turn cap (now an explicit `NotifyTurnCap` notice that
  costs no LLM call and consumes no turn), over-broad follow-up replies, and four UI display bugs.
- **Provider neutrality enforced by the build (ADR-020, 2026-07-26)**: new `spire-arch` module fails the
  build when a core module names an SCM or context provider outside a reasoned allowlist — scanning
  **source text**, since the leaks that caused real bugs were string literals. Three name-carrying leaks
  fixed (one a real defect: a GitHub/GitLab 404 escaping as a 500) plus six **semantic** leaks a name
  scan cannot see, including a live GitLab defect where thread recency compared opaque discussion ids as
  `BigInteger`, making ADR-019 reconciliation inert on GitLab. `DiffRefs` deleted in favour of a single
  `headCommit`; mention syntax moved into each ingress; new credential-free `ContextReferenceSource` SPI
  so reference extraction (which runs before context credentials are brokered) needs no configured
  provider. Allowlist: 13 entries, every one a composition root or `ScmType`.
- **Split licensing (ADR-021, 2026-07-26)**: source-available, licensed per module — Apache-2.0 for the
  SPI/libraries/reference adapters, FSL-1.1-ALv2 for the four deployables, each carrying its own
  `LICENSE`; map and reasoning in `LICENSING.md`, DCO + relicensing grant in `CONTRIBUTING.md`.
  Invariant: no Apache-2.0 module may depend on a service module. The same pass corrected the PR-Agent
  provenance language (read as prior art, no upstream code used — `NOTICE`, and the shipped-vs-upstream
  comparison recorded in RESEARCH.md §4).
- **Operator attention panel (2026-07-27..30)**: a topbar bell whose rows are conditions true right now,
  derived on demand — no usable default LLM provider, no SCM provider, unresolved bot identity, rejected
  credential, stuck/failed reviews, pending DLQ, webhook registrations with no secret or refusing
  deliveries. Two same-shape feeds (`AttentionView`) from the orchestrator and gateway, each **pushed
  over its own WebSocket** and merged client-side — no new topic, no non-`reviewId` message class, since
  most of the catalog is state rather than events. Credential health rides on work already happening
  (V28 three-valued `last_check_ok`; `ScmApiException.isUnauthorized()` is 401-only because one provider
  overloads 403 for rate limiting). Stuck/failed reviews are per-review, navigable and dismissable
  (V29) — the one place where a row describes a past event no repair can clear. Verified live on a real
  GitHub PR incl. self-clearing on the next verified delivery. Merged as PR #2.
- **GitHub Issues + GitLab Issues context providers (2026-07-30)**: closes item 14, the last unbuilt
  ticket-reference source. Two new Apache-2.0 modules (`spire-context-github`, `spire-context-gitlab`)
  resolve issues, pull/merge requests and GitLab epics into `ContextItem`s (kinds `ISSUE`/
  `PULL_REQUEST`/`EPIC`), gated by a new `ScmType` on `GatherContext`/`ContextRequest` so a
  repo-relative `#123` cannot resolve against a same-named repository on the wrong platform. Ahead of
  the two adapters, the pinned-redirect SSRF-guarded HTTP client Jira and Confluence each carried a
  copy of moved into a new Apache-2.0 module, `spire-http` — one of three Apache-2.0 modules this
  branch adds (with `spire-context-github`/`spire-context-gitlab`), bringing the total to thirteen
  per `LICENSING.md`. See CLAUDE.md for the full write-up and test totals; SMOKE-TEST.md **Mode I**
  covers live verification across all four provider types, including the cross-platform negative pass.
- **Repo rules — the `.codespire` file (2026-08-01)**: closes Phase 2's last unbuilt item. A repository
  states its own conventions in a root `.codespire` file, contributed as `ContextContributed{source=RULES}`
  / `ContextItem{kind=RULE}` by a credential-free `RulesContextProvider` — the rules ride in on
  `DiffFetched.repoRules` rather than being fetched by the aggregator. Read from the PR's **target
  branch, never the reviewed commit**, so a PR cannot rewrite the reviewer's instructions in the same
  PR; prompt fencing cannot cover that, because rules are *meant* to steer the review. New SPI method
  `DiffSource.fetchTextFileOnBranch` on all three adapters. Format and guidance: `docs/REPO-RULES.md`.
- **Debt-and-guard wave (2026-08-02)**: ten commits closing tracked debt, with **no roadmap advance** —
  the open items below are untouched. Three user-visible defects fixed (a headerless diff parsed to
  **zero files**; the Context card never live-updated within a run; the real adapters' `apiHost()`
  covered only by fakes) and four **guards** added — build checks that fail on a debt's
  *reintroduction*, not merely its removal: the framework-free boundary of `spire-contract`/`spire-diff`
  (`jackson-annotations` the one allowlisted exception), a fourth hand-rolled redirect loop, and a
  **vacuity hole in the contract-compat gate itself** (it read zero event types as zero failures). Plus
  a per-host circuit breaker over the SCM retry ladder, keyed by a new no-default `DiffSource.apiHost()`,
  and `spire-ui` on React 19 + react-router 8 (`npm audit` 0). 1027 Java tests / 124 suites; 192 vitest
  / 31 files. Tech debt 9 → 6 items, no high.
- **Debt wave 2 (2026-08-03)**: three more tracked items closed, again with **no roadmap advance** —
  tech debt 6 → 4, nothing above Low. Component tests for the two largest forms and the route shell
  (which also exposed mock-state leaking between tests, so `not.toHaveBeenCalled()` was passing on
  test ordering — fixed in the shared setup); the **circuit breaker extended to the LLM path**, the
  one with money attached, including the trap that the provider reports failure as a *failed future*
  rather than by throwing, and a fix to `FollowUpWorker` which would otherwise have DLQ'd every
  follow-up during a cooldown; and the reviews-list findings count **split into new vs carried-over**
  so its movement between rounds explains itself. 1039 Java tests / 125 suites; 228 vitest / 35 files.

- **Operator authentication — D10 delivered (2026-08-03)**: the dashboard and every REST/WebSocket
  endpoint now require an operator identity. **Hybrid OIDC** (ADR-022) — a cookie session for the
  browser and its four sockets, bearer for `curl`/CI — because a browser cannot set a header on a
  WebSocket handshake and a credential must not ride in a query string. Each service is its own OIDC
  client under its own URL prefix (orchestrator `/api`, sockets at `/api/ws/*`; gateway `/gw`; worker
  `/wk`): cookies scope by host+path, not by backend, so the prefixes are what stop one service
  receiving another's session credential. Deny-by-default policies with `/webhooks/*`, `/q/health*`
  and `/api/me` explicitly public; two roles across 21 resources, with `GET /api/dlq` admin because it
  returns raw wire records. The dashboard knows its own session, hides what a viewer may not do, and
  asks *why* a socket closed before reconnecting — the previous blind retry hammered the IdP on every
  routine five-minute expiry while reporting it as a gateway outage. Dev runs unauthenticated by
  default and refuses to start that way anywhere else. Preceded by a spike that overturned two of the
  plan's own predictions and three adversarial reviews that each falsified a design; the record is in
  [D10-AUTH-PLAN.md](D10-AUTH-PLAN.md), the live check is SMOKE-TEST.md **Mode J**. **Open by design:**
  TLS arrives with the production edge — until then this stops casual access, not an on-path attacker.

- **CI/CD + packaging delivered (2026-08-05):** nine GitHub Actions workflows, four images on GHCR, and
  a `deploy/` tree covering Compose, Helm and kustomize from one source of truth (chart → kustomize
  inflation → rendered YAML, drift-checked). The fast/slow test seam already existed on module
  boundaries and now lives in Gradle as `testFast` / `testServices`, guarded so a module in neither tier
  fails the build instead of being tested by nothing. **What the analysis in
  [CICD-AND-PACKAGING.md](CICD-AND-PACKAGING.md) did not anticipate is the largest piece of it:** D10 did
  not merely gate this work, it added scope, because ADR-022's cookie-path isolation is a property of the
  *deployment topology* and the single origin it needs was being supplied by the Vite dev proxy. The
  dashboard image is therefore a **reverse proxy**, not a static server, and its routing is a security
  control — `/webhooks` missing from it means every SCM delivery fails and no review ever starts. Six
  things were corrected by running rather than reasoning: the services resolve their datasource and
  broker only under `%dev`; nginx refuses to *start* on an unresolvable upstream; a `${VAR}` expression
  with no default does not enforce presence when the target is `Optional`, which had shipped forwarded
  header trust wide open; bearer tokens are audience-scoped per service, the counterpart of per-path
  cookies; `helm lint` exits 0 with every required value missing; and the repo is not Semgrep-clean, so
  Semgrep reports rather than blocks. 21 end-to-end checks pass against the packaged stack, including a
  WebSocket upgrade through nginx and the gateway's role being denied on the orchestrator schema by
  Postgres itself. **1074 Java tests / 130 suites; 265 vitest / 37 files.**

- **Code-scanning backlog cleared (2026-08-05):** the Security tab's 120 open alerts closed at source in
  seven classes. **Every action reference is now a commit SHA** with the version in a trailing comment —
  the comment is not decoration, it is what Dependabot's `github_actions` ecosystem parses, and without
  it a pin is a permanently unpatched action. Tags were dereferenced through the commits API rather than
  read off the ref, because an annotated tag's ref points at the *tag object* and pinning that SHA fails
  at runtime. Every Dependabot entry gained a `cooldown` — the complementary defence, since pinning stops
  a tag being repointed while cooldown stops a freshly published version being proposed before anyone has
  looked at it, and it delays nothing that matters because security updates are exempt. The Trivy half
  split by **who can fix it**: 39 OS-package alerts inherited from the JRE base image close at build time
  with an `apk upgrade`, while 24 Java-dependency alerts were mostly closed by the *platform* moving
  3.37.1 → 3.38.1 — netty, PostgreSQL and OpenTelemetry each ship as a stack whose modules must move
  together, so hand-forcing thirty-odd coordinates loses to importing a combination upstream already
  tested. Semgrep still reports rather than blocks, now for the one honest reason: `p/default` resolves
  from the registry at run time, so a blocking gate would let a rule added upstream redden an untouched
  branch.
- **Operator sessions correct on every prefix (2026-08-06, PR #38):** three defects that all came from
  treating a session as per-browser when ADR-022 makes it per-prefix. A sibling login could be *recorded
  without running* — two callers race on a fresh page and `goToLogin` is first-caller-wins, so the loser
  wrote its `sessionStorage` mark while its navigation did nothing, retiring that prefix for the life of
  the tab and making the attention panel report the gateway unreachable every 1.5s. **Signing out ended
  `/api` alone**: measured, the gateway still answered its attention feed 200 afterwards and both sibling
  cookies were still held, because neither sibling had a logout endpoint at all. And a cold sign-in cost
  **three renders**, each booting the dashboard only to discover the next prefix missing; the login
  endpoints now hand each hop to the next under an opt-in `chain=1`, so one redirect sequence paints once.
  Opt-in is load-bearing — the unchained answer is what the session probes read, and chaining it would
  make a healthy service look unauthenticated because a *later* prefix had none. The call that actually
  decides a cold sign-in turned out to be `apiFetch`'s, not the shell's: the first data fetch is refused
  before `/api/me` answers and wins the race. **271 vitest / 37 files; 1074 Java tests / 130 suites.**
- **LLM cost is a priced ledger, not a fabricatable total (ADR-023, 2026-08-07):** the accounting a fleet
  spend cap would have to trust turned *unknown* into *zero* in four separate, individually-defensible
  places; fixing that came before the caps rather than alongside them, since a cap reading the old
  numbers would install cleanly and never fire for exactly the calls it exists to stop. `llm_charge`
  (V30) is now the ledger — one row per token type per call, priced at the rate **in force when the call
  happened** and snapshotted onto the row, so a later price edit cannot rewrite history and a rejected
  temporal price catalog was not needed to get that property. `pricing_mode` (`METERED`/`UNMETERED`/
  `UNKNOWN`) replaces a bare number, because no amount of validating a number distinguishes "this model
  is free" from "nobody told us the price" when both used to arrive as `0`. The vendor-usage partition is
  cross-checked against each vendor's own reported total — **per vendor, not uniformly**, since
  Anthropic's total is derived as `input + output` and excludes both its cache buckets entirely; a
  uniform check would have made every cached Anthropic call unpriceable, the cheap calls being the only
  ones that couldn't be priced. The priceable-model rule is enforced twice on purpose — at the registry
  (`LlmProviderRegistry`, so a bad configuration cannot exist, closing a gap a bypassing test proved
  live) and again pre-spend in `ResultSaga` (because pricing is post-hoc, so that is the last point an
  unpriceable review can be refused rather than merely reported) — and the same registry guard now
  refuses **renaming** a catalogued model still in use, not only deleting one; a rename orphaned
  referencing providers identically and was the one path that could defeat the guard after it had
  passed. The conversation path deliberately keeps no pre-spend check of its own — a follow-up answers a
  human already waiting, and the turn cap's old silent-drop failure is the shape this project does not
  want to repeat on that path — so it records cost honestly and leans on the registry guard instead.
  `UNIQUE (call_ref, token_type)` closes a real double-charge window: the prior write was an unguarded
  `INSERT` behind only a staleness check, so a redelivered result between `ReviewGenerated` and
  `ReviewCompleted` charged the same call twice. See ADR-023 for the full reasoning, including why the
  ADR-013 contract-compat gate did **not** catch the `ModelUsage` wire reshape (a documented blind spot,
  not a passed check) and why the break is safe anyway. **1138 Java tests / 142 suites; 290 vitest / 40
  files; `tsc --noEmit` silent.**

### Next-up backlog — pick by number (S/M/L = rough effort; ⚑ = needs a decision/credential from the operator)

**A. Finish the multi-SCM story (current thread)**
1. ✅ GitHub **active mode** — post a real review comment (2026-07-07). Complete GitHub loop
   (diff → LLM → inline + summary) proven live against `artyomsv/spire-test` PR #2 via manual
   Register PR. See SMOKE-TEST.md Mode C.
2. ✅ **GitLab adapter (Phase C)** — **verified live 2026-07-08.** baseUrl-driven (gitlab.com +
   self-managed), MR `iid`, 3 SHAs, discussion-thread replies, nested-group project paths (slug parsers
   widened). Read+write over manual Register PR; WireMock + register tests green; live diff → LLM →
   inline + summary confirmed. Webhook ingress deliberately omitted — see item 3. See SMOKE-TEST.md Mode D.
3. ✅ **Real webhooks (Phase D)** · M · **all three SCMs done** (GitHub 2026-07-09, GitLab + Bitbucket
   2026-07-16). A single
   registry-backed edge `POST /webhooks/github/{key}` auto-registers PRs on open/update/reopen (and
   `closed` → cancel; `/review` issue-comments → force). Per-repository registrations live in a new
   `webhook_repo` table (Settings → **Webhooks**): an unguessable routing `key` in the URL + a
   Tink-encrypted HMAC secret under a **dedicated webhook keyset** (`SPIRE_ENCRYPTION_WEBHOOK_KEYSET`),
   so the gateway verifies inbound signatures without ever holding the master keyset that unlocks API
   tokens. `GitHubIngress` (X-Hub-Signature-256, constant-time) translates to the same
   `PullRequestEventReceived` the manual path emits, so the whole downstream saga (incl. the decider's
   same-commit idempotency for re-deliveries) is unchanged. Live via **Tailscale Funnel** — see
   SMOKE-TEST.md **Mode E**. GitLab (`X-Gitlab-Token`, constant-time compare — GitLab does not sign the
   body) and Bitbucket were folded onto the same per-repo model on 2026-07-16 behind a shared
   `RegistryWebhookEdge`, and the legacy single-secret Bitbucket edge was removed. SMOKE-TEST.md
   **Mode F** covers the GitLab edge.

**B. Make the reviewer genuinely useful (P2 — currently diff-only)**
4. ✅ **`/review` command** (GitHub, 2026-07-21) — a `/review` PR comment forces a re-run via
   `ReviewRerunService` (see item 13 / the finalize-GitHub thread). GitLab/Bitbucket fold on next.
5. ✅ **Conversational replies** (2026-07-16..18, hardened through 2026-07-26) · M. In-thread LLM answers
   on all three SCMs, per-provider conversation levels, root-keyed multi-turn threads (V24), an explicit
   turn-cap notice, and replies scoped to the question asked.
6. ✅ **ContextProviders (Jira/Confluence)** · L. Enrich reviews with linked ticket & page context. Biggest lever.
   ✅ **Jira done (2026-07-08)** — SPI made real end-to-end (`spire-context-jira`, ticket-key extraction,
   worker-local aggregator, Postgres `BlobStore`, encrypted registry). ✅ **Confluence done (2026-07-09)** —
   second provider on the same SPI (`spire-context-confluence`, link-driven page resolution, `DiffFetched.links`).

**C. Correctness & robustness** — ✅ done (2026-07-07)
7. ✅ **Store provider type in the read model** · S. `review_status.provider_type` (V4); badge/label/
   "Open in …" key off the stored type with a URL-sniff fallback for legacy rows. Self-hosted now badges.
8. ✅ **Bounded auto-retry** · M. Saga-owned retry budget (ADR-016), `spire.review.max-attempts` (V5
   `attempt` column). Not per-call SmallRye FT — see the ADR for why.
9. ✅ **Author numeric id** · S. `author_id` surfaced on `ReviewSummary` + shown under `@username` in the
   list; `Attempt` on the detail page is now live too.

**E. SCM parity & new feature threads (added 2026-07-21)**
13. ✅ **SCM parity live-testing for the full loop** — **complete on all three SCMs (2026-07-26)** · M.
    GitHub is the proven reference (webhook → review → conversation → reconciliation, PRs #8–#11).
    ✅ **GitHub half done (2026-07-21..22)** — the live-use audit's 12 findings are fixed: truthful
    403/GraphQL rate-limit detection + posting backoff, `/review` wired to a forced re-run, draft-PR
    skip (`SPIRE_REVIEW_DRAFT_PRS`), honest 406/pagination failures, OLD-side/multi-line anchors,
    and summary-comment conversations.
    ✅ **GitLab + Bitbucket parity code delivered (2026-07-23)** — both adapters now match the GitHub
    feature set: `ThreadSource` on each CommentSink (the shared `FollowUpWorker`/`ConversationSaga`
    untouched — conversation lights up via the `instanceof ThreadSource` gate), GitLab `AuthorReplied`
    ingress + Bitbucket `topLevel` flag, draft/WIP skip on all three SCMs (reuses
    `spire.review.draft-prs`), `Retry-After` (+ GitLab `RateLimit-Reset`) classification, GitLab
    NEW-side `line_range` multi-line comments. Bitbucket inline stays single-anchor (API constraint).
    New SMOKE-TEST.md **Mode F** (GitLab webhook) + conversation/reconciliation steps. WireMock-tested
    per adapter.
    ✅ **Bitbucket live pass done (2026-07-25)** — the same script run on a real GitHub PR and a real
    Bitbucket PR with identical content, 11/11 on both (new SMOKE-TEST.md **Mode G — provider parity**,
    now the reusable regression script). The **compare-direction gate is settled live**: across four
    reconciliation rounds every verdict read the change in the correct direction. Ten defects the run
    exposed are fixed with tests — cross-provider resolution by the review's stored SCM type,
    conversation root-keying (V24 `review_thread.root_ref`: multi-turn, turn cap, thread attribution),
    **Bitbucket thread resolve** (so "reply-only" above no longer holds), GitHub's false-success
    `resolveThread`, re-posted-finding reconciliation, Bitbucket `@{account_id}` mentions, fenced code
    in follow-ups, and four UI conversation-display bugs. See CLAUDE.md for the itemized list.
    ✅ **GitLab live pass done (2026-07-26)** — Mode G run on a real GitLab MR alongside GitHub and
    Bitbucket, 11/11 behaviourally on all three. Every reconcile verdict except `ACKNOWLEDGED` was
    exercised (`SUPERSEDED` correctly never fired), 14 thread resolves across three different resolve
    mechanisms, a finding born mid-reconciliation closed two rounds later, and a 100%-similarity rename
    that did not churn finding identity. Three defects fixed — GitLab's compare diff parsing to zero
    files (reconciliation was inert on GitLab while *reading* correctly, so the notes looked right), the
    silent turn cap, and over-broad follow-up replies.
14. ✅ **Ticket-reference context providers: GitHub Issues + GitLab Issues** — **delivered
    2026-07-30** · M. Two new Apache-2.0 modules, `spire-context-github` and `spire-context-gitlab`,
    extend the proven ContextProvider SPI to issues, pull/merge requests and (GitLab) epics.
    Reference extraction is per-platform grammar (`GitHubIssueRefs`/`GitLabIssueRefs`): GitHub's bare
    `#123`, qualified `owner/repo#123`, and issue/PR URLs; GitLab's three sigils (`#123` issue, `!123`
    merge request, `&123` epic), its multi-segment qualified `group/subgroup/project#123`, and their
    URL forms. A bare reference is repository-relative, which only the new `ScmType` on
    `GatherContext`/`ContextRequest` makes safe: the same `workspace/slug` routinely exists on two
    platforms, so without knowing which platform the review actually runs on, a bare `#123` on a
    GitLab MR could silently resolve against a same-named GitHub repo. The gate is per-*reference*,
    not per-provider — a qualified reference or URL names its own repository and needs no platform
    match, only a bare one does. `ContextItem` gained three neutral kinds, `ISSUE` / `PULL_REQUEST` /
    `EPIC` (GitLab's own term "merge request" stays out of core's vocabulary). GitLab epics are a
    Premium feature; a free-tier instance's 403/404 skips just that reference, not the whole
    contribution. Both providers reuse the registry's generic `projectKeys` column as an owner/repo
    (GitHub) or group/project (GitLab) allow-list — no migration. Both reject `basic` auth **on
    save** (bearer-only, `BEARER_ONLY_TYPES`); Check hits `/user` (GitHub) and `/api/v4/user`
    (GitLab); Preview rejects a bare reference with actionable guidance instead of a silent empty
    result. Ahead of these two adapters, the pinned-redirect SSRF-guarded HTTP client Jira and
    Confluence each carried a copy of was extracted into a new Apache-2.0 module, `spire-http` — one
    of three Apache-2.0 modules this branch adds (with `spire-context-github`/`spire-context-gitlab`),
    bringing the total to thirteen per `LICENSING.md`. One home for the guard instead of four
    near-identical copies, so a future fix to it lands once. See CLAUDE.md for the test totals.
15. ✅ **Prompt management (operator-controlled prompts)** — **delivered 2026-07-21..23** · L. Settings →
    **Prompts** over DB-backed versioned templates (V23 `prompt_template`) seeded from the built-in
    `PromptCatalog` with reset-to-default; the prompt builders became slot/template-driven, and
    `PromptValidation` enforces the structural invariants (untrusted-data fencing, sentinel
    neutralization, token clipping, JSON output contract) so customization cannot break them. Editor is
    slot-aware rather than a raw textarea. **Scope shipped global**; per-repo prompts, preview against a
    sample diff, and a migration story for evolving built-in defaults remain open (see item 16).
16. **Prompt management follow-ups** · M. The three questions the global-scope shipment deferred:
    **per-repo prompt scope** (which needs a resolution order — repo overrides global — and a UI that
    makes the effective template obvious), **preview against a sample diff** so an operator can see what
    a template renders before it reaches a real PR, and a **migration story for evolving built-in
    defaults** (a customized template silently misses improvements to the shipped prompt; today's only
    answer is reset-to-default, which discards the customization wholesale).
17. **Conversation-derived findings** · M. A discussion that surfaces a real defect doesn't register one —
    the finding exists only as prose in a thread, so it never reaches the findings list, the reconciled
    open count, or the next round's exclusion set. Tracked in `techdebt/global/`.

**D. Infra & security hardening**
10. ✅ **OIDC on the dashboard** — **delivered 2026-08-03** as D10 / ADR-022 · M. Every REST and
    WebSocket endpoint now requires an operator identity; hybrid OIDC (cookie for the browser and its
    sockets, bearer for `curl`/CI), each service its own client under its own URL prefix, two roles,
    deny-by-default. See the D10 entry above and SMOKE-TEST.md **Mode J**. This line read "UI/API is
    unauthenticated" for three days after that shipped — the delivered list said one thing and the
    backlog the opposite, which is the failure mode a live view exists to prevent.
11. ✅ **`costMillicents` LLM pricing** (2026-07-07). LLM model catalog with operator-entered token
    pricing (`llm_model`); a review's real token usage was priced into `review_status.cost_millicents`
    and shown on the detail page + a Cost column in the reviews list. Model is now a dropdown from the
    catalog. See ADR-018. **Storage superseded by ADR-023 (2026-08-07):** V30 dropped
    `review_status.cost_millicents` (with `model`/`tokens_in`/`tokens_out`) and `review_llm_call`
    entirely; a review's cost is now derived from the `llm_charge` ledger. The operator-entered pricing
    this item delivered is unchanged and is still the reason there is no hardcoded price table — only
    where the resulting figure lives, and the fact that it can now be *absent* rather than `0`.
12. **MinIO / object-store BlobStore** · M. The `BlobStore` port itself is wired and in production use —
    `PostgresBlobStore` holds encrypted assembled context (`worker.context_blob`). What remains is an
    **object-store adapter** (MinIO/S3) plus the large-diff and future-artifact cases that outgrow a
    Postgres column.

### What is actually left

**Every numbered item in A, B and C is now closed, and all of D bar one** (1–11, 13, 14, 15 — item 10
closed with D10 on 2026-08-03). Only **D12**, **E16** and **E17** remain numbered. The product loop —
webhook → diff → context → review → conversation → reconciliation — is complete and live-verified on
GitHub, GitLab **and** Bitbucket, with operator-editable prompts and an attention panel over its health,
and reviews now understand a linked issue/PR/epic on every supported platform.

Open, by nature of the work rather than by section:

| # | Item | Effort | Why it's next / what gates it |
|---|---|---|---|
| **E16** | Prompt management follow-ups | M | Per-repo scope, preview against a sample diff, and a default-migration story. |
| **E17** | Conversation-derived findings | M | A discussion that surfaces a real defect leaves no finding behind. |
| **D12** | Object-store BlobStore adapter | M | Only bites when context or diffs outgrow a Postgres column. |
| **P3** | Whole-repo RAG | L | The stated differentiator, and the largest single item on this roadmap. Adds a `RagContextProvider` with **zero change to the review flow** — the SPI investment is what makes that true. |
| **P4** | Learned memory + per-author analytics | M–L | Wants a corpus of accepted/rejected findings to learn from, so it is naturally later. |
| — | Fleet cost/abuse caps | M | Explicitly deferred from v1 and a **known operator-facing gap**: no per-repo rate limit, no daily spend cap, no hard giant-PR skip. |

**Closed since this table was last written:** the **contract-compat CI gate** (round-trip + snapshot
tests on `spire-contract`, failing a breaking change without an `eventVersion` bump + upcaster,
ADR-013) shipped in `5bc593b` and had a vacuity hole closed on 2026-08-02 — it iterated event types and
skipped an empty list, so zero types read as zero failures.

Also open and tracked outside this file: **8 techdebt items** in `techdebt/` — 1 medium, 7 low,
nothing high or critical. The medium one is D10's: the authorization guard is copied into all three
services, and the drift check that was chosen instead of extracting a shared module has not been
written yet. (Another option, tracking waived nits durably so a set-aside issue cannot return as its
own finding, was considered and deliberately not built: it needs a store, a wire field and a prompt
slot, which makes it a feature rather than debt. It sits closest to **E17**.) **No P1 scope remains pending**: the
call-level resilience once framed as "SmallRye Fault Tolerance retry budgets" shipped as a hand-rolled
retry ladder + per-host circuit breaker (ADR-016 rejected per-call `@Retry` for the review budget, and
the same reasoning held one level down), and model pricing is delivered and deliberately
operator-entered (ADR-018) rather than a hardcoded cost table that would silently drift.

**Suggested order:** D10 and the CI/CD work it was gating are both delivered, so "someone else can run
this" is answered except for **TLS at the production edge**, which is the one remaining thing between the
current state and a deployment that is not a single trusted machine. After that, **E16** remains the
cheapest user-visible gain and **P3 (RAG)** is the one item that changes what the product *is* rather
than how well it runs. Operator decides.

---

## Phase 0 — Skeleton & event backbone (~2–3 pw) — SINGLE process (ADR-008 sequencing)
- Quarkus multi-module scaffold: `spire-contract` + one app wiring the modules in **one process** over
  the SmallRye **in-memory connector** (dev/test harness; services split at Phase 1).
- Event store (append-only Postgres) + dispatcher; replay + idempotent (`eventId`) dispatch.
- `ReviewLifecycle` decider, trivial happy path `PullRequestEventReceived → … → ReviewCompleted` with
  stub Diff/Llm/Comment plugins; unit-test the decider (pure functions).
- WebSockets dashboard showing the live event timeline.
- **Exit:** a fake PR event flows end-to-end and animates on the dashboard.

## Phase 1 — Real Bitbucket review, one bot (~3–4 pw) — split into services + Redpanda
- Split Phase-0 modules into `spire-gateway` / `spire-orchestrator` / `spire-review-worker` over
  **Redpanda** (Kafka connector); keep the in-memory connector for tests.
- `spire-diff`: own unified-diff parser (typed `FilePatch`/`Hunk`/`DiffLine`), dual line numbers, token
  clipping, prompt rendering and anchor resolution. PR-Agent was **read as prior art; no upstream code
  was used** — see `NOTICE` and RESEARCH.md §4 for the shipped-vs-upstream comparison.
- `spire-scm-bitbucket`: `ScmIngress` (webhook + HMAC, **drop bot-authored events**), `DiffSource`,
  `CommentSink` (inline + summary + thread-reply + PR-author). Bitbucket Cloud first, then DC.
- `spire-llm`: one `LlmProvider` via LangChain4j (config-selected, no default) + fallback saga.
- **Idempotent posting + stale-run pre-check** (ADR-013); ingress returns 202; fully async.
- **Exit:** open a PR in a real Bitbucket repo → the bot posts a real inline review as one account.

## Phase 2 — Context providers (~2–3 pw) — ✅ complete (2026-08-01)
- ✅ Context-provider pipeline + aggregator (completeness/timeout policy).
- ✅ `spire-context-jira` and `spire-context-confluence` plugins.
- ✅ **Repo rules provider (`.codespire` config) — delivered 2026-08-01.** A repository states its own
  conventions in a `.codespire` file, contributed as `ContextContributed{source=RULES}` /
  `ContextItem{kind=RULE}`. Read from the PR's **target branch**, never the reviewed commit: the head
  is written by the change under review, so rules taken from it would let a PR rewrite the reviewer's
  instructions in the same PR. Prompt fencing cannot cover that — rules are *meant* to steer the
  review. Fetched at diff-fetch via `DiffSource.fetchTextFileOnBranch` (all three adapters) and
  carried on `DiffFetched.repoRules`, so the context aggregator never needs an SCM credential.
- ✅ Conversational follow-up loop (S8).
- **Exit met (2026-07-18):** reviews cite the linked Jira ticket; author replies get in-thread answers.

## Phase 3 — Whole-repo RAG (the differentiator) (~4–6 pw)
- `RepositoryIndexDecider` + push-triggered incremental indexer.
- Code-aware chunking + embeddings + pluggable vector store (Qdrant/LanceDB).
- `RagContextProvider` contributing retrieved snippets — **added with zero change to the review flow**.
- **Exit:** reviews reference code elsewhere in the repo, not just the diff.

## Phase 4 — Memory & analytics (~2–3 pw)
- `MemoryView` (learned preferences from accepted/rejected findings) + `MemoryContextProvider`.
- `MetricsView` (per-author/per-repo) — using the author field present since S1.
- **Exit:** the bot adapts to team conventions over time; basic analytics dashboard.

## Cross-cutting (ongoing)
- Additional SCM adapters (GitHub, GitLab) — proves the port abstraction. ✅ GitHub + GitLab done.
- Additional LLM providers — ✅ Anthropic + Gemini (native) done; OpenAI-compatible covers Azure/Ollama.
  Vertex still open.
- ✅ **Contract-compat CI gate** (`5bc593b`) — round-trip + snapshot tests on `spire-contract` events;
  fails on a breaking change without an `eventVersion` bump + upcaster (ADR-013).
- ✅ **Packaging** — delivered 2026-08-05: four images on GHCR, a Helm chart, kustomize overlays and
  rendered `kubectl apply` manifests from one source of truth (drift-checked), plus the `.env.example`
  contract for both the dev and the packaged stack. See the CI/CD entry above and `deploy/`.
- Docs site — still open. ✅ Contribution guide done (`CONTRIBUTING.md`, DCO + relicensing grant, ADR-021);
  the licence split it documents makes the grant a hard requirement, not a nicety.

## Explicitly deferred (NOT in v1)
- **Fleet-level cost/abuse caps** — per-repo/workspace rate limit, daily LLM spend cap, giant-PR
  guard, bot-authored-PR skip. v1 has only per-review token budgeting. (Draft/WIP-PR skip
  is **no longer deferred at all** — shipped on all three SCMs by 2026-07-23, item 13.)
  Tracked as FR-later (PRD) + SECURITY.md; a known gap an operator must be aware of. NOTE: a giant PR
  is not silently mis-reviewed — the diff is clipped to the token budget and the partial review is now
  MARKED (dashboard note + a line on the posted summary comment). A hard giant-PR skip is still future.
  **Still deferred, but no longer blocked on trustworthy data**: ADR-023 (2026-08-07) replaced the
  accounting a spend cap would have had to read, which used to record `$0.00` for any call it could not
  price — a cap built on it would have installed cleanly and never fired for exactly the calls that
  needed it. The ledger (`llm_charge`) now distinguishes priced, unpriced-but-billable and genuinely-free
  calls, so this item is implementation work rather than a data-trust problem. Carry the one consequence
  forward when it is built: **a money-denominated cap is inert by design on an `UNMETERED` (self-hosted)
  deployment** — the operator has asserted zero cost, so there is nothing in dollars to cap, while every
  other abuse scenario (a hammered inference GPU, for instance) still applies. The cap needs a token- or
  call-count axis that holds regardless of pricing mode, not a money axis alone.
- Whole-repo RAG (P3), learned memory + per-author analytics (P4).
  (**"non-Bitbucket SCMs" is no longer deferred** — GitHub and GitLab both shipped and are live-verified
  to full parity with Bitbucket as of 2026-07-26.)

## Design-time decisions (historical — all long since executed)

Kept for provenance. **This is not a to-do list** — for what to do next, see
[What is actually left](#what-is-actually-left) at the top.

1. SCM target: **DECIDED = Bitbucket Cloud** (`api.bitbucket.org/2.0`, App Password, signed webhooks). See CONTRACT.md §10.
   (Since joined by GitHub and GitLab at full parity.)
2. Event store: **DECIDED = Postgres append-only** (ADR-007).
3. Domain formalism: **DECIDED = hand-rolled** Decider/View/Saga (ADR, minimal deps).
4. Domain contract: **DONE** — see CONTRACT.md (`spire-contract`).
5. Local dev: **DECIDED = docker-compose** (Redpanda + Postgres). ✅ **Keycloak now wired and
   verified live** — the base file stays IdP-free, and authentication is opted into per file:
   `docker-compose.idp.yml` runs a bundled Keycloak (realm auto-imported from
   `infra/keycloak/realm-spire.json`), `docker-compose.auth.yml` flips the containerized dev stack's
   three services on. Both supported IdP options were exercised end to end against the same realm:
   the bundled instance, and an externally-running one reached by pointing
   `SPIRE_OIDC_AUTH_SERVER_URL` at it. They need **opposite hostname strategies**, which is the one
   thing to remember — a pinned `KC_HOSTNAME` lets front- and backchannel differ while the issuer
   stays fixed; an unpinned instance derives its issuer from the Host it was called on, so browser
   and containers must use one name that resolves for both. Runbook: SMOKE-TEST **Mode J**.
6. ✅ **Phase 0 scaffolded and long superseded** — Gradle multi-module, `spire-contract`, and the
   gateway→orchestrator→worker path on Redpanda all shipped; there are now **16 Gradle modules** (13
   Apache-2.0 libraries and adapters plus the three services — `spire-ui` is the fourth deployable but
   not a Gradle module) and the orchestrator is on migration **V29**, with V2 on the gateway and V4 on
   the worker, each service owning its own schema.
