# Roadmap

Phased plan. Estimates are rough person-weeks for one developer in private time; treat as relative
sizing, not commitments.

---

## Current status & next-up backlog (updated 2026-07-07)

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

### Next-up backlog — pick by number (S/M/L = rough effort; ⚑ = needs a decision/credential from the operator)

**A. Finish the multi-SCM story (current thread)**
1. ✅ GitHub **active mode** — post a real review comment (2026-07-07). Complete GitHub loop
   (diff → LLM → inline + summary) proven live against `artyomsv/spire-test` PR #2 via manual
   Register PR. See SMOKE-TEST.md Mode C.
2. **GitLab adapter (Phase C)** · L · ⚑ gitlab.com vs self-hosted host. Static-token auth (not HMAC),
   MR `iid`, 3 SHAs, discussion-thread replies, nested-group project paths (touches reviewId slug).
3. **Real webhooks (Phase D)** · M. `/webhooks/{provider}` dispatch in the gateway so PRs auto-register
   on open/update instead of manual "Register PR". Needs a public tunnel to test.

**B. Make the reviewer genuinely useful (P2 — currently diff-only)**
4. **`/review` command** · M. Author types `/review` in a PR comment to (re-)trigger. Parsed, inactive.
5. **Conversational replies** · M. Answer follow-ups in a thread (`AuthorReplied` received but ignored).
6. **ContextProviders (Jira/Confluence)** · L. Enrich reviews with linked-ticket context. Biggest lever.

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

**Suggested order for momentum:** C (7/8/9) and 1 (GitHub active) are done. Next: 3 (webhooks — auto-
register instead of manual Register PR) → 2 (GitLab). Operator decides.

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
- Additional SCM adapters (GitHub, GitLab) — proves the port abstraction.
- Additional LLM providers (Vertex, Anthropic, Azure, Ollama).
- **Contract-compat CI gate** — round-trip + snapshot tests on `spire-contract` events; fail on a
  breaking change without an `eventVersion` bump + upcaster (ADR-013).
- Packaging: container image, Helm chart / ArgoCD-friendly manifests, `.env.example` contract.
- Docs site + contribution guide once the plugin SPI stabilizes.

## Explicitly deferred (NOT in v1)
- **Fleet-level cost/abuse caps** — per-repo/workspace rate limit, daily LLM spend cap, giant-PR
  guard, draft/WIP-PR skip, bot-authored-PR skip. v1 has only per-review token budgeting (ported).
  Tracked as FR-later (PRD) + SECURITY.md; a known gap an operator must be aware of.
- Whole-repo RAG (P3), learned memory + per-author analytics (P4), non-Bitbucket SCMs.

## Immediate next steps
1. SCM target: **DECIDED = Bitbucket Cloud** (`api.bitbucket.org/2.0`, App Password, signed webhooks). See CONTRACT.md §10.
2. Event store: **DECIDED = Postgres append-only** (ADR-007).
3. Domain formalism: **DECIDED = hand-rolled** Decider/View/Saga (ADR, minimal deps).
4. Domain contract: **DONE** — see CONTRACT.md (`spire-contract`).
5. Local dev: **DECIDED = docker-compose** (Redpanda + Postgres + Keycloak).
6. **Next: scaffold Phase 0** — Gradle multi-module + `spire-contract` (events/commands/decider/ports)
   + thin gateway→orchestrator→worker path on Redpanda + the docker-compose dev env.
