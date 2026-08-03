# D10 — Authentication on the dashboard: corrected plan

> **Status: planned, not started (2026-08-03).** Roadmap item **D10**, the hard gate before Code Spire
> runs anywhere but a single operator's machine. This document supersedes two earlier drafts that were
> falsified in review — see [§2](#2-what-review-falsified) so those designs are not re-proposed.

Today the UI and **every** REST and WebSocket endpoint are unauthenticated. There is no
`quarkus-oidc` dependency and no `quarkus.http.auth` configuration anywhere.

## Verified inventory

| | Count | Notes |
|---|---|---|
| JAX-RS resources | **21** | orchestrator 15, gateway 5, worker 1 |
| WebSocket endpoints | **4** | all **Quarkus WebSockets Next** (`io.quarkus.websockets.next`), not `@ServerEndpoint` |
| `@QuarkusTest` classes | **37** | 16 drive HTTP via RestAssured |
| WebSocket client tests | **0** | none exist; sockets are tested through broadcasters and UI fakes |

Quarkus is **3.37.1** (`gradle.properties:4`). Machine-to-machine HTTP between our own services: **none** —
the only outbound HTTP clients call external hosts. The browser and SCM webhooks are the sole callers.

---

## 1. Decisions taken

| # | Decision |
|---|---|
| **D1** | **Restructure the URL space** so each service owns one non-nested prefix, making per-service cookie scoping real rather than nominal. |
| **D2** | **`quarkus.oidc.application-type=hybrid`** — cookie sessions for the browser and WebSockets, bearer/JWKS for curl, CI and the runbook. |
| **D3** | **Runtime authorization toggle via a custom `AuthorizationController` bean**, not by disabling OIDC. |
| **D4** | Two roles: `spire-viewer`, `spire-admin` (as `docs/SECURITY.md` already specifies). |
| **D5** | Dev IdP is either a bundled Keycloak behind an opt-in compose profile, **or** an already-running external one. Same config knob; the profile is the only difference. |
| **D6** | The production nginx edge is **out of scope**, deferred to `CICD-AND-PACKAGING.md`. D10 targets the single origin that already exists (the Vite proxy). |

---

## 2. What review falsified

Recorded so these are not re-proposed.

**Rejected — terminate auth at the orchestrator, proxying to gateway and worker.**
`/api/webhook-repos` carries plaintext webhook secrets on write. Proxying them through the orchestrator
would place them in the memory of the one service holding the master keyset and the event store,
destroying the boundary the gateway exists to create.

**Rejected — "the existing `spire-ui` nginx image".** No production image exists for anything;
`spire-ui` ships only `Dockerfile.dev` running Vite. The edge is new build work belonging to the
parked packaging item.

**Falsified — "per-service OIDC clients give session isolation".** They do not, on their own.
Cookies are scoped by **host + path**, not by backend. The orchestrator's cookie needs `Path=/`
because its surface spans `/api` *and* `/ws`, and every gateway and worker path nests underneath:

```
/api/webhook-repos    → gateway        ⎫
/api/review-context   → worker         ⎬ all beneath /api
/api/…                → orchestrator   ⎭
/ws/webhook-attention → gateway        ⎫ beneath /ws
/ws/…                 → orchestrator   ⎭
```

The browser attaches the orchestrator's cookie to gateway requests and the proxy forwards it
untouched. Per-service encryption secrets prevent *decryption*; they do nothing about **replay** — the
encrypted cookie **is** the credential. A compromised gateway could harvest and replay operator
sessions against the orchestrator under either design.

**This is why D1 exists.** Encryption isolation and replay isolation are different properties, and the
earlier draft conflated them.

---

## 3. Architecture

All three services run `quarkus-oidc` in **hybrid** mode. Cookies authenticate the browser and — the
reason this shape was chosen at all — the four WebSockets, since a browser cannot set an
`Authorization` header on a WS handshake and a credential must never appear in a query string.

### 3.1 URL map (D1)

Each service's authenticated surface sits under exactly one prefix, so `cookie-path` can scope it:

| Service | Prefix | `cookie-path` | Change |
|---|---|---|---|
| orchestrator | `/api/**` (incl. `/api/ws/**`) | `/api` | 15 resources unchanged; **3 sockets move** `/ws/x` → `/api/ws/x` |
| gateway (operator) | `/gw/**` | `/gw` | `/api/webhook-repos` → `/gw/webhook-repos`; `/ws/webhook-attention` → `/gw/ws/attention` |
| worker | `/wk/**` | `/wk` | `/api/review-context` → `/wk/review-context` |
| gateway (webhooks) | `/webhooks/{provider}/{key}` | none — public | **unchanged, deliberately** |

**The webhook paths must not move.** They are registered with live GitHub, GitLab and Bitbucket
installations; changing them silently breaks delivery. They also sit outside every `cookie-path`, so no
session cookie is ever sent to a public endpoint.

Chosen to minimise churn: the orchestrator keeps `/api` (15 of 21 resources untouched). Only 3 sockets
and 2 endpoints move. The Vite proxy simplifies to three prefix rules with **`changeOrigin` removed**
(see §6). Every path is browser-only, so the sole consumer is our own SPA, shipped together — no
external API contract breaks.

### 3.2 Session isolation

Per-service OIDC client, per-service cookie name (`cookie-suffix`), per-service `cookie-path`. With
D1 in place a service only ever receives its own cookie, so a compromised gateway cannot replay an
orchestrator session. Cost: the browser performs a silent SSO flow per service on first load (no
re-login prompt — the IdP SSO session is shared), and logout must be initiated for all three.

### 3.3 Roles (D4)

| Access | Endpoints |
|---|---|
| **public** | `/webhooks/**`, `/q/health*` |
| **`spire-viewer`** | every GET, plus all 4 WebSockets |
| **`spire-admin`** | every mutation — providers, llm-\*, context-providers, webhook-repos, prompts, settings, review delete — **plus** manual PR register, review re-run, and **`POST /api/dlq/{id}/replay` + `DELETE /api/dlq/{id}`** |

The admin line is *"can it spend money or change behaviour"*, not merely read/write. Register, re-run
and DLQ replay all re-trigger pipeline processing that reaches paid LLM calls.

Decide deliberately rather than by blanket rule: `POST …/attention-ack` is a mutation but only
acknowledges a failed review — viewer is defensible. `GET /api/reviews/{…}/threads/{ref}` makes
outbound SCM calls, so a viewer can consume SCM rate limit; acceptable, but say so.

---

## 4. Phase 0 — spike first

Five unknowns, none of which should be discovered after code is built on them.

1. **Cookie scoping under D1** — confirm a service receives only its own cookie once prefixes and
   `cookie-path`/`cookie-suffix` are set. This tests *isolation*, not merely "does login work".
2. **Script-initiated redirect contract** — `quarkus.oidc.authentication.java-script-auto-redirect=false`:
   exact status and headers an unauthenticated XHR receives on 3.37, and the marker header the UI must
   send. Without this an unauthenticated `fetch()` gets a 302 the browser follows cross-origin, dying
   as an opaque CORS failure the SPA never sees as 401.
3. **WebSockets Next** — does `@RolesAllowed` apply to a `@WebSocket` endpoint; does identity expiry
   close an already-open connection; what close code does the UI see; does `@TestSecurity` reach a real
   handshake (**no WS client test infrastructure exists** — this may need building).
4. **Login-return choreography** — Quarkus restores the *originally requested* URL, which for an
   XHR-triggered login is an API path, not a SPA route. Needs `redirect-path` plus SPA-side return
   handling. Most likely unknown to invalidate the design late.
5. **Cookie size** — default token-state keeps ID + access + refresh tokens encrypted in the cookie;
   Keycloak role claims routinely push past 4KB into chunked cookies. Measure, and consider
   `token-state-manager.strategy=id-refresh-tokens`.

---

## 5. Phasing

Vertical slices, one service at a time. Each commit ships **policy + RBAC + `@TestSecurity` fixes +
negative tests together**, so the build is never red. Tests are strictly per-service with no shared
cross-service infrastructure, which is what makes this work.

**Order: gateway → worker → orchestrator.** The gateway is the cheaper pathfinder (2 operator
resources, 1 socket) and is the internet-facing service; meeting every OIDC unknown for the first time
on the orchestrator's 15 resources and 3 sockets is the expensive way to learn.

> **Commit 1 must disable Keycloak Dev Services** (`quarkus.keycloak.devservices.enabled=false`) for
> **both `%dev` and `%test`**. With `quarkus-oidc` on the classpath and no `auth-server-url`, Dev
> Services boots its own Keycloak container per service — three surprise containers, or 37
> `@QuarkusTest` classes hanging on a container pull.

UI work follows the backends. The ADR and the `SECURITY.md` rewrite land **with the design**, not in a
trailing docs phase.

---

## 6. Named deliverables

- **Runtime toggle (D3).** `spire.security.auth-enabled` defaults **true**; false outside `%dev`/`%test`
  must **refuse to start**, not warn — otherwise it becomes an env var that silently runs the app open.
  Implemented as an `AuthorizationController` bean: disabling OIDC instead would leave `@RolesAllowed`
  compiled in with no identity, so every endpoint would 401 rather than open. `%dev` defaults to
  **off** so the stack runs without an IdP.
- **`/api/me`** (orchestrator-owned): is auth enabled at all, who am I, which roles — so the SPA renders
  correctly including in auth-disabled dev mode. It cannot report gateway/worker config divergence; note that.
- **Drop `changeOrigin: true`** from the Vite proxy. It rewrites `Host` to the backend's port, so
  `redirect_uri` comes out as `localhost:34080`. `proxy-address-forwarding` does **not** fix this —
  Vite never sends `x-forwarded-host`. Keep `proxy-address-forwarding` + trusted-proxy config as a
  documented requirement for the *future* edge only, noting it is a header-spoofing vector while the
  service ports remain directly reachable.
- **CSRF.** Cookie sessions are CSRF-reachable in a way bearer tokens are not. Audit the session
  cookie's `SameSite`, audit all 21 resources to confirm no GET mutates, then decide SameSite-only vs
  `quarkus-rest-csrf`.
- **UI auth state machine.** A central `fetch` wrapper emitting the script-marker header and handling
  the login redirect; **auth-aware WebSocket reconnect** — today `useLiveReviews.ts:93` reconnects
  unconditionally every 1.5s with no close-code inspection, so an auth-rejected socket becomes an
  infinite hammer; login/logout; hide admin actions from viewers.
- **Logout and session lifetime.** Keycloak's default ID-token lifetime (~5 min) logs the operator out
  constantly unless refresh/session-age extension is configured — and three self-contained cookies
  survive an IdP logout unless RP-initiated logout is wired per service. Specify, or record
  "expiry only, no logout in v1" in the ADR.
- **Dev IdP (D5).** Bundled Keycloak pinned to a current tag, host port in the **34xxx** range, behind
  `--profile idp`, with `depends_on` + healthcheck and OIDC connection-retry tuning so a plain
  `docker compose up` cannot crash-loop the services. External option via `SPIRE_OIDC_AUTH_SERVER_URL`.
  Both consume a shipped `infra/keycloak/realm-spire.json` (clients, both roles, an obviously-synthetic
  `dev-operator` user per the no-fabricated-data rule; redirect URIs covering **both** dev origins,
  `localhost:34000` and `:39285`).
- **Containerised dev issuer.** In the `:39285` mode the services reach Keycloak as `keycloak:8080`
  while the browser uses `localhost` — the token issuer will not match discovery. Needs a `KC_HOSTNAME`
  strategy or per-mode issuer config.
- **`.env.example`** gains the new secrets — OIDC client secret(s), token-state encryption secret,
  auth-server URL, Keycloak bootstrap credentials — with **no defaults**, fail-fast.
- **The orchestrator's own static dashboard** (`spire-orchestrator/src/main/resources/META-INF/resources/index.html`)
  is an unmentioned browser surface. Role-gate it as viewer, or delete it — `spire-ui` superseded it.
- **ADR** recording the departure from `SECURITY.md:20`, which prescribes *"auth-code + PKCE at the UI;
  JWT bearer validated per request against the issuer's JWKS"*. Hybrid mode keeps the bearer half, so
  the departure is narrower than a pure cookie design, but it is still a departure. The same edit should
  delete SECURITY.md's now-dead *"OAuth2 client-credentials for sync service calls"* row — verified
  there are no internal service-to-service HTTP calls.
- **Runbook.** `docs/SMOKE-TEST.md` needs a documented way to authenticate its curl-driven steps —
  which is what D2 (hybrid) exists to make possible.

---

## 7. Explicitly out of scope

- **The production nginx edge** — deferred to `CICD-AND-PACKAGING.md`, which is itself parked behind
  this item. D10 authenticates against the single origin that exists today.
- **TLS.** Until the edge lands, session cookies travel plaintext on a LAN and are sniffable and
  replayable. **D10 stops casual and unauthorised access; it does not stop an on-path attacker.** This
  belongs in SECURITY.md explicitly, and `cookie-force-secure` cannot be enabled without breaking
  http dev.
- Per-repo or per-workspace authorization. Two global roles only.
- Kafka bus security (SASL/mTLS) — already separately noted in SECURITY.md.

## 8. Verified non-issues

Checked so they are not re-raised: `spire-arch` cannot trip on `keycloak`/`oidc` (it scans only SCM and
context provider names); the ADR-021 invariant is safe because all auth code lands in the three FSL
service modules — but if a shared module is ever added, `LICENSING.md` and its `LICENSE` file must
follow; no Flyway migration is needed (sessions live in cookies), so there is no startup-ordering risk.

## 9. Effort

**M–L.** The roadmap's **M** is optimistic. 21 resources, 4 sockets, 37 test classes, a URL
restructure, a UI auth state machine, WS test infrastructure that does not yet exist, and the realm
plus compose work. It is *not* L only because the production edge is out of scope.

---

## References

- [ROADMAP.md](ROADMAP.md) — item **D10**
- [SECURITY.md](SECURITY.md) — the prescribed design this plan departs from (§6, ADR deliverable)
- [CICD-AND-PACKAGING.md](CICD-AND-PACKAGING.md) — the parked item that owns the production edge
- [DECISIONS.md](DECISIONS.md) — ADR-015 (brokered credentials), ADR-021 (split licensing)
