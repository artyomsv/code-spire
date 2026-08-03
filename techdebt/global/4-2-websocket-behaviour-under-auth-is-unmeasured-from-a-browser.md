# Two WebSocket-under-auth behaviours were never measured from a real browser

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-ui/src/useLiveReviews.ts`, `spire-ui/src/hooks/useAttention.ts`; the four `@WebSocket` endpoints across the orchestrator and gateway |
| Found during | D10 phase-0 spike (docs/D10-AUTH-PLAN.md §4) |
| Date | 2026-08-03 |

## Issue

The spike answered its six questions against a running service, but two of them only as far as `curl`
reaches. Both need a real browser or a WebSocket client, and this repository has neither in its test
suites (there are **zero** WebSocket client tests — the sockets are covered through their
broadcasters and through UI fakes).

**1. What close code does a browser actually see** when a handshake is refused or a session expires?
Measured via `curl`, an unauthenticated upgrade is answered with a `302` the browser cannot follow.
What the `onclose` event then reports — code, `wasClean`, whether it is distinguishable from a network
drop — was never observed.

**2. Does the sub-protocol carrier work for a non-`Authorization` header?** Quarkus WebSockets Next
documents `quarkus-http-upgrade#<header>#<value>` smuggled through `Sec-WebSocket-Protocol`. If it
accepts an arbitrary header, the script marker could ride the handshake and the `499` contract would
apply to sockets as it does to `fetch`. Untested.

## Risks

Low, because the shipped design does not depend on either answer.

The reconnect logic deliberately asks `/api/me` on every close rather than interpreting the close
event, precisely because the close code was unknown — so it behaves correctly whatever the code turns
out to be. That makes these unknowns a matter of refinement rather than correctness: knowing the code
could avoid one round-trip per close, and a working sub-protocol carrier could replace the `/api/me`
question entirely.

The residual risk is that a *future* change reads the close code and assumes something untrue about
it.

## Suggested Solutions

1. **Measure both during the next live pass** (SMOKE-TEST **Mode J**, check 10 already requires
   letting a session lapse with the dashboard open — reading the close code off the console at that
   moment costs nothing).
2. **Build the missing WebSocket client test infrastructure**, which would also let the socket
   boundary be tested rather than reasoned about. Note that `@TestSecurity` is not known to reach a
   real upgrade handshake, so this may need real cookies.
3. If the sub-protocol carrier does accept an arbitrary header, reconsider the reconnect design —
   but scope `supported-subprotocols` narrowly, since it is a header-injection surface.
