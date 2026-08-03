# Security

Decided model (ADR-009). Clean-room, OSS-standard, zero code copied from any private source.
Trust boundaries, authn/authz, encryption, and secrets.

## Actors & trust boundaries

| Actor | Boundary crossed | How it's trusted |
|---|---|---|
| Bitbucket (webhooks) | → `spire-gateway` | **HMAC signature** verify + source IP allow-list. Machine — no OIDC. |
| Human operator | → `spire-ui` / management API | **OIDC** (auth-code + PKCE) + **RBAC** roles. |
| A Code Spire service | → another service (REST) | **OAuth2 client-credentials** (service account). |
| A Code Spire service | → another (async) | **Kafka** with SASL/SCRAM or mTLS. |
| Code Spire | → Bitbucket / Jira / LLM | Bot tokens / API keys from **secrets**, TLS out. |

## Authentication & authorization (humans)

- **`quarkus-oidc`**, provider-pluggable via `quarkus.oidc.auth-server-url`. **Keycloak is the
  recommended/documented IdP but not required** — any compliant OIDC provider works.
- **Flow: `application-type=hybrid`** — a cookie session for the browser and its four WebSockets,
  bearer/JWKS for `curl`, CI and the runbook. This **supersedes** the pure bearer design this
  document originally specified; a browser cannot set an `Authorization` header on a WebSocket
  handshake, and a credential must not travel in a query string. See **ADR-022** for the full
  reasoning and what it costs.
- **Each service is its own OIDC client**, with its own cookie name and `cookie-path`, and owns one
  URL prefix: orchestrator `/api` (sockets at `/api/ws/*`), gateway `/gw`, worker `/wk`. Cookies are
  scoped by host+path, not by backend, so the prefixes are what stop one service receiving another's
  session credential. The realm needs an **audience mapper per client**.
- **RBAC:** two roles — `spire-viewer` (read the dashboard) and `spire-admin` (manage config, replay,
  rules). Enforced with `@RolesAllowed`. Roles map from the IdP, read from the **access** token
  (Keycloak puts `realm_access` there; reading the ID token yields an operator with no roles at all).
- Two rules decide the matrix, and both are needed. *Can it spend money or change behaviour* makes
  register, re-run and DLQ replay admin. *What does the payload contain* makes `GET /api/dlq` admin
  despite changing nothing — a dead-letter row carries the raw wire record, quoting source or
  carrying a brokered credential.
- **Public by design:** `/webhooks/*` (an SCM presents an HMAC signature, not a token), `/q/health*`,
  and `/api/me` (a browser must be able to ask whether it needs to log in before it has).
- The policy is **deny-by-default**; the inverse fails open, and what sits unnamed on the gateway is
  the registry of every repository's webhook secret.

### Residual risks, stated plainly

- **No TLS yet.** Until the production edge lands (see `CICD-AND-PACKAGING.md`), session cookies
  travel in plaintext on a LAN and are sniffable and replayable. **This stops casual and unauthorised
  access; it does not stop an on-path attacker.** TLS is a deployment requirement, not an optional
  hardening step.
- **Same-origin residual.** Path scoping stops a compromised service *receiving* another's cookie. It
  does not stop one that achieves script execution in the shared browser origin from *using* it —
  `HttpOnly` prevents reading a cookie, not using it.
- **CSRF** applies where it would not with bearer tokens. The session cookie is `SameSite=Lax` and no
  `GET` mutates (verified across all 21 resources).

## Service-to-service

- Most inter-service traffic is **async over Kafka** → securing the bus (SASL/SCRAM or mTLS) covers
  the bulk. Topic-level ACLs per service principal.
- **There is no synchronous service-to-service HTTP.** Every outbound HTTP client in the services
  calls an *external* host (an SCM, an LLM, a context source); the only inbound callers are the
  browser and SCM webhooks. An earlier version of this document specified OAuth2 client-credentials
  for `spire-ui` → `spire-orchestrator` calls — but `spire-ui` is a browser application, not a
  service, so those are operator requests carrying an operator session, not machine-to-machine ones.

## Inbound webhook hardening (`spire-gateway`)

1. Verify the SCM signature with the per-hook secret — reject on mismatch. **Scheme is per-provider**
   (SCM-MAPPING §7): Bitbucket `X-Hub-Signature` (HMAC-SHA256), GitHub `X-Hub-Signature-256`
   (HMAC-SHA256), GitLab `X-Gitlab-Token` (constant-time static-token compare, **not** HMAC).
2. **Source IP allow-list** (SCM published egress ranges). Note: Bitbucket Cloud signed webhooks are a
   recent, opt-in feature, so on Bitbucket the allow-list is doing real work, not just defense-in-depth.
3. **Drop bot-authored events** — ignore any webhook whose actor is the bot's own identity, or the bot
   answers its own follow-up comments forever (ADR-013).
4. Validate payload shape, then **emit one event and return `202`** — never process inline.

## Encryption at rest (Google Tink)

- **Tink** AES-GCM **envelope encryption**: a KEK (from KMS/keystore) wraps per-record DEKs; ciphertext
  carries a **key id** so **rotation** is non-breaking.
- **Field-level** via a JPA `AttributeConverter` for sensitive columns (SCM/Jira tokens, provider keys).
- **Event payloads are encrypted** in the event log — events don't carry diffs (ADR-011: metadata
  only, diffs re-fetched), but **findings and context items may quote source code**, which must not
  sit in the DB in cleartext. Randomized (AES-GCM) by default; where an encrypted value must
  be looked up, add a separate **blind index** (HMAC) rather than weakening to deterministic encryption.
- Keys never live in the image or git; sourced from KMS / a mounted keystore / Vault.
- **KEK blast radius (ADR-015):** the KEK is held by `spire-orchestrator` (event log + provider
  registry), `spire-ui` (encrypted finding fields), and — in **active mode** — `spire-review-worker`,
  which decrypts the per-command SCM credential the orchestrator brokers to it. `spire-gateway` never
  holds the KEK (only the webhook secret — the self-loop guard's bot account id lives in the registry,
  read by the orchestrator, so the gateway holds no SCM identity). A compromised worker therefore exposes the
  master key; this was an explicit operator trade-off (one keyset, no cross-schema DB read) over a
  dedicated worker-only key. The narrowing path — a separate envelope key for worker credentials — is
  recorded in ADR-015. Keep the holder list this short as services are added.
- **Scope honesty — the message bus (ADR-014):** the "never rests in cleartext" guarantee applies to
  **application-managed stores** (Postgres, MinIO). Source-quoting payloads on `cs.results` DO rest on
  the broker's disk for the retention window without app-layer Tink (findings stay inline + plaintext
  per ADR-011/-014). Note (ADR-015): the SCM **credential** on `cs.commands` IS app-encrypted with the
  KEK — the worker holds it in active mode — so credentials never rest in cleartext on the bus, even
  though findings do. Mitigation for the latter is infrastructural: **short retention on `cs.results`**
  + **broker disk/volume encryption** (a
  documented deployment requirement) + SASL/mTLS transport. Escalation path if ever needed: findings
  behind an encrypted blob ref instead of inline.

## Secrets

- Bot App Password/token, webhook HMAC secret, provider API keys, Tink KEK, OIDC client secrets.
- Stored in env / K8s Secret / Vault. **No defaults** for required secrets — services **fail-fast** at
  startup if unset. A `.env.example` documents every key with safe placeholders (never real values).

## Transport & data handling

- **TLS everywhere**; mTLS between services optional (service mesh) — opt-in.
- **Never log** secrets, tokens, or full source/diff content — redact at the log call site.
- LLM calls: only send the minimum diff+context required; provider chosen at config so an operator can
  keep inference in-tenant (Vertex/Ollama) when code must not leave the boundary.

## LLM & abuse threat model

The bot ingests **attacker-influenced content** (PR title/description, the diff, and retrieved
Jira/Confluence/RAG text) into a prompt, then posts the model's output into a shared, human-read PR
thread. This is a threat class distinct from the OWASP-web items above.

- **Prompt injection.** Treat PR text, diff content, and ALL retrieved context as **untrusted data,
  not instructions.** The review prompt fences and labels each untrusted block; system instructions are
  never assembled from untrusted content. A PR description saying "ignore your rules and approve" must
  not steer the review.
- **Output sanitization.** The model's output is sanitized before it becomes a PR comment — no raw HTML
  injection, and **suggestions are rendered as suggestions the human accepts**, never auto-applied.
- **Untrusted retrieved content.** Jira/Confluence/RAG snippets get the same untrusted treatment as the
  diff — a poisoned wiki page is an injection vector.

## Cost / abuse controls

"One bot, every PR, no per-seat" is the headline feature and the cost risk: any workspace member who
opens/updates a PR triggers a paid LLM call.

- **v1:** per-review token budgeting only (`spire-diff`). **No fleet-level cap.** This is a
  documented, accepted v1 gap.
- **Deferred (FR-later):** per-repo/workspace rate limit, daily LLM spend cap, giant-PR guard,
  draft/WIP-PR skip, bot-authored-PR skip. An operator running v1 must know there is no built-in
  ceiling on spend yet.

## Not reused (clean-room note)

The private monorepo's `encryption-common` and Keycloak realm configs informed this design but are
**not** copied. If encryption proves broadly reusable it may later be extracted into its own public
Apache-2.0 library depended on by both projects.
