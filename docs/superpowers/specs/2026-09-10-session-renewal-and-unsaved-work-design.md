# The dashboard reloads itself every five minutes — analysis and fix

**Date:** 2026-09-10
**Status:** **§3 A is implemented** (config on all four services + `OidcSessionsAreRenewedTest`, both
mutation-verified). **B and C are not built** — the operator chose A alone. The live 15-minute check
in §5 step 6 has **not** been run: the dev stack on this machine belongs to another worktree
(`code-spire-worktrees/feat-software-factory`), so rebuilding it would have swapped somebody else's
running environment onto this branch. The claim is registered in `docs/UNVERIFIED.md` §B until it is.

**What C not being built leaves open:** a session that lapses for real — the realm's SSO idle
timeout, an identity-provider restart, a revoked session — still takes the whole window to a login
and still discards unsaved form input. A alone makes that rare rather than every five minutes; it
does not make it non-destructive.
**Reported as:** "code-spire UI refreshes automatically, it happened few times already when I was
editing some forms and lost the data."
**Scope:** OIDC session renewal on all four services, and the dashboard's response to a lapsed
session. No new tables, no new endpoints, no schema change.

---

## 1. The symptom, reproduced

The dashboard reloads itself roughly every five minutes. It comes back on the same screen, so
nothing on screen says anything happened — but every unsaved form field is empty.

Reproduced on the dev stack (`docker-compose.dev.yml`, UI at `http://localhost:39285`) on
2026-09-10, with authentication on and `spire-keycloak` running:

1. Opened `#/settings/general`.
2. Typed `777` into **Max changed files**. Did not save.
3. Waited.
4. At **15:50:56 UTC** the page navigated away and came back on `#/settings/general`. The field was
   empty again.

Instrumentation in the page (patched `fetch`, a `pagehide` listener, and a 15-second poll of the
field's value) recorded the sequence:

| Time (UTC) | Event |
|---|---|
| 15:47:25 | field value = `777` |
| 15:47:38 … 15:50:41 | field value = `777` (13 consecutive polls) |
| 15:50:56.107 | `GET /api/me` → **499** |
| 15:50:56.117 | `GET /api/me` → **499** |
| 15:50:56.159 | `GET /api/me` → **499** |
| 15:50:56.170 | `GET /gw/auth/login` → **499** |
| 15:50:56.203 | `GET /gw/auth/login` → **499** |
| 15:50:56.259 | `GET /wk/auth/login` → **499** |
| 15:50:56.280 | `GET /wk/auth/login` → **499** |
| 15:50:56.554 | **`pagehide`** — the window navigates |
| 15:51:02 | new document, same route, field value = *empty* |

Three `/api/me` calls in the same 60 ms are the three WebSocket close handlers firing together
(`/api/ws/reviews`, `/api/ws/attention`, `/gw/ws/webhook-attention`). `499` is
`AUTH_REQUIRED_STATUS` — the status a script-marked request gets instead of a redirect it cannot
follow (`spire-ui/src/auth.ts:38`).

The service logs give the cadence. Every one of the three services logs the same failure, at the
same second, every five minutes:

```
io.quarkus.oidc.runtime.OidcProvider  Verification of the token issued to client
spire-orchestrator has failed: The JWT is no longer valid - the evaluation time
... is on or after the Expiration Time (exp=...) claim value.
```

Observed at `15:23:56`, `15:28:56`, `15:33:56`, `15:38:56`, `15:43:56`, `15:45:55`, `15:48:56`,
`15:50:56` — in `spire-orchestrator-dev`, `spire-gateway-dev` **and** `spire-review-worker-dev`
alike. Each expiry is followed by a silent re-login that mints tokens expiring five minutes later,
so the cycle is self-sustaining and runs for as long as the tab is open.

## 2. Root cause

Ten steps, each one verified.

1. **`infra/keycloak/realm-spire.json` sets no token lifespan.** Keycloak's default access- and
   ID-token lifespan is therefore in force: **5 minutes**.
2. **Quarkus sets the session cookie's `Max-Age` to the ID token's lifespan.** The D10 phase-0 spike
   measured exactly this — `Max-Age=300` (`docs/D10-AUTH-PLAN.md:236`), and recorded it as
   *"confirms the session-lifetime problem is real and not theoretical"*.
3. **No service enables token refresh.** `quarkus.oidc.token.refresh-expired` appears in no
   `application.yml` and no `.env`; grep for `refresh-expired`, `refresh-token-time-skew`,
   `session-age-extension` and `token-state` across the repository returns nothing. Quarkus documents
   `refresh-expired` as **not enabled by default**, "because with the automatic renewal, the user,
   after authenticating once, may not be asked to re-authenticate for a very long time. Therefore an
   admin level decision may be required". `session-age-extension` defaults to 5 minutes but is inert:
   the reference states it "is effective only if the `token.refresh-expired` property is enabled".
4. **So at the ID token's `exp`, Quarkus invalidates the local session** and, per its own reference,
   "the user redirected to the OpenID Provider to re-authenticate. In this case, the user might not
   be challenged again if the OIDC provider session is still active."
5. **Quarkus also auto-closes every open WebSocket at that moment** — predicted in
   `docs/D10-AUTH-PLAN.md:167-168` ("**every socket dies every five minutes**") and now observed.
   All three of the dashboard's sockets close together, because all three sessions were minted by one
   chained login and share an `exp`.
6. **Each close handler asks `/api/me` why** (`useLiveReviews.ts:122`, `useAttention.ts:133`). The
   answer is **499**.
7. **`fetchMe()` maps every non-`ok` response to `null`** (`auth.ts:331`). `null` means *unknown*, not
   *signed out*.
8. **`needsLogin(null)` is `false` by design** (`auth.ts:370-372`: `me !== null && …`). So the branch
   written for "no session at all" — `goToFullLogin()` at `useAttention.ts:136` and
   `useLiveReviews.ts:127` — **is never taken on an ordinary expiry.** The real expiry falls through it.
9. **It falls into `ensureServiceSessions()`** (`useAttention.ts:144`), whose job is to notice a
   *sibling* prefix that has lapsed. It probes `/gw/auth/login` and `/wk/auth/login`; both answer
   **499**; both are recorded as lapsed; it calls `goToFullLogin()` (`auth.ts:318`).
10. **`startLogin()` assigns `window.location`** (`auth.ts:165`). Keycloak's SSO session is still
    alive — its defaults are 30 minutes idle, 10 hours maximum — so the identity provider
    re-authenticates without prompting and redirects back to `/`. The SPA boots from nothing;
    `takeReturnRoute()` (`App.tsx:118`) puts the operator back on the screen they were on.

**The reload is a silent re-login, and the trigger is the sibling-session probe, not the
dashboard's own session check.** That is an accident: the code path written to handle "the session
is gone" (step 8) is unreachable for the case it was written for, and the case is handled instead by
a helper written for a different purpose. The operator sees a page that reloads for no stated
reason and eats their typing.

### What is *not* the cause

- **Not Vite HMR.** `@vite/client` logged `connecting` / `connected` once and nothing further; no
  reload was preceded by an HMR message.
- **Not a service restart.** No Quarkus live-reload or container restart appears in the logs across
  the observed window.
- **Not `location.reload()`.** No code in `spire-ui/src` calls it; the only navigations are
  `window.location.assign` at `auth.ts:165` and `auth.ts:228`.

### Why this shipped

`docs/D10-AUTH-PLAN.md:344-346` lists the open item verbatim:

> **Logout and session lifetime.** Configure refresh/session-age extension against the ~5-minute
> default, and wire RP-initiated logout per service … **Or** record "expiry only, no logout in v1" in
> the ADR.

Logout was wired. The session-lifetime half was neither configured nor recorded — the item was
closed by its second half only, and the first half was never decided. This is the same shape as the
`refused` status and the nginx `location` trap in `CLAUDE.md`: an unhandled case defaulting into the
reassuring answer.

---

## 3. What to change

Three changes. **A** removes the cause. **C** makes the remaining, legitimate case non-destructive.
**B** is a dev-comfort change and is optional.

### A — Renew the session instead of bouncing the browser (backend, the actual fix)

Add to the `quarkus.oidc` block of **all four** services — `spire-orchestrator`
(`application.yml:39`), `spire-gateway` (`:40`), `spire-review-worker` (`:30`), `spire-run-worker`
(`:27`):

```yaml
quarkus:
  oidc:
    token:
      # Renew the session from the refresh token rather than sending the whole window
      # through the identity provider every time the ID token expires. Without this the
      # dashboard reloaded itself every five minutes and discarded whatever was typed.
      refresh-expired: true
      # Refresh BEFORE expiry, so the session cookie is never briefly absent.
      # Setting this alone also enables refresh-expired; both are set on purpose.
      refresh-token-time-skew: 60S
    authentication:
      # How long a session may go on being renewed before a real re-authentication.
      # Inert unless refresh-expired is on -- which is why it is written next to it.
      session-age-extension: 8H
```

**Three things must be verified, not assumed:**

1. **`refresh-expired` with `application-type: hybrid`.** The Quarkus reference says the option "is
   valid only when the application is of type `ApplicationType#WEB_APP`". All four services are
   `hybrid` (orchestrator `application.yml:18`). Hybrid is web-app + service, so the web-app leg
   should honour it — but this repository has been bitten before by a config that looked applied and
   was not (`CLAUDE.md`: the `${VAR}` with no default; the `ARG` that BuildKit ignored). **Measure it
   with a live 15-minute observation, not with a green test.**
2. **Cookie size.** The refresh token must be in the session for this to work; the default
   `token-state-manager.strategy=keep-all-tokens` keeps it. The D10 spike already measured the cookie
   **arriving chunked** with only two realm roles (`docs/D10-AUTH-PLAN.md:239-240`). Re-measure the
   chunk count and check it against the proxy buffer sizing already flagged in
   `techdebt/global/4-3-proxy-buffer-sizing-is-unverified-by-any-check.md`.
3. **The security trade-off is real and must be recorded.** After this change an operator who signs
   in is not challenged again for up to 8 hours, bounded by the realm's SSO Session Max. Keycloak's
   SSO Session Idle (30 minutes by default) still ends an idle session. Write it into ADR-022 and
   `docs/SECURITY.md`; do not let it arrive undocumented.

### B — Stop the socket churn in dev (optional)

Even with A, Quarkus closes each socket at the `exp` captured at its handshake, so three sockets
still reconnect every five minutes. After A those reconnects are silent and correct, so this is
comfort, not correctness. If taken: set `"accessTokenLifespan": 1800` in
`infra/keycloak/realm-spire.json`.

**Do not treat B as part of the fix.** A production realm belongs to the operator and may use any
lifespan; the dashboard has to tolerate five minutes regardless.

### C — Never take a whole window away from unsaved work (UI, defence in depth)

A session can still lapse for real: the Keycloak idle timeout, an identity-provider restart, a
revoked session. Today that is a silent whole-window navigation that destroys everything typed.
Two parts.

**C1 — `fetchMe()` must tell "signed out" apart from "unreachable".**

`auth.ts:328-335` collapses both into `null`, which is why step 8 above is unreachable. Change it to
translate an authentication failure into the answer it actually is:

```ts
export async function fetchMe(): Promise<Me | null> {
  try {
    const res = await fetch('/api/me', { headers: SCRIPT_REQUEST_HEADER });
    // A 499 or 401 from /api/me is not "no answer" -- it IS the answer. The endpoint is
    // deliberately public (application.yml: policy permit on /api/me), so the only way it
    // refuses is an expired session cookie presented alongside the request. Returning null
    // here made needsLogin() false for the one case it was written for, and the expiry was
    // then handled -- by whole-window navigation -- by the sibling-prefix probe instead.
    if (isAuthFailure(res.status)) {
      return { authEnabled: true, authenticated: false, user: '', roles: [] };
    }
    if (!res.ok) return null;
    return (await res.json()) as Me;
  } catch {
    return null;
  }
}
```

This alone does **not** fix the reload — it moves the trigger from
`ensureServiceSessions()` to the `needsLogin` branch, which also navigates. Its value is that the
code now describes what is happening, and C2 has one place to guard.

*Alternative considered and rejected:* exempt `/api/me` from the OIDC session check on the server
(a second tenant with authentication off). It is the more correct fix — a `policy: permit` path
should not answer 499 — but it adds a tenant to four services to change one status code, and C1 is
one function.

**C2 — a lapsed session must ask, not act, when work is unsaved.**

Add a tiny module-level registry beside `leavingForAuth` in `auth.ts`:

- `registerUnsaved(id): () => void` — a form calls it while dirty, calls the returned function when
  clean or unmounted.
- `hasUnsavedWork(): boolean`.
- `startLogin()` (`auth.ts:161`) consults it. When something is dirty it does **not** assign
  `location`; it raises a session-expired state instead and returns `false`.

The shell renders that state as a banner: *"Your session expired. Your unsaved changes are still
here. **Sign in again**"* — a real button, which calls a `force` variant of `startLogin`. The
attention panel already has an honest `down` state for the interval, and the "LIVE" chip in the
topbar already has somewhere to say so.

`startLogin` returning `false` is already the contract for "this call did not navigate"
(`auth.ts:88-90`), and `ensureServiceSessions` already handles a lost race
(`auth.ts:318`), so callers need no change.

**Not doing: restoring form values across the login.** `startLogin` already carries the route through
`sessionStorage` (`auth.ts:139`), and carrying the field values would be a small step from there.
It is the wrong step: the provider, LLM-provider and webhook forms hold API tokens and webhook
secrets, and this would write them to browser storage in clear. C2 keeps the page, so there is
nothing to restore.

---

## 4. Files touched

| File | Change |
|---|---|
| `spire-orchestrator/src/main/resources/application.yml` | A — `refresh-expired`, `refresh-token-time-skew`, `session-age-extension` |
| `spire-gateway/src/main/resources/application.yml` | A — same |
| `spire-review-worker/src/main/resources/application.yml` | A — same |
| `spire-run-worker/src/main/resources/application.yml` | A — same |
| `spire-ui/src/auth.ts` | C1 `fetchMe`; C2 registry + `startLogin` guard + session-expired state |
| `spire-ui/src/App.tsx` | C2 — render the session-expired banner |
| `spire-ui/src/components/SettingsGeneral.tsx` and the other settings forms | C2 — register while dirty |
| `spire-arch/src/test/…` | new: every service's OIDC block carries A |
| `spire-ui/src/auth.test.ts` | new cases for C1 and C2 |
| `infra/keycloak/realm-spire.json` | B, if taken |
| `docs/DECISIONS.md` (ADR-022), `docs/SECURITY.md`, `docs/D10-AUTH-PLAN.md` §7, `CLAUDE.md` gotchas, `docs/UNVERIFIED.md`, `techdebt/global/4-2-…`, `SMOKE-TEST.md` Mode J | records |

---

## 5. How this is proved fixed

`CLAUDE.md` says three milestones in a row shipped a feature that was green, documented and did not
work. This one is exactly that shape: **no unit test in this repository can observe a five-minute
token expiry in a real browser.** The live check is the verification; the tests only stop it
regressing.

1. `./gradlew testFast testServices` — green, 0 failures.
2. `cd spire-ui && npx vitest run && npx tsc --noEmit` — green, silent.
3. **New arch test** (`spire-arch`, alongside `PureModulesAreFrameworkFreeTest`): parse every
   `*/src/main/resources/application.yml` that declares `quarkus.oidc.auth-server-url`, and assert
   each carries `token.refresh-expired: true` and a non-zero `authentication.session-age-extension`.
   **Derive the service list by scanning**, do not hard-code it — the same reason
   `DockerTestsAreSerialisedTest` scans (`CLAUDE.md`).
4. **New vitest cases** in `auth.test.ts`: `fetchMe` returns `authenticated: false` on 499 and on
   401, and `null` on 500 and on a thrown fetch; `needsLogin` is true for the first; `startLogin`
   does **not** touch `location` while an unsaved registration is open, and does once it is released.
5. **Mutation-verify each guard** (`CLAUDE.md`): set `refresh-expired: false` in one service and
   confirm **exactly** the new arch test fails; make `fetchMe` return `null` on 499 again and confirm
   exactly the new C1 case fails; drop the `hasUnsavedWork()` check and confirm exactly the C2 case
   fails.
6. **The live check, and it is the one that decides** — add as `SMOKE-TEST.md` Mode J check 11:
   bring up the dev stack with authentication on, open `#/settings/general`, type a value into
   **Max changed files**, and leave the tab untouched for **15 minutes** (three expiry periods).
   Pass = the value is still there, the topbar still reads LIVE, and
   `docker logs spire-orchestrator-dev | grep "no longer valid"` shows no new lines in that window.
   Fail = anything else.
7. Until step 6 has been run against a real browser, this fix belongs in `docs/UNVERIFIED.md`.

---

## 6. What this measured that was previously unknown

`techdebt/global/4-2-websocket-behaviour-under-auth-is-unmeasured-from-a-browser.md` asks two
questions. This session answers half of one and neither of the other.

- **Answered:** what the dashboard *does* on an expiry, from a real browser — the `499` chain and the
  whole-window navigation in §1 are the first browser-side measurement of it.
- **Still open:** the actual WebSocket `close` code and `wasClean` flag. The probe that would have
  recorded them was installed after React had already opened the sockets, so it caught the close
  handlers' `/api/me` calls but not the close events themselves. Re-measure during the Mode J run.
- **Still open:** whether the `quarkus-http-upgrade#<header>#<value>` sub-protocol carrier accepts an
  arbitrary header. Untouched.
