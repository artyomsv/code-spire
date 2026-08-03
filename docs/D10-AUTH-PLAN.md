# D10 — Authentication on the dashboard: corrected plan

> **Status: planned, spike in progress (2026-08-03).** Roadmap item **D10**, the hard gate before
> Code Spire runs anywhere but a single operator's machine. Three adversarial reviews each falsified a
> premise of an earlier draft — see [§2](#2-what-review-falsified) so none is re-proposed.

Today the UI and **every** REST and WebSocket endpoint are unauthenticated. There is no
`quarkus-oidc` dependency and no `quarkus.http.auth` configuration anywhere.

## Verified inventory

| | Count | Notes |
|---|---|---|
| JAX-RS resources | **21** | orchestrator 15, gateway 5, worker 1 (+ `DevSimulatorResource`, prod-excluded and stub-gated) |
| WebSocket endpoints | **4** | all **Quarkus WebSockets Next** (`io.quarkus.websockets.next`), not `@ServerEndpoint` |
| `@QuarkusTest` classes | **37** | 16 drive HTTP via RestAssured |
| WebSocket client tests | **0** | none exist; sockets are tested through broadcasters and UI fakes |

Quarkus **3.37.1** (`gradle.properties:4`). No machine-to-machine HTTP between our own services.

---

## 1. Decisions taken

| # | Decision |
|---|---|
| **D1** | **Restructure the URL space** so each service owns one non-nested prefix, making per-service cookie scoping real. |
| **D2** | **`quarkus.oidc.application-type=hybrid`** — cookie sessions for the browser, bearer/JWKS for curl, CI and the runbook. See §3.4: the WebSocket rationale originally given for this was **wrong**. |
| **D3** | **Runtime authorization toggle via a custom `AuthorizationController`**, not by disabling OIDC. |
| **D4** | Two roles: `spire-viewer`, `spire-admin`. |
| **D5** | Dev IdP is either a bundled Keycloak behind an opt-in compose profile **or** an already-running external one. |
| **D6** | The production nginx edge is **out of scope**, deferred to `CICD-AND-PACKAGING.md`. |

---

## 2. What review falsified

**Rejected — terminate auth at the orchestrator, proxying to the other two.** `/api/webhook-repos`
carries plaintext webhook secrets on write; proxying them through the orchestrator would place them in
the one service holding the master keyset and the event store.

**Rejected — "the existing `spire-ui` nginx image".** No production image exists; `spire-ui` ships only
`Dockerfile.dev` running Vite. The edge is new build work belonging to the parked packaging item.

**Falsified — "per-service OIDC clients give session isolation".** Cookies scope by **host + path**, not
by backend. With the orchestrator on `Path=/` and every gateway and worker path nested beneath `/api`
and `/ws`, the browser sent the orchestrator's cookie to the gateway and the proxy forwarded it. Per-service
encryption secrets prevent *decryption*, not **replay** — the encrypted cookie **is** the credential.
This is why D1 exists. (Encryption isolation ≠ replay isolation.)

**Falsified — "a browser cannot set an `Authorization` header on a WebSocket handshake".** This was the
stated reason for choosing cookies at all. Quarkus WebSockets Next documents a carrier:
`quarkus-http-upgrade#<header-name>#<header-value>` encoded into `Sec-WebSocket-Protocol`, with
`quarkus.websockets-next.server.supported-subprotocols` + `propagate-subprotocol-headers=true`. A third
option existed and was never considered. Hybrid may still be right, but not for that reason — see §3.4.

---

## 3. Architecture

### 3.1 URL map (D1)

Each service's authenticated surface sits under one prefix so `cookie-path` can scope it:

| Service | Prefix | `cookie-path` | Change |
|---|---|---|---|
| orchestrator | `/api/**` (incl. `/api/ws/**`) | `/api` | 15 resources unchanged; **3 sockets move** `/ws/x` → `/api/ws/x` |
| gateway (operator) | `/gw/**` | `/gw` | `/api/webhook-repos` → `/gw/webhook-repos`; `/ws/webhook-attention` → **`/gw/ws/webhook-attention`** |
| worker | `/wk/**` | `/wk` | `/api/review-context` → `/wk/review-context` |
| gateway (webhooks) | `/webhooks/{provider}/{key}` | none — public | **unchanged, deliberately** |

**The webhook paths must not move.** They are registered with live GitHub, GitLab and Bitbucket
installations; changing them silently breaks delivery. They also sit outside every `cookie-path`.

**Every rename must be a pure prefix addition.** Four broadcasters filter connections by path, and
`WebhookAttentionBroadcaster.java:26,67` matches with `endsWith("/ws/webhook-attention")`. Renaming that
socket to `/gw/ws/attention` would make the filter false, so **every push would match zero connections,
silently**. The orchestrator's three (`AttentionBroadcaster.java:35,92`, `ReviewProjection.java:1142`,
`TimelineBroadcaster.java:57`) survive only because their move *is* a pure prefix add — so the pattern
looks safe three times and fails the fourth, in the first slice, with no WS test to catch it. Keep the
gateway socket's suffix intact (`/gw/ws/webhook-attention`) **and** switch these filters to exact
comparison so the trap cannot recur.

**Consumers outside the SPA exist** — an earlier draft claimed otherwise. All must move together:

- `spire-orchestrator/src/main/resources/META-INF/resources/index.html:72` — hardcodes `/ws/timeline`
  and does **not** ship with the SPA (see §6: role-gate or delete)
- `docs/SMOKE-TEST.md:222` — curls `POST :34080/api/reviews/register`; Mode G is the reusable
  regression script, i.e. live tooling
- `README.md:51`, `spire-ui/README.md:11`, `useAttention.ts:18-19`, `AttentionBell.test.tsx:70-71`,
  and `ORCHESTRATOR_WS_URL`/`GATEWAY_WS_URL` in `docker-compose.dev.yml:157-158`

A whole-repo sweep found nothing else — no nginx config, no scripts, no other compose file.

### 3.2 Session isolation — what path scoping does and does not buy

Per-service OIDC client, cookie name (`cookie-suffix`) and `cookie-path`. **What it buys: a compromised
gateway never *receives* an orchestrator credential** — the browser will not send it there.

**What it does not buy:** D6 keeps a single origin. A compromised gateway that gets anything executing
under `/gw/**` in the operator's browser can drive authenticated same-origin `fetch('/api/…')`.
`HttpOnly` prevents *reading* a cookie, not *using* it. Path scoping raises the bar from "harvest and
replay offline" to "achieve script execution in the shared origin"; it does not eliminate the class.
Record this beside §7's TLS paragraph rather than claiming isolation the design does not deliver.

The bearer half needs per-service `quarkus.oidc.token.audience`, or a token minted for one service is
valid at all three — the same residual by another route.

### 3.3 Roles (D4)

| Access | Endpoints |
|---|---|
| **public** | `/webhooks/**`; `/q/health*` **as an explicit permit rule**, not by absence of a rule |
| **`spire-viewer`** | GETs that expose only review/config metadata, plus all 4 WebSockets |
| **`spire-admin`** | every mutation — providers, llm-\*, context-providers, webhook-repos, prompts, settings, review delete — **plus** manual register, review re-run, and `POST /api/dlq/{id}/replay` + `DELETE /api/dlq/{id}` |

The admin line is *"can it spend money or change behaviour"*. That partitions mutation and is **blind to
disclosure**, which is how the following was missed:

- **`GET /api/dlq` must be admin.** `DlqEntry` carries `payload` and `DlqRepository` does `SELECT *`
  unfiltered. Per SECURITY.md those are raw wire records — `cs.results` carries findings inline
  (quoting source), `cs.commands` the encrypted SCM credential. Audit `GET /api/timeline` the same way.

**Non-mutating POSTs, assigned explicitly** (neither "every GET" nor "every mutation" reaches them):

| Endpoint | Role | Why |
|---|---|---|
| `POST /api/reviews/register/resolve` | viewer | parse-only |
| `POST /api/providers/{id}/check`, `/verify-repo` | **admin** | writes `last_check_ok` (V28), spends SCM rate limit |
| `POST /api/llm-providers/{id}/check` | **admin** | the only path recording a verified LLM credential |
| `POST /api/context-providers/{id}/check`, `/preview` | **admin** | resolves a real ticket using stored credentials |
| `POST /api/prompts/{kind}/preview` | viewer | renders a template, no external call |
| `POST /dev/simulate-pr` | **admin** | prod-excluded and stub-gated, but must still carry a rule |

**No GET mutates** — verified across all 21 resources, so §6's CSRF reasoning rests on a true premise.
Note in the rationale that `GET …/threads/{ref}` and `GET …/description` both make outbound SCM calls,
so a viewer can consume SCM rate limit. Acceptable; state it.

### 3.4 The WebSocket problem — the real one

Cookies were chosen because headers were believed impossible on a WS handshake. That was wrong (§2).
The genuine difficulty is what happens to an **unauthenticated or expired** socket:

- Hybrid begins the code flow when no bearer header is present. `new WebSocket()` sends none → **302**.
- The browser WebSocket API does not follow redirects; the socket fails with no useful close code.
- `java-script-auto-redirect=false` returns **499** only when the request carries
  `X-Requested-With` — which `new WebSocket()` also cannot set. **The 499 contract the plan relies on
  for `fetch()` is structurally unavailable to all four sockets.**
- Quarkus auto-closes a socket on token expiry. With Keycloak's ~5-minute ID-token default, **every
  socket dies every five minutes.**
- `useLiveReviews.ts:93` and `useAttention.ts` reconnect every 1.5s unconditionally, with no close-code
  inspection → three sockets hammering the IdP.
- Worse than silent: `useAttention.ts:43-59` synthesizes a **BLOCKING** row reading *"The webhook
  gateway is not responding, so no pull request event can arrive."* An auth failure would be reported
  to the operator as an outage.

Two candidate answers, to be settled by the spike: **(a)** use the sub-protocol carrier to send the
script-marker header on the upgrade so the 499 contract applies — scope `supported-subprotocols`
narrowly, it is a header-injection surface; or **(b)** open no socket until `/api/me` reports
authenticated, and treat a close as "re-check auth", never "retry in 1.5s". Either way the socket
lifecycle, token lifetime and reconnect policy are **one problem**, not three.

---

## 4. Phase 0 — spike

Six questions. Nothing is built on an unmeasured answer.

1. **Cookie scoping *and* the callback path — one experiment.** `cookie-path` governs the `q_auth_*`
   **state** cookie as well as the session cookie. So a `redirect-path` on a SPA route (`/`, `/reviews`)
   would never receive the state cookie and login would fail 100% of the time. Callbacks must live under
   each service's own prefix (`/api/auth/callback`, `/gw/…`, `/wk/…`), each with its own realm redirect
   URI. Confirm scoping holds for state, session, chunked and post-logout cookies, and on a WS upgrade.
2. **Script-initiated redirect contract** — exact status and headers an unauthenticated `fetch()`
   receives on 3.37, and the marker header required.
3. **The WebSocket lifecycle as one question** (§3.4) — does securing the HTTP upgrade work via
   `quarkus.http.auth.permission.*` (docs: `@AuthorizationPolicy` on a `@WebSocket` *method* fails the
   build); does the sub-protocol carrier accept a non-`Authorization` header; what close code does an
   unauthenticated or expired socket produce; does `@TestSecurity` reach a real handshake (**no WS test
   infrastructure exists** — this may need building).
4. **Cookie size** — token-state keeps ID + access + refresh encrypted in the cookie; Keycloak role
   claims routinely exceed 4KB into chunked cookies. Measure; consider `strategy=id-refresh-tokens`.
5. **`AuthorizationController` reach** — does it govern `quarkus.http.auth.permission.*` policies, or
   only annotation checks? Documented as separate code paths. If it does not reach them, D3's kill-switch
   is partial: REST opens in `%dev` while sockets still challenge. **Load-bearing, because question 3's
   likely fix uses HTTP permissions.**
6. **IdP-less `%dev` boot** — with `quarkus-oidc` on the classpath, Dev Services off and no
   `auth-server-url`, the tenant has nothing to discover. Confirm `%dev.quarkus.oidc.tenant-enabled=false`
   **plus** the controller is required, and that **both** Dev Services are disabled
   (`quarkus.keycloak.devservices.enabled` *and* `quarkus.oidc.devservices.enabled`, the latter enabled
   by default when Docker/Podman are absent).

---

## 5. Phasing

Vertical slices, one service at a time, **gateway → worker → orchestrator** (the gateway is the cheap
pathfinder: 2 operator resources, 1 socket, and it is the internet-facing service).

Each commit ships **policy + RBAC + `@TestSecurity` fixes + negative tests together** so the build is
never red. Test suites are strictly per-service, which is what makes this work — **but the Vite proxy
is shared cross-service infrastructure, and the URL restructure crosses it.** The moment the gateway
moves to `/gw/**`, `api.ts` still calls `/api/webhook-repos`, which `vite.config.ts`'s `/api` catch-all
routes to the orchestrator → a 404 no test catches. **Each slice must carry its own Vite rule and its
`api.ts` call sites**, or backends must serve both prefixes for the interim. Pick one and say so.

> **Commit 1 must disable both Dev Services** for `%dev` and `%test`, or 37 `@QuarkusTest` classes hang
> pulling a Keycloak container.

UI work follows the backends. The ADR and `SECURITY.md` rewrite land **with the design**.

---

## 6. Named deliverables

- **Runtime toggle (D3).** `spire.security.auth-enabled` defaults **true**; false outside `%dev`/`%test`
  must **refuse to start**. `%dev` defaults **off**, which requires `tenant-enabled=false` *and* the
  controller (spike 6). Disabling OIDC alone would leave `@RolesAllowed` compiled in with no identity —
  every endpoint 401s rather than opening.
- **`/api/me`** (orchestrator-owned): is auth enabled, who am I, which roles. Cannot report
  gateway/worker config divergence; note that.
- **Drop `changeOrigin: true`** from the Vite proxy — it rewrites `Host` so `redirect_uri` becomes
  `localhost:34080`. `proxy-address-forwarding` does **not** fix this; Vite never sends
  `x-forwarded-host`. Keep that setting as a documented requirement for the *future* edge only, noting
  it is a header-spoofing vector while service ports stay directly reachable.
- **CSRF.** Audit the session cookie's `SameSite`; decide SameSite-only vs `quarkus-rest-csrf`.
- **UI auth state machine.** Central `fetch` wrapper emitting the marker header; **auth-aware socket
  lifecycle** per §3.4 replacing the unconditional 1.5s reconnect; login/logout; hide admin actions from
  viewers; and stop `useAttention` reporting an auth failure as a gateway outage.
- **Logout and session lifetime.** Configure refresh/session-age extension against the ~5-minute default,
  and wire RP-initiated logout per service — three self-contained cookies otherwise survive an IdP logout.
  Or record "expiry only, no logout in v1" in the ADR.
- **Dev IdP (D5).** Bundled Keycloak pinned, host port in the **34xxx** range, behind `--profile idp`,
  with `depends_on` + healthcheck and connection-retry tuning. External option via
  `SPIRE_OIDC_AUTH_SERVER_URL`. Both consume `infra/keycloak/realm-spire.json` — clients, both roles, an
  obviously-synthetic `dev-operator` user, and redirect URIs covering both dev origins **and each
  service's callback path** (spike 1).
- **Containerised dev issuer.** At `:39285` the services reach Keycloak as `keycloak:8080` while the
  browser uses `localhost` — issuer will not match discovery. Needs a `KC_HOSTNAME` strategy.
- **`.env.example`** gains the new secrets, no defaults, fail-fast.
- **The orchestrator's static dashboard** (`META-INF/resources/index.html`) — role-gate or delete; it
  hardcodes `/ws/timeline` and breaks under D1 either way.
- **ADR** for the departure from `SECURITY.md:20` (bearer/JWKS), including the corrected WebSocket
  rationale, per-service `token.audience`, and deletion of the dead "OAuth2 client-credentials for sync
  service calls" row.
- **Runbook.** `SMOKE-TEST.md` needs a documented way to authenticate its curls — what D2 exists for.

---

## 7. Explicitly out of scope

- **The production nginx edge** — deferred to `CICD-AND-PACKAGING.md`.
- **TLS.** Until the edge lands, session cookies travel plaintext on a LAN. **D10 stops casual and
  unauthorised access; it does not stop an on-path attacker**, and per §3.2 it does not stop a
  compromised gateway achieving script execution in the shared origin. Both belong in SECURITY.md.
- Per-repo or per-workspace authorization; Kafka bus security (SASL/mTLS).

## 8. Verified non-issues

`spire-arch` cannot trip on `keycloak`/`oidc` (`CoreIsProviderNeutralTest.java:67` matches only
provider names); ADR-021 holds because all auth code lands in FSL service modules — if a shared module
is added, `LICENSING.md` and its `LICENSE` must follow; no Flyway migration is needed, so no
startup-ordering risk.

## 9. Effort

**M–L**, and L-leaning: the WebSocket lifecycle (§3.4) is a real subproject, and WS test
infrastructure does not exist. Not full L only because the production edge is out of scope.

## 10. Wording note

"The single origin that already exists" is loose: **two** dev origins exist (`:34000` and `:39285`) and
the service ports (34080–34082, 3928x) are directly reachable, which SMOKE-TEST uses. The plan means
"the browser reaches all three services through one origin". Direct port access must also be covered by
the same auth policies — it is not a bypass, but it is not one origin either.

---

## References

- [ROADMAP.md](ROADMAP.md) — item **D10** · [SECURITY.md](SECURITY.md) — the design this departs from
- [CICD-AND-PACKAGING.md](CICD-AND-PACKAGING.md) — owns the production edge
- [DECISIONS.md](DECISIONS.md) — ADR-015, ADR-021
