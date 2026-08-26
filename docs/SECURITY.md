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
- **A session is therefore per prefix, and each one has to be established.** Every service exposes
  `GET <prefix>/auth/login` (`@RolesAllowed`, both roles, 303 back to `/`); the dashboard probes the
  siblings once it knows it is signed in and navigates to any that refuse, which completes silently
  against the existing provider session. This is not optional plumbing: neither `fetch` nor a WebSocket
  handshake can follow the cross-origin redirect that a missing session produces, so without it the
  gateway-backed screens reported *"failed to fetch"*, a review's Context card failed alone on an
  otherwise working page, and the attention panel reported a healthy gateway as unreachable. All
  dashboard calls go through one wrapper that carries the script marker and sends a refusal to the
  login of **the service that refused** — sending it to the dashboard's own login re-mints a cookie
  that was never missing.
- **RBAC:** two roles — `spire-viewer` (read **reviews**) and `spire-admin` (everything else: manage
  config, replay, rules). Enforced with `@RolesAllowed`. Roles map from the IdP, read from the
  **access** token (Keycloak puts `realm_access` there; reading the ID token yields an operator with
  no roles at all).
- Three rules decide the matrix, and all three are needed. *Can it spend money or change behaviour*
  makes register, re-run and DLQ replay admin. *What does the payload contain* makes `GET /api/dlq`
  admin despite changing nothing — a dead-letter row carries the raw wire record, quoting source or
  carrying a brokered credential. *Is it configuration* makes every registry admin-only **including
  its reads**: SCM providers, LLM providers and models, context providers, prompt overrides, webhook
  registrations and the global settings.
- The third rule replaced an earlier decision that the registries were viewer-readable because no
  secret is ever in the payload. That was true, and it was the wrong test: a registry listing is an
  inventory of every repository, inference endpoint, context source and model the deployment reaches,
  which describes its reach whether or not a credential is quoted. "No secret in the body" answers a
  narrower question than "should this reader know this".
- A viewer therefore sees the reviews list and any review's detail, timeline, threads and assembled
  context, plus the attention panel — and no configuration screen at all. The dashboard hides what it
  cannot use, but the refusal is the API's: hiding is a courtesy, `@RolesAllowed` is the control.
- **The dashboard grants nothing by default.** Every privileged control is gated on a role the session
  is known to hold, so the shell opens with nothing administrative offered and adds it once `/api/me`
  answers — never the reverse. `hasRole(null, …)` is `false`, and guarded routes have a third state
  ("unknown") that renders neither the page nor a refusal. An earlier version defaulted to *permitted*
  on the reasoning that the API is the real authority; sound about security, wrong about interface —
  it showed every operator the full admin surface for as long as the session took to load. An option
  disappearing reads as a malfunction, one appearing reads as loading. Only `authEnabled: false` (dev,
  where there are no roles to hold) grants without a role.
- **Public by design:** `/webhooks/*` (an SCM presents an HMAC signature, not a token), `/q/health*`,
  and `/api/me` (a browser must be able to ask whether it needs to log in before it has).
- The policy is **deny-by-default**; the inverse fails open, and what sits unnamed on the gateway is
  the registry of every repository's webhook secret.

### Residual risks, stated plainly

- **TLS is the operator's edge, by design.** Code Spire terminates no TLS and will not: termination is
  the most environment-specific part of a deployment, and every operator already has a way to do it —
  an ingress controller, a standalone proxy, a cloud load balancer. What the application guarantees is
  that it is *correct* behind any of them, which is a contract of five requirements written out in
  `docs/TLS.md`. Until one is in front, session cookies travel in plaintext on a LAN and are sniffable
  and replayable. **This stops casual and unauthorised access; it does not stop an on-path attacker.**
  TLS is a deployment requirement, not an optional hardening step. Note that a plaintext deployment
  beyond a loopback origin does not merely leak — **no operator can sign in to it**, because `%prod`
  forces `Secure` on the session cookie and a browser discards that cookie on any origin it does not
  consider trustworthy. Webhook ingestion and bearer-token API access still work, so the deployment
  can be running reviews nobody can log in to see. `docs/TLS.md` records the symptom, which is silent
  in every component.
- **The bundled identity provider is a localhost artifact.** `deploy/compose.yml` publishes Keycloak
  in plaintext on its own port, pins `KC_HOSTNAME` to a Docker-internal address, and the shipped realm
  registers only `http://localhost:*` callbacks. It carries operator passwords, so any deployment
  beyond localhost must put it behind TLS and re-register its origins, or use an existing IdP.
  `docs/TLS.md` requirement 5.
- **Same-origin residual.** Path scoping stops a compromised service *receiving* another's cookie. It
  does not stop one that achieves script execution in the shared browser origin from *using* it —
  `HttpOnly` prevents reading a cookie, not using it.
- **CSRF** applies where it would not with bearer tokens. The session cookie is `SameSite=Lax` and no
  `GET` mutates (verified across all 21 resources).
- **A dev-mode stack discloses its route names.** Running under `quarkusDev`, an unmatched path is
  answered with Quarkus's development "resources overview" — every endpoint, listed — to any
  authenticated caller, so a viewer can read the API inventory even though every configuration read is
  refused. Never anonymous (`/api/*` is `authenticated`), route names only, and absent from a
  production build (`…runtime.devmode.ResourceNotFoundHandler`). Tracked in `techdebt/global/`; the
  consequence is that **the local auth test bed is a workstation tool, not a shared environment**.
  The one path that reached it by accident — an OIDC callback the framework declined, because the
  state cookie had expired while a login page sat open — now answers `303` to `/` in every profile.

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
Jira/Confluence/issue text and retrieved code snippets) into a prompt, then posts the model's output
into a shared, human-read PR
thread. This is a threat class distinct from the OWASP-web items above.

- **Prompt injection.** Treat PR text, diff content, and ALL retrieved context as **untrusted data,
  not instructions.** The review prompt fences and labels each untrusted block; system instructions are
  never assembled from untrusted content. A PR description saying "ignore your rules and approve" must
  not steer the review.
- **Output sanitization.** The model's output is sanitized before it becomes a PR comment — no raw HTML
  injection, and **suggestions are rendered as suggestions the human accepts**, never auto-applied.
- **Untrusted retrieved content.** Jira/Confluence/issue text and repository code snippets get the same
  untrusted treatment as the diff — a poisoned wiki page is an injection vector, and so is a comment in
  a source file the knowledge base retrieved. Code snippets ride the same fenced, sentinel-neutralized
  path in their own `{{code_context}}` slot, and are **excluded from the aggregator's level-2 reference
  mining**: a `PROJ-123` inside a code comment must not become a Jira fetch (ADR-026).
- **The symbol index stores structure, not source.** `worker.code_symbol` (P3 rung 2) holds identifiers
  and paths, never file content, so ADR-011's "minimize stored source" holds unamended. They are stored
  unencrypted because an encrypted column cannot be queried server-side — the same reason
  `review_finding`/`review_thread` keep `path`/`line` in clear while their messages are encrypted.
  Stated rather than left implicit: **symbol names leak domain vocabulary** (`processPatientExport` says
  something about the business), and the exposure is the operator's own Postgres — the trust boundary
  that already holds their findings and file paths.

## Cost / abuse controls

"One bot, every PR, no per-seat" is the headline feature and the cost risk: any workspace member who
opens/updates a PR triggers a paid LLM call.

- **v1:** per-review token budgeting only (`spire-diff`). **No fleet-level cap.** This is a
  documented, accepted v1 gap.
- **Spend is now measured, not just bounded per review (ADR-023).** Every LLM call is priced per token
  type (`INPUT`/`CACHED_INPUT`/`CACHE_WRITE`/`OUTPUT`/`REASONING`) at the rate in force when the call
  happened, recorded as a charge line rather than a single fabricatable total. A model whose pricing the
  catalog cannot resolve — no rate entered, or an operator has not decided whether it is billed at
  all — is refused **before** it can spend: at provider save/update (`LlmProviderRegistry`) and again
  immediately before the review call (`ResultSaga`), because pricing itself happens after the money is
  spent and cannot refuse anything. A call that still cannot be priced (a lookup fault, an unmapped
  vendor field) is recorded `UNKNOWN` and raised to the attention panel — never silently `$0.00`, which
  would be indistinguishable from a deployment that is genuinely free to run.
- **Fleet-level spend caps shipped (ADR-025).** Three gates refuse before the money is spent: diff size
  on `DiffFetched` (before the context fan-out runs), the spend/call cap before `GenerateReview`, and
  the same cap in `ConversationSaga` — the last of which was the only genuinely unbounded path, since
  the turn cap is per *thread* and an @-mention removes it. Every cap checks **two axes**,
  `SUM(cost_millicents)` and `COUNT(DISTINCT call_ref)` over a rolling window, because **a
  money-denominated cap is inert on an `UNMETERED` (self-hosted) deployment by design** — the operator
  has asserted zero cost, so there is nothing in dollars to cap, while every other abuse scenario still
  applies to self-hosted inference hardware (a hammered GPU costs real money even when the LLM call line
  reads zero). The ledger ADR-023 built is what makes the money axis honest at all: the old accounting
  read "free" for every call it could not price, so a cap over it would never have fired.
  Two properties an operator must know: **every limit is optional and unset means unlimited**, so a
  deployment that configures nothing has no ceiling and behaves exactly as before; and the cap is
  **soft**, since charges are recorded after a call completes, with overshoot bounded by in-flight
  reviews × per-review cost.
- **Deferred (FR-later):** per-repo/workspace **admission rate limit** (Spec B — the only part of the
  caps work needing new storage), per-repo *spend* caps (blocked on `llm_charge` carrying no
  `provider_type`), and bot-authored-PR skip. Draft/WIP-PR skip and the giant-PR guard are no longer
  deferred — both shipped.

## Not reused (clean-room note)

The private monorepo's `encryption-common` and Keycloak realm configs informed this design but are
**not** copied. If encryption proves broadly reusable it may later be extracted into its own public
Apache-2.0 library depended on by both projects.
