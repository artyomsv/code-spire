# Roadmap

Phased plan. Estimates are rough person-weeks for one developer in private time; treat as relative
sizing, not commitments.

---

## Current status & next-up backlog (updated 2026-07-09)

This is the **live view** — what is actually built and what to pick next. The Phase 0–4 plan further
down is the original design-time roadmap (kept for reference).

### Delivered
- **P0 + P1**: event backbone, 3 services over Redpanda, real Bitbucket adapter set, event store,
  idempotent posting + stale-run guard, live operator UI (`spire-ui`).
- **Encryption at rest** (ADR-009): `EncryptionService`/Tink in the shared `spire-encryption` module.
- **Provider registry** (Settings → Providers): encrypted credentials in the DB, no `.env` tokens.
- **ADR-015**: active-mode worker gets per-command SCM credentials brokered (encrypted) over the bus.
- **GitHub adapter** (`spire-scm-github`): registry is type-aware end-to-end; GitHub PRs register and
  observe live (verified against `github.com/artyomsv/spire-test`). Bitbucket API access is still
  blocked at work, so GitHub/GitLab are being built first to prove the flow.
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

### Next-up backlog — pick by number (S/M/L = rough effort; ⚑ = needs a decision/credential from the operator)

**A. Finish the multi-SCM story (current thread)**
1. ✅ GitHub **active mode** — post a real review comment (2026-07-07). Complete GitHub loop
   (diff → LLM → inline + summary) proven live against `artyomsv/spire-test` PR #2 via manual
   Register PR. See SMOKE-TEST.md Mode C.
2. ✅ **GitLab adapter (Phase C)** — **verified live 2026-07-08.** baseUrl-driven (gitlab.com +
   self-managed), MR `iid`, 3 SHAs, discussion-thread replies, nested-group project paths (slug parsers
   widened). Read+write over manual Register PR; WireMock + register tests green; live diff → LLM →
   inline + summary confirmed. Webhook ingress deliberately omitted — see item 3. See SMOKE-TEST.md Mode D.
3. **Real webhooks (Phase D)** · M · **GitHub done (2026-07-09); GitLab + Bitbucket pending.** A single
   registry-backed edge `POST /webhooks/github/{key}` auto-registers PRs on open/update/reopen (and
   `closed` → cancel; `/review` issue-comments → force). Per-repository registrations live in a new
   `webhook_repo` table (Settings → **Webhooks**): an unguessable routing `key` in the URL + a
   Tink-encrypted HMAC secret under a **dedicated webhook keyset** (`SPIRE_ENCRYPTION_WEBHOOK_KEYSET`),
   so the gateway verifies inbound signatures without ever holding the master keyset that unlocks API
   tokens. `GitHubIngress` (X-Hub-Signature-256, constant-time) translates to the same
   `PullRequestEventReceived` the manual path emits, so the whole downstream saga (incl. the decider's
   same-commit idempotency for re-deliveries) is unchanged. Live via **Tailscale Funnel** — see
   SMOKE-TEST.md **Mode E**. Next: fold GitLab (`X-Gitlab-Token`) and Bitbucket (`X-Hub-Signature`)
   onto the same per-repo model.

**B. Make the reviewer genuinely useful (P2 — currently diff-only)**
4. **`/review` command** · M. Author types `/review` in a PR comment to (re-)trigger. Parsed, inactive.
5. **Conversational replies** · M. Answer follow-ups in a thread (`AuthorReplied` received but ignored).
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

**D. Infra & security hardening**
10. **OIDC on the dashboard** · M. UI/API is unauthenticated — matters before any shared deployment.
11. ✅ **`costMillicents` LLM pricing** (2026-07-07). LLM model catalog with operator-entered token
    pricing (`llm_model`); a review's real token usage is priced into `review_status.cost_millicents`
    and shown on the detail page + a Cost column in the reviews list. Model is now a dropdown from the
    catalog. See ADR-018.
12. **MinIO / BlobStore** · M. Wire the storage port (large-diff handling, future artifacts).

**Suggested order for momentum:** C (7/8/9), 1 (GitHub active), and 2 (GitLab adapter) are all done and
verified live; native Anthropic + Gemini LLM providers landed 2026-07-08. **Webhooks (3) — GitHub done
2026-07-09** (per-repo key + dedicated webhook keyset + Tailscale Funnel runbook); GitLab + Bitbucket
ingress fold onto the same model next. With a real webhook edge, **B4** (`/review` command) is now
partially live (issue-comment `/command` → force review); **B5** (conversational replies) still parked.
**B6 Jira landed 2026-07-08** and **B6 Confluence landed 2026-07-09** — the ContextProvider SPI is proven
generic across two sources. Other levers with no infra blocker: **D10** (OIDC, before any shared deploy),
**D12** (MinIO/BlobStore), or a third ContextProvider (repo rules / RAG). Operator decides.

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
- `spire-diff`: port `git_patch_processing` + `pr_processing` + token handling + YAML-repair; prompts → Qute.
- `spire-scm-bitbucket`: `ScmIngress` (webhook + HMAC, **drop bot-authored events**), `DiffSource`,
  `CommentSink` (inline + summary + thread-reply + PR-author). Bitbucket Cloud first, then DC.
- `spire-llm`: one `LlmProvider` via LangChain4j (config-selected, no default) + fallback saga.
- **Idempotent posting + stale-run pre-check** (ADR-013); ingress returns 202; fully async.
- **Exit:** open a PR in a real Bitbucket repo → the bot posts a real inline review as one account.

## Phase 2 — Context providers (~2–3 pw)
- Context-provider pipeline + aggregator (completeness/timeout policy).
- `spire-context-jira` and `spire-context-confluence` plugins.
- Repo rules provider (`.codespire` config).
- Conversational follow-up loop (S8).
- **Exit:** reviews cite the linked Jira ticket; author replies get in-thread answers.

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
- **Contract-compat CI gate** — round-trip + snapshot tests on `spire-contract` events; fail on a
  breaking change without an `eventVersion` bump + upcaster (ADR-013).
- Packaging: container image, Helm chart / ArgoCD-friendly manifests, `.env.example` contract.
- Docs site + contribution guide once the plugin SPI stabilizes.

## Explicitly deferred (NOT in v1)
- **Fleet-level cost/abuse caps** — per-repo/workspace rate limit, daily LLM spend cap, giant-PR
  guard, draft/WIP-PR skip, bot-authored-PR skip. v1 has only per-review token budgeting (ported).
  Tracked as FR-later (PRD) + SECURITY.md; a known gap an operator must be aware of. NOTE: a giant PR
  is not silently mis-reviewed — the diff is clipped to the token budget and the partial review is now
  MARKED (dashboard note + a line on the posted summary comment). A hard giant-PR skip is still future.
- Whole-repo RAG (P3), learned memory + per-author analytics (P4), non-Bitbucket SCMs.

## Immediate next steps
1. SCM target: **DECIDED = Bitbucket Cloud** (`api.bitbucket.org/2.0`, App Password, signed webhooks). See CONTRACT.md §10.
2. Event store: **DECIDED = Postgres append-only** (ADR-007).
3. Domain formalism: **DECIDED = hand-rolled** Decider/View/Saga (ADR, minimal deps).
4. Domain contract: **DONE** — see CONTRACT.md (`spire-contract`).
5. Local dev: **DECIDED = docker-compose** (Redpanda + Postgres + Keycloak).
6. **Next: scaffold Phase 0** — Gradle multi-module + `spire-contract` (events/commands/decider/ports)
   + thin gateway→orchestrator→worker path on Redpanda + the docker-compose dev env.
