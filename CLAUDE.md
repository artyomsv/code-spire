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
| `docs/EVENT-MODEL.md` (+ `docs/diagrams/event-model.html`) | Slices S1–S10 in Event Modeling notation |
| `docs/CONTRACT.md` | Event/command catalog, `ReviewLifecycle` decide table, SPI ports, topics, Bitbucket mapping |
| `docs/DATA-MODEL.md` | Value types, event store, object store, read models, encryption boundaries |
| `docs/SCM-MAPPING.md` | Provider-neutral SCM model verified against Bitbucket/GitHub/GitLab/DC APIs |
| `docs/SECURITY.md` | Trust boundaries, OIDC/RBAC, Tink encryption, LLM threat model, cost gaps |
| `docs/DECISIONS.md` | ADR-001..014 — every locked decision with its why |
| `docs/RESEARCH.md` | Market landscape + the PR-Agent code evaluation that justified greenfield |
| `docs/ROADMAP.md` | Phases P0–P4 with exit criteria |

## Status (keep current)

- **Phase 0 delivered:** `spire-contract` (pure domain lib: events, commands, hand-rolled
  Decider/View/Saga, SPI ports, `ReviewLifecycle` decider + 13 GWT tests) and `spire-orchestrator`
  (single-process pipeline over SmallRye in-memory channels, Postgres event store with optimistic
  concurrency, stub workers with the ADR-013 stale-run pre-check, live WebSocket timeline dashboard).
  Exit criterion green: `PipelineSmokeTest` drives a synthetic PR to `ReviewCompleted`.
- **Next (P1):** split into `spire-gateway`/`spire-orchestrator`/`spire-review-worker` over Redpanda;
  port PR-Agent's diff/token algorithms + prompts into `spire-diff` (source reference:
  `qodo-ai/pr-agent`, Apache-2.0); real Bitbucket Cloud adapter (`ScmIngress`/`DiffSource`/
  `CommentSink`); one LangChain4j `LlmProvider`; Tink encryption of event payloads.

## Build & run

JDK 25 (SDKMAN `25.0.3-tem`) + Docker required.

```bash
cp .env.example .env                      # set POSTGRES_PASSWORD (dev-only)
docker compose up -d                      # Postgres on :34432
./gradlew build                           # unit tests + Testcontainers smoke test
./gradlew :spire-orchestrator:quarkusDev  # dashboard at http://localhost:34080
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
