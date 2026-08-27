# The proxy buffer sizes are asserted by nothing

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-ui/nginx/default.conf.template` (`proxy_buffer_size` / `proxy_buffers` / `proxy_busy_buffers_size` / `large_client_header_buffers`); `deploy/helm/spire/tests/render.sh`, `deploy/e2e.sh` |
| Found during | PR #76 review (browser login to the packaged deployment) |
| Date | 2026-08-27 |

## Issue

Four directives in the nginx template exist because an operator session does not fit nginx's default
header buffers, and **no check anywhere reads any of them** — `grep -i buffer` over `render.sh` and
`e2e.sh` returns nothing. Every other rule in that file has an assertion in `render.sh`; these do not,
and the file now says so in a comment rather than leaving the omission to be discovered.

The failure they prevent is silent on both sides:

- **Response.** The OIDC callback returns the session as several large `Set-Cookie` headers (Quarkus
  chunks the encrypted JWT; a realm token carrying roles does not fit one cookie). Under the 4k/8k
  default nginx answers `502` with *"upstream sent too big header"* and the service logs nothing,
  because it answered correctly.
- **Request.** A browser concatenates every `q_session_chunk_*` cookie into ONE `Cookie:` header, so
  the same session comes back on a line the default caps at 8k and nginx answers a bare `400` before
  any service sees the request.

Neither shows up as an application error, and both look to an operator like "login is broken".

## Risks

Low today, and the reason is worth stating: the sizes are generous (16k header, 8×16k body, 4×32k
request line) relative to a Keycloak session, so the realistic regression is not "the numbers are
wrong" but "a future edit deletes them" — the same class as the header-inheritance defect this PR
fixed, which also broke nothing visible in dev.

What makes it hard rather than merely undone: the condition only exists against a **real chunked
session cookie**, which needs a completed login round-trip through a live identity provider.
`render.sh` reads files, and `e2e.sh` checks the `302` without ever following it. Asserting a number
is present would be theatre — it would pass on a value too small to work.

## Suggested Solutions

1. **Cheapest honest check:** have `e2e.sh` complete one real login against the bundled Keycloak
   (password grant is already there for tokens; the missing part is driving the browser-shaped
   authorization-code flow with a cookie jar) and assert the callback answers `302`, not `502`, and
   that a follow-up request carrying the resulting cookie jar is not answered `400`. That exercises
   both directions against real header sizes rather than asserting a literal.
2. **Weaker but immediate:** assert in `render.sh` that all four directives are present and above the
   image defaults. Catches deletion, not under-sizing. Worth doing only if 1 stays unbuilt.
3. Fold it into SMOKE-TEST Mode J, which already requires a real login — an operator confirming the
   dashboard loads is confirming this, just not repeatably.
