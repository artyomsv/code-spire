# Code Spire

> Source-available, self-hosted, **event-driven**, **plugin-first** AI code reviewer for any Git platform — Bitbucket-first.

> [!NOTE]
> **"Code Spire" is a working name, not the final product name.** It is what the project is called
> while it is built; the shipped product may be named something else. Nothing here depends on it —
> the licence grants come from the copyright holder, not from the project name — so a rename changes
> branding, not terms. Don't treat the name as a commitment or build assets around it yet.

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

**Phase 1 — the service split is live.** Three deployables over the Kafka protocol (Redpanda):

| Service | Port | Role |
|---|---|---|
| `spire-gateway` | 34081 | webhook verify -> translate -> `cs.integration`, returns 202 |
| `spire-orchestrator` | 34080 | `ReviewLifecycle` decider + sagas, owns the event store, emits `cs.commands`, serves the live dashboard |
| `spire-review-worker` | 34082 | consumes `cs.commands`: diff fetch, LLM review, idempotent comment posting -> `cs.results` |
| `spire-run-worker` | 34083 | consumes `cs.run-commands`: builds a three-container run unit on Docker, drives the agent, publishes the result |
| `spire-ui` | 34000 | React operator UI: live reviews list + per-PR detail (reads `/api/reviews`, `/ws/reviews`) |

Shared libraries: `spire-contract` (domain + wire format), `spire-diff`, `spire-scm-*`,
`spire-llm`, `spire-http`; and for the factory `spire-runtime` (+ its Docker arm),
`spire-harness` (+ the Codex arm), `spire-workspace`, `spire-secrets` and `spire-agent-image`.

## Development

Requirements: JDK 25 (e.g. SDKMAN `25.0.3-tem`), Docker.

```bash
cp .env.example .env          # fill in POSTGRES_PASSWORD (dev-only value)
docker compose up -d          # Postgres :34432 + Redpanda :34092
./gradlew build               # unit + per-service split tests (Testcontainers: Kafka + Postgres)

# backend services (three terminals):
./gradlew :spire-orchestrator:quarkusDev
./gradlew :spire-gateway:quarkusDev
./gradlew :spire-review-worker:quarkusDev

# operator UI (fourth terminal):
cd spire-ui && npm install && npm run dev   # http://localhost:34000
```

Open **http://localhost:34000** for the operator UI — a live reviews list; click any review for
its pipeline, findings, model usage, and event stream. (The orchestrator on `:34080` also serves a
raw event-timeline dashboard and the `/api` + `/ws` endpoints the UI proxies to.)

Register an SCM provider in the UI (Settings -> Providers) and an LLM in Settings -> LLM — both
encrypted at rest. For GitHub/GitLab, add a per-repo webhook in Settings -> Webhooks and point the
repo's webhook at `https://<gateway>/webhooks/{provider}/{key}`; the bot then reviews real PRs. See
[docs/SMOKE-TEST.md](docs/SMOKE-TEST.md) for the safe observe-only first-contact flow.

### Operator authentication

**Dev runs with authentication off**, so everything above works as written. Any other run requires an
operator identity, and a service refuses to start with it disabled outside dev/test (ADR-022).

To exercise it locally, start an identity provider — the bundled one, or point
`SPIRE_OIDC_AUTH_SERVER_URL` at a Keycloak you already run — and import
`infra/keycloak/realm-spire.json`:

```bash
docker compose -f docker-compose.yml -f docker-compose.idp.yml up -d keycloak   # :34567
```

The realm ships two synthetic operators. **Development fixtures only** — they are committed to this
repository in plain text, so they must never exist anywhere reachable from outside a workstation.

| User | Password | Roles | Can do |
|---|---|---|---|
| `dev-operator` | `dev-operator` | `spire-admin` + `spire-viewer` | everything — register a PR, re-run, delete, replay the DLQ, all settings |
| `dev-viewer` | `dev-viewer` | `spire-viewer` | read reviews only — no Configure section at all, no Register PR, no re-run or delete |

Turning it on for a running stack, what a viewer may and may not reach, and the reason the two IdP
options need different URLs, are all in [docs/SMOKE-TEST.md](docs/SMOKE-TEST.md) **Mode J**.

## Deployment

> **Do not expose a deployment without TLS.** Operator sessions are cookies, and in plaintext they are
> sniffable and **replayable** — authentication stops casual access, not an on-path attacker. Bind the
> dashboard to `localhost`, or put a TLS terminator in front of it.

```bash
cp deploy/.env.example deploy/.env      # every value is required; none has a default
docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env up -d
```

Dashboard on `http://localhost:34700`. Images are published to
`ghcr.io/artyomsv/spire-{gateway,orchestrator,review-worker,ui}` — `:edge` tracks `master`.

Two presets, for Compose and for Kubernetes alike: **`simple`** bundles Postgres, Redpanda and Keycloak
for self-hosting or evaluation; **`production`** expects all three externally. The Helm chart is the
single source of truth — kustomize inflates it and the plain YAML in `deploy/k8s/` is rendered from it,
with a drift check in CI so the three cannot diverge.

One thing to know before changing anything: **the dashboard image is also the reverse proxy**, routing
`/webhooks`, `/api`, `/gw` and `/wk` to the services. That is what puts all four on one origin, which is
what makes the per-service session cookies isolate (ADR-022) — so it must stay at the origin root, and
`/webhooks` must keep reaching the gateway or no review ever starts.

Full guide, including the realm contract for bringing your own identity provider and why nothing here
generates an encryption keyset: [deploy/README.md](deploy/README.md).

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
| [docs/SMOKE-TEST.md](docs/SMOKE-TEST.md) | Runbook: local stub demo + real Bitbucket PR smoke test |
| [docs/SECURITY.md](docs/SECURITY.md) | Trust boundaries, OIDC/RBAC, Tink encryption, secrets |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Why build (not buy), why greenfield (not fork), why event-driven |
| [docs/RESEARCH.md](docs/RESEARCH.md) | Market alternatives + the PR-Agent code evaluation |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Phased plan v1 → v2 (RAG) → v3 (memory) |

## License

Code Spire is **source-available**, split by module — full map and reasoning in
[LICENSING.md](LICENSING.md):

| | License | |
|---|---|---|
| **Libraries + plugin SPI** — `spire-contract`, `spire-diff`, `spire-encryption`, `spire-http`, `spire-scm-*`, `spire-context-*`, `spire-llm`, `spire-arch`, and the factory's `spire-runtime`, `spire-runtime-docker`, `spire-harness`, `spire-harness-codex`, `spire-workspace`, `spire-secrets`, `spire-agent-image` | [Apache-2.0](licenses/Apache-2.0.txt) | Write plugins against these under any license you like, including a proprietary one. |
| **Services** — `spire-gateway`, `spire-orchestrator`, `spire-review-worker`, `spire-run-worker`, `spire-publisher`, `spire-ui` | [FSL-1.1-ALv2](LICENSE) | Self-hosting, internal commercial use, forking, teaching and consulting are all permitted. Reselling it as a competing product or hosted service is not. Each version becomes Apache-2.0 two years after release. |

`LICENSING.md` is the authoritative per-module map; this table is a summary of it. A licence
summary that omits a module reads as permissive by omission, which is why both rows name every one.

Versions published before this change remain Apache-2.0; that grant is
irrevocable. Third-party attributions are in [NOTICE](NOTICE). Contributions:
[CONTRIBUTING.md](CONTRIBUTING.md).
