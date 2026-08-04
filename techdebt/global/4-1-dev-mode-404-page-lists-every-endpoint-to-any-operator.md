# Quarkus's dev-mode 404 page lists every endpoint to any signed-in operator

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Trivial |
| Location | Any unmatched path on a service running `quarkusDev` (e.g. `/api/does-not-exist`) |
| Found during | Live Mode J pass against the shared Keycloak, 2026-08-04 |
| Date | 2026-08-04 |

## Issue

In dev mode Quarkus answers an unmatched path with its "resources overview" page — a rendered list of
every JAX-RS endpoint in the service, method by method. On the orchestrator that includes
`/api/providers`, `/api/dlq` and `/dev/simulate-pr`.

It is served to **any authenticated caller**, so a `spire-viewer` — who is refused every configuration
read — can obtain the full inventory of what exists by requesting a path that does not.

Found by walking into it: a login page left open past the OIDC state cookie's five-minute life produced
a callback the framework declined, which fell through to routing and rendered this page. **That
fall-through is fixed** (`AuthResource.staleCallback` and its two siblings now answer `303` to `/`), so
the accidental route in is closed. What remains is the general case — any typo, any stale bookmark.

## Risks

Low, on three counts, and none of them is "it doesn't matter":

- **Dev-mode only.** The handler is `io.quarkus.vertx.http.runtime.devmode.ResourceNotFoundHandler`;
  the `devmode` package is Quarkus's convention for dev/test-only runtime, so a production build does
  not serve it. Verified by the class's location, *not* by running a prod jar — if that matters to a
  future reader, build one and check.
- **Never anonymous.** `/api/*` is `authenticated`, so an unauthenticated request is answered with a
  redirect to the identity provider. Confirmed live: `302`, no listing.
- **Route names only.** No payloads, no configuration values, no secrets.

The real exposure is that the containerized dev stack is the auth test bed, and it is where an
operator would first be given a viewer role. Treating that stack as shared would hand a viewer the
API surface.

## Suggested Solutions

1. **Accept and document** (current position). The stack is a workstation tool; production does not
   serve the page. This entry is that documentation.
2. Run the auth test bed in a **prod build** instead of `quarkusDev` when the point is to exercise the
   boundary rather than to edit code. Costs the live-reload loop, which is why it is not the default.
3. If Quarkus ever exposes a switch for the dev not-found page, set it — there was none at 3.37.1.

Not worth a custom `ExceptionMapper` for `NotFoundException`: it would mask the dev page's genuine
usefulness while editing, to remove a disclosure that does not exist in the deployed artifact.
