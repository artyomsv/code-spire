# Tech Stack & Service Decomposition — DECIDED

> Companion to [ARCHITECTURE.md](ARCHITECTURE.md) and [DECISIONS.md](DECISIONS.md).
> This is the locked stack. Only one soft choice remains (UI framework, §5).

## 1. Deployment topology — microservices (ADR-008, revised)

Multiple independently-deployable Quarkus services communicating over a **Kafka** event/command
backbone. Not a monolith. One public repo (`code-spire`), Gradle multi-module, building multiple
container images.

| Service | Responsibility | Scales on |
|---|---|---|
| `spire-gateway` | Webhook ingress (HMAC verify) + OIDC edge for UI/API + publishes inbound events to Kafka | request volume |
| `spire-orchestrator` | Domain brain: `ReviewLifecycle` decider + sagas + **owns the event store**; drives the pipeline | aggregate throughput |
| `spire-review-worker` | Consumes `GenerateReview` → LLM (LangChain4j) → `ReviewGenerated` | LLM/CPU load |
| `spire-context-worker` | Consumes `ContextRequested` → Jira/Confluence/rules providers → `ContextContributed` | context fan-out |
| `spire-indexer` *(P3)* | Repo vector index; RAG `ContextProvider`; consumes `PushReceived` | embeddings/batch |
| `spire-ui` | Operator dashboard BFF; subscribes to views; pushes over WebSockets; behind OIDC | operators |
| `spire-contract` *(lib)* | Shared events/commands schema + hand-rolled `Decider`/`View`/`Saga` interfaces + all SPI ports | — |

**Event backbone from v1:** cross-service choreography requires a durable bus, so **Kafka
(or Redpanda) is a v1 dependency**, not deferred. The SmallRye in-memory connector is retained only
for local dev and unit tests. Per-service durability via transactional-outbox (append event in one tx
→ dispatcher publishes to Kafka → idempotent consumers keyed by event id, at-least-once).

**Event store ownership:** `spire-orchestrator` owns the `ReviewLifecycle` aggregate + its append-only
Postgres event log (source of truth); it publishes events to Kafka. Workers are stateless
consumers/producers. Read models are built by projectors consuming Kafka.

## 2. Core stack

| Layer | Choice | Extension / lib | Notes |
|---|---|---|---|
| Language / runtime | **Java 25** (LTS) | — | Records, sealed types, pattern matching for events/commands/deciders. |
| Framework | **Quarkus** | core | Every service is a Quarkus app. Reactive, CDI plugin discovery, container-native. |
| Build | **Gradle (Kotlin DSL)**, multi-module | — | One repo, many service modules → many images. |
| Event/command bus | **SmallRye Reactive Messaging** (Mutiny) | `quarkus-messaging-kafka` (prod) · in-memory connector (dev/test) | Same annotated code; connector by profile. |
| Broker | **Kafka protocol** — Redpanda (light default self-host) or Apache Kafka (managed) | — | v1 dependency; operator's choice, swappable with no code change. |
| Event store | **Postgres**, append-only log | `quarkus-reactive-pg-client` | Behind `EventStore` port. KurrentDB = optional adapter only (ADR-007). |
| Schema migrations | **Flyway** | `quarkus-flyway` | Per-service schema; runs at startup. |
| Read models | **Postgres** projection tables | (same PG client) | Owned by `spire-ui`; Redis only if hot-path fan-out ever needs it. |
| Object store | **S3-compatible — MinIO** (self-host) | `quarkus-amazon-s3` | `BlobStore` port; **client-side Tink** encryption; transient assembled-context only, TTL auto-delete. Diffs never stored. |
| Domain formalism | **hand-rolled** `Decider`/`View`/`Saga` | (in `spire-contract`) | No fmodel dependency (ADR, minimal deps). |

## 3. Capabilities, LLM & RAG

| Concern | Choice | Extension / lib | Notes |
|---|---|---|---|
| LLM providers | **LangChain4j** | `quarkus-langchain4j-{openai,anthropic,vertex-ai-gemini,ollama}` | Default impl of the `LlmProvider` port (swappable to direct SDKs). **No default provider** — operator brings provider + API key via config; fail-fast if unset. |
| Prompt templates | **Qute** | `quarkus-qute` | Ported from PR-Agent's Jinja/TOML. |
| Diff/token IP (ported) | pure Java lib | `jtokkit` (token counting) | `spire-diff` module, ported from PR-Agent. |
| Vector store (P3) | **pgvector** default; Qdrant optional | `quarkus-langchain4j-{pgvector,qdrant}` | Reuse Postgres → one datastore; Qdrant as pluggable adapter. |

## 4. Security (clean-room, OSS-standard — ADR-009)

Zero code copied from the private monorepo; all public building blocks. See [SECURITY.md](SECURITY.md).

| Vector | Mechanism | Extension / lib |
|---|---|---|
| Encryption at rest | **Google Tink** — AES-GCM envelope, key ids + rotation. Field-level via JPA `AttributeConverter`. **Event payloads encrypted** (findings/context items may quote source; diffs themselves are never stored, ADR-011), plus token/secret columns. | `tink-java` |
| Resilience on external calls | **SmallRye Fault Tolerance** — per-call-class timeout/retry/circuit-breaker budgets (ADR-013) | `quarkus-smallrye-fault-tolerance` |
| Human auth (UI + mgmt API) | **OIDC**, provider-pluggable (Keycloak recommended, not required). Auth-code + PKCE. | `quarkus-oidc` |
| Authorization (RBAC) | Roles `spire-viewer` / `spire-admin`, enforced with `@RolesAllowed` | `quarkus-security` |
| Inbound webhook | **HMAC signature** verify + source IP allow-list (machine, no OIDC) | — |
| Service→service (REST) | **OAuth2 client-credentials** / service accounts | `quarkus-oidc-client` |
| Service→service (bus) | **Kafka SASL/SCRAM or mTLS** — most inter-service traffic is Kafka, so securing the bus covers the bulk | — |
| Outbound (Bitbucket/Jira/LLM) | Bot tokens / API keys from **secrets** (env / K8s Secret / Vault), never in image | — |
| Transport | TLS everywhere; mTLS between services optional (service mesh) | — |

## 5. UI

Confirmed as an **internal operator/observability surface** (end users interact only via PR comments).
Behind OIDC (`spire-viewer`/`spire-admin`). Served by `spire-ui` as a BFF.

- **Decided: latest React + Vite + TypeScript** SPA — broad OSS-contributor familiarity, mature
  WebSocket + charting ecosystem for the live event-timeline dashboard. (SvelteKit was weighed as a
  lighter, more reactive alternative; React chosen for contributor reach + zero learning curve.)
- Shows: live review timelines (event-model slices advancing), per-PR status, recent events,
  errors/retries, provider/latency stats.

## 6. Observability, testing, delivery

| Concern | Choice | Extension / lib |
|---|---|---|
| Metrics / tracing | Micrometer + OpenTelemetry (traceId propagated across Kafka) | `quarkus-micrometer`, `quarkus-opentelemetry` |
| Logging | Structured JSON; correlationId = review/PR id | `quarkus-logging-json` |
| Health | liveness/readiness (readiness fails if Kafka/DB down) | `quarkus-smallrye-health` |
| Config | MicroProfile Config + YAML; injected, fail-fast on missing required keys | `quarkus-config-yaml` |
| Testing | JUnit 5, REST Assured, Testcontainers (PG + Kafka), WireMock (SCM/LLM) | `quarkus-junit5`, `rest-assured`, `testcontainers` |
| Container | **Standard `docker build`** using Quarkus-provided `src/main/docker/Dockerfile.jvm` (+ `Dockerfile.native`) | — |
| Deploy | Helm / Kustomize, ArgoCD-friendly; `.env.example` secret contract | — |
| CI | GitHub Actions — build, Testcontainers, image publish, lint | — |

## 7. All locked

Every choice above is decided:
- **Event backbone:** program to the **Kafka protocol** (SmallRye Kafka connector); run **Redpanda**
  (light, default self-host) or **Apache Kafka** (managed) — operator's choice, swappable, no code change.
- **LLM:** **LangChain4j** as the default `LlmProvider` implementation, behind our own port (swappable).
- **UI:** latest **React + Vite + TypeScript**.
- **Container:** standard **`docker build`** (Quarkus `Dockerfile.jvm`/`.native`).
