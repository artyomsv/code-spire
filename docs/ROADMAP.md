# Roadmap

Phased plan. Estimates are rough person-weeks for one developer in private time; treat as relative
sizing, not commitments.

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
