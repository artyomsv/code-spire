# Code Spire

> Open-source, self-hosted, **event-driven**, **plugin-first** AI code reviewer for any Git platform — Bitbucket-first.

Code Spire is a single bot service that automatically reviews **every** pull request in a
workspace — regardless of author, with **no per-seat licensing** — and runs entirely inside
your own infrastructure. The LLM provider, the source-control platform, the context sources,
and the review capabilities are all **plugins** behind an event-driven core.

## Why it exists

The mature open-source PR reviewer (`qodo-ai/pr-agent`) is Python, single-shot, and has no
plugin system, no whole-repo context, and no learned memory. The polished tools (Greptile,
CodeRabbit, Qodo Merge) are closed and/or per-seat SaaS, and Greptile does not support
Bitbucket at all. Code Spire fills the gap: **a plugin-first, self-hosted, whole-repo-aware
reviewer you can extend without touching the core.**

## Design pillars

1. **Event-driven, no synchronous processing.** Every step is a message. The core is modeled
   with [Event Modeling](https://eventmodeling.org/) and implemented as event choreography.
2. **Plugin-first.** A new capability (a context provider, an SCM adapter, an LLM provider, a
   whole new review flow) is a component that subscribes to and emits events. Zero core edits.
3. **Self-hosted, provider-agnostic.** No hard-coded LLM or SCM. Chosen at configuration time.
   Code and inference can stay entirely in your tenant.
4. **One bot, all PRs.** Workspace-level webhook + one service identity. Not per-user.

## Stack

- **Quarkus** (Java) — reactive core.
- **SmallRye Reactive Messaging** (Mutiny) — the event bus over the **Kafka protocol** (Redpanda/Kafka from v1; in-memory connector for dev/test).
- **Quarkus WebSockets Next** — live read-model / progress / token-stream push to UIs.
- **LangChain4j** — LLM provider adapters.
- Event-sourced deciders/views/sagas in the style of [Fraktalio fmodel](https://modeler.fraktalio.com/).

## Status

**Phase 0 — skeleton & event backbone.** `spire-contract` (the domain: events, commands,
Decider/View/Saga, SPI ports, the `ReviewLifecycle` decider + tests) and `spire-orchestrator`
(single-process pipeline over in-memory channels with stub workers, Postgres event store, live
WebSocket timeline dashboard). Services split over Redpanda at Phase 1 (ADR-008).

## Development

Requirements: JDK 25 (e.g. SDKMAN `25.0.3-tem`), Docker.

```bash
cp .env.example .env          # fill in POSTGRES_PASSWORD (dev-only value)
docker compose up -d          # Postgres on localhost:34432
./gradlew build               # compiles + decider unit tests + pipeline smoke test (Testcontainers)
./gradlew :spire-orchestrator:quarkusDev
```

Open http://localhost:34080 — press **Simulate PR** and watch the review pipeline flow across the
live event timeline (integration -> command -> domain -> result) to `ReviewCompleted`.

## Docs

| Doc | What |
|---|---|
| [docs/PRD.md](docs/PRD.md) | Product requirements: problem, users, goals, FR/NFR, scope, success criteria |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | The event-driven plugin-first core (start here) |
| [docs/TECH-STACK.md](docs/TECH-STACK.md) | Full stack, topology (microservices), build units, UI |
| [docs/EVENT-MODEL.md](docs/EVENT-MODEL.md) | The event model: slices, events, commands, read models |
| [docs/diagrams/event-model.html](docs/diagrams/event-model.html) | Visual Event Modeling board (open in a browser) |
| [docs/CONTRACT.md](docs/CONTRACT.md) | The shared kernel: event/command catalog, decider, SPI ports, topics, Bitbucket mapping |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md) | Value types, event store, blob store, read models, relationships, encryption |
| [docs/SCM-MAPPING.md](docs/SCM-MAPPING.md) | Provider-neutral SCM model mapped to Bitbucket/GitHub/GitLab APIs (verified) |
| [docs/SECURITY.md](docs/SECURITY.md) | Trust boundaries, OIDC/RBAC, Tink encryption, secrets |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Why build (not buy), why greenfield (not fork), why event-driven |
| [docs/RESEARCH.md](docs/RESEARCH.md) | Market alternatives + the PR-Agent code evaluation |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phased plan v1 → v2 (RAG) → v3 (memory) |

## License

[Apache-2.0](LICENSE).
