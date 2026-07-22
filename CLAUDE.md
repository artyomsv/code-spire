# Code Spire — project context

Self-hosted, event-driven, plugin-first AI code reviewer. One bot account reviews every PR in a
workspace via webhooks (no per-seat licensing); SCM platform, LLM provider, context sources, and
storage are pluggable. Bitbucket Cloud first. Open source (Apache-2.0).

## Read first

The design is fully specified in `docs/` — **treat those files as the source of truth**:

| Doc | Contents |
|---|---|
| `docs/PRD.md` | Problem, users, goals, FR-1..13 / NFR-1..9, scope, success criteria |
| `docs/ARCHITECTURE.md` | Event-driven plugin-first core; module layout; build sequencing |
| `docs/EVENT-MODEL.md` (+ `docs/diagrams/event-model.html`) | Slices S1–S11 in Event Modeling notation |
| `docs/CONTRACT.md` | Event/command catalog, `ReviewLifecycle` decide table, SPI ports, topics, Bitbucket mapping |
| `docs/DATA-MODEL.md` | Value types, event store, object store, read models, encryption boundaries |
| `docs/SCM-MAPPING.md` | Provider-neutral SCM model verified against Bitbucket/GitHub/GitLab/DC APIs |
| `docs/SECURITY.md` | Trust boundaries, OIDC/RBAC, Tink encryption, LLM threat model, cost gaps |
| `docs/DECISIONS.md` | ADR-001..019 — every locked decision with its why |
| `docs/RESEARCH.md` | Market landscape + the PR-Agent code evaluation that justified greenfield |
| `docs/ROADMAP.md` | Phases P0–P4 with exit criteria |

## Status (keep current)

- **Phase 0 delivered:** `spire-contract` (pure domain lib: events, commands, hand-rolled
  Decider/View/Saga, SPI ports, `ReviewLifecycle` decider + GWT tests) and `spire-orchestrator`
  (single-process pipeline over SmallRye in-memory channels, Postgres event store with optimistic
  concurrency, live WebSocket timeline dashboard).
- **Phase 1 feature delivered (single-process):** `spire-diff` (unified-diff parser with dual line
  numbers, token clip, prompt renderer, anchor resolver — ported semantics from qodo-ai/pr-agent),
  `spire-scm-bitbucket` (real Bitbucket Cloud `ScmIngress` with HMAC verify + bot-drop + /command
  parse, `DiffSource`, `CommentSink` per SCM-MAPPING), `spire-llm` (LangChain4j OpenAI-compatible
  `LlmProvider`, injection-fenced review prompt, lenient findings parser), orchestrator wiring
  (`/webhooks/bitbucket` returning 202, real `ReviewWorker` with comment_idempotency
  insert-before-post + stale-run pre-check, dev/test stub toggles `spire.scm.stub` /
  `spire.llm.provider` — real SCMs/LLMs are the UI registry, not config). Exit criterion green:
  `BitbucketWebhookE2ETest` — signed webhook → real adapters (WireMock Bitbucket) → inline+summary
  posted exactly once, duplicate delivery posts nothing.
- **Phase 1 code-reviewed:** 4-agent review (security-officer, code-reviewer, rules-compliance, qa);
  all 15 findings fixed — recovery-aware comment idempotency (reclaimable NULL claims + id reuse),
  per-finding post isolation + retryable classification, PR-head re-check before LLM/post,
  prompt-fence sentinel neutralization, host-pinned redirects, HTML-escaped model output, unordered
  dispatcher. Semgrep clean. **86 tests green** across 17 suites (incl. webhook E2E + idempotency
  integration). Two LOW items tracked in `techdebt/`.
- **P1 service split delivered:** three deployables over the Kafka protocol — `spire-gateway`
  (webhook -> cs.integration, :34081), `spire-orchestrator` (deciders/sagas/event store/dashboard,
  cs.commands + cs.events, :34080), `spire-review-worker` (cs.commands -> adapters -> cs.results,
  own `worker` schema for comment_idempotency, :34082). Wire format = polymorphic JSON
  (type discriminator on the sealed hierarchies); everything keyed by reviewId. The ADR-013
  stale-run guard lives in the orchestrator's ResultSaga (it owns the aggregate); the worker keeps
  the PR-head re-check. Split tests per service run against Testcontainers Kafka + Postgres
  (gateway webhook->topic, orchestrator choreography incl. stale-drop, worker command->result incl.
  idempotent redelivery). Redpanda in docker-compose at :34092.
- **Split code-reviewed (4 agents) and hardened:** poison records never kill consumers
  (never-throw deserializers) and processing failures go to **cs.dlq** (never silently dropped,
  ADR-013); repo/prId derive from the reviewId itself (`ReviewIds.parse` — no in-memory registry,
  nothing lost on restart); ordered per-partition dispatch (no same-review races); the paid LLM
  call has its own idempotency claim (no duplicate spend on redelivery); gateway awaits broker acks
  before the 202 (Bitbucket retries on failure) and holds only the webhook secret + bot account id
  (never the App Password); work queues use `latest` offsets (no side-effect replay for new groups);
  per-service HTTP port vars. Semgrep clean.
- **Encryption at rest delivered:** event-store payloads and provider secrets are Tink
  AES-256-GCM encrypted with AAD binding (stream id / provider id / workspace); the base64
  Tink keyset (`SPIRE_ENCRYPTION_KEYSET`) is the single fail-fast bootstrap secret. Legacy
  plaintext rows (`key_id='none'`) still read back.
- **Provider registry + multi-SCM delivered:** encrypted provider registry with Settings ->
  Providers CRUD (`ProviderResource`; secrets never returned — `hasSecret` only), bot identity
  auto-resolved from the token on save (`IdentitySource` / `ProviderIdentityResolver`),
  `spire-scm-github` adapter (client, diff source, comment sink), review modes
  `SPIRE_REVIEW_MODE=active|observe`, bounded auto-retry per review
  (`SPIRE_REVIEW_MAX_ATTEMPTS`, ADR-016), per-provider PR-author allowlist in the DB.
- **`spire-ui` delivered:** React/Vite dashboard (reviews list/detail with live WebSocket
  updates, Register PR dialog, provider settings) against the orchestrator's REST + WS APIs;
  vitest + `tsc --noEmit` in CI-shape, dev server on `:34000` (`UI_PORT`).
- **Full-project review hardened (2026-07):** 4-agent audit, all findings fixed — SSRF guard
  on provider base URLs (https + public host enforced; `spire.security.allow-insecure-provider-urls`
  relaxes only in `%dev`/`%test`), provider-neutral `ScmApiException` so GitHub 404/5xx/429
  classify like Bitbucket, outbound Kafka publishes await broker acks (failures DLQ or 5xx —
  never silent), `events-in` DLQ'd instead of ignored, LLM idempotency marks-before-emit with
  persisted-result re-emit on redelivery (no duplicate spend, no stall), per-call LLM
  `maxTokens` cap, `review_event.seq` race closed (V6 unique constraint + atomic insert),
  redirect hardening in SCM clients (GET-only, private-IP guard, port-normalized auth pinning),
  structured JSON logging + reviewId MDC (prod profile), UI URL-scheme guard + fetch-race fixes,
  npm audit 0 vulnerabilities.
- **First ContextProvider — Jira delivered (B6, 2026-07-08):** the P1 context stub is now the real
  aggregator. `spire-context-jira` (framework-free, JDK HttpClient + Jackson, SSRF-guarded like the SCM
  adapters) resolves PR-referenced issue keys (`PROJ-123`, parsed from title/branch at diff-fetch) into
  `ContextItem`s via the Jira v2 REST API (baseUrl-driven Cloud + Data Center, basic/bearer). `ContextWorker`
  fans out to the supported providers under a bounded 20s timeout and persists the assembled context
  encrypted (Tink, AAD=reviewId) to a Postgres `BlobStore` (`worker.context_blob`); `ReviewWorker` loads
  it into the untrusted-fenced prompt slot. Credentials live in a new encrypted context-provider registry
  (Settings → Context, `/api/context-providers`, global default) brokered per-command like SCM/LLM (ADR-015).
  Blob deletion is keyed by `review_id` at all three sites (delete, re-run, re-assembly) — no orphans.
  Per-instance **project keys** (`ACME`) narrow candidate keys; a live **connectivity check**
  (`/{id}/check`) and a **preview/test** endpoint (`/{id}/preview` — resolve a ticket number via the
  pattern and show the exact `ContextItem` a review would inject) back the Settings → Context UI.
- **Unified keyed webhook ingress (2026-07-16):** all three SCMs now share the gateway's
  per-repo registry edge `/webhooks/{provider}/{key}` (key resolves the encrypted per-repo
  secret + scope from `webhook_repo`). `GitLabIngress` added (`X-Gitlab-Token` constant-time
  compare — GitLab does not sign the body — + Merge Request / Note translation; `update`⇒UPDATED
  only when `oldrev` is present) with `GitLabWebhookResource`; the shared `RegistryWebhookEdge`
  (resolve→verify→translate→scope→publish) backs the GitHub, GitLab and Bitbucket resources. The
  **legacy single-secret `/webhooks/bitbucket` edge was removed** (`WebhookResource` +
  `GatewayScmProducer` + `SPIRE_SCM_BITBUCKET_WEBHOOK_SECRET`) — Bitbucket now registers like the
  others (`bitbucket-cloud` provider). Dev webhook exposure: opt-in Cloudflare quick-tunnel
  service (`--profile tunnel`) forwarding to `gateway:39281`.
- **Re-review reconciliation delivered (ADR-019, 2026-07-18):** a follow-up commit now reconciles
  instead of blindly re-reviewing — the orchestrator command-carries the last posted run's snapshot
  (`PriorRun`, from `review_status.posted_findings_json`, commit-guarded) into `GenerateReview`; the
  worker runs a claim-guarded reconcile call (prior findings + threads + incremental diff via
  `DiffSource.fetchCompareDiff`, full-diff fallback on force-push) for per-finding verdicts, then the
  review call with an exclusion list. `PostComments` resolves-then-replies closing verdicts (Bitbucket
  degrades to reply-only), always replies `STILL_OPEN`, updates the summary in place. V19; UI card.
- **GitHub integration finalized (2026-07-21..22):** a live-use audit's 12 findings are closed —
  403/GraphQL `RATE_LIMITED` now classify as retryable (was 429-only), backed by a throttled,
  Retry-After-aware inline-posting backoff; `/review` PR comments force a re-run; draft PRs skip
  until `ready_for_review` (`SPIRE_REVIEW_DRAFT_PRS` restores always-review); OLD-side/multi-line
  anchors and honest 406/pagination failures; plain PR comments now get conversational answers in
  the summary thread. GitLab/Bitbucket parity tracked in ROADMAP.md item 13.
- **PR-12 review-fix batch (2026-07-22..23):** reviews-list rows now show reconciled open-finding
  counts and cumulative LLM cost instead of overwritten last-run columns; `STILL_OPEN` downgrades
  to `UNCHANGED` at hunk (not file) granularity; the Findings card shows an in-progress state
  instead of a false clean; a transient `answering` flag (V21) drives a responding indicator.
- **PR-state badge (2026-07-23):** a distinct `pr_state` (OPEN/MERGED/CLOSED, V22) on the review
  read-model, set from the open/close webhook events across all three SCMs, shown as its own
  badge separate from the review status; cancel-on-close is unchanged.
- **Still pending from P1 scope:** SmallRye Fault Tolerance call-level retry budgets (tracked
  in `techdebt/global/`); cost table for `ModelUsage.costMillicents`.

## Build & run

JDK 25 (SDKMAN `25.0.3-tem`) + Docker required.

```bash
cp .env.example .env                      # set POSTGRES_PASSWORD (dev-only)
docker compose up -d                      # Postgres :34432 + Redpanda :34092
./gradlew build                           # unit + per-service split tests (Testcontainers: Kafka + Postgres)
./gradlew :spire-orchestrator:quarkusDev  # dashboard at http://localhost:34080
./gradlew :spire-gateway:quarkusDev       # webhook edge :34081
./gradlew :spire-review-worker:quarkusDev # worker :34082
cd spire-ui && npm install && npm run dev # React dashboard :34000 (UI_PORT)
```

## Conventions (enforced by design docs — do not regress)

- **Everything between components is an async event/command** — the only sync edge is webhook
  ingress returning 202. New capabilities subscribe to events; the core is never edited for them.
- **Domain events are appended ONLY by the aggregate** (single writer, ADR-010); workers emit
  integration events; sagas translate. All messages keyed by `reviewId`.
- **No hard-coded LLM/SCM provider** — config-selected, fail-fast when unset. No defaults for
  credentials anywhere; `.env.example` is the contract.
- **Diffs are never persisted** (ADR-011) — re-fetched by commit. Findings ride inline in events.
- **Sensitive fields (findings/context — may quote source) are Tink-encrypted at rest** in
  app-managed stores; the Kafka bus is covered by short retention + broker disk encryption
  (ADR-014), not app-layer crypto.
- **Money in millicents.** Host-exposed dev ports in the **34xxx** range.
- **Author identity** is data (stable `providerUserId`), never a gate; `email` never logged/persisted.
- Java 25 / Quarkus 3.36 / Gradle Kotlin DSL; pure domain code stays free of framework imports.
