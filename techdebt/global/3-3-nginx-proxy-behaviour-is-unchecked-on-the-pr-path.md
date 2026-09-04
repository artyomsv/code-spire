# A change to the dashboard's reverse proxy reaches master with its behaviour unchecked

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `.github/workflows/docker.yml`, `.github/workflows/e2e.yml`, `spire-ui/Dockerfile`, `spire-ui/nginx/default.conf.template` |
| Found during | Triaging dependabot #97 (`nginx-unprivileged` 1.30-alpine → 1.31-alpine), 2026-09-04 |
| Date | 2026-09-04 |

## Issue

`spire-ui/nginx/default.conf.template` is a security control, not a convenience. ADR-022 scopes each
service's session cookie to its own URL path, and cookies scope by host **and** path — so that
isolation exists only while all four services answer on one origin, which in a packaged run is this
file. `CLAUDE.md` records three rules in it as load-bearing, and each has already broken once.

**Nothing on the pull-request path exercises any of them.**

- `docker.yml` runs on a PR touching a Dockerfile, and answers exactly one question: does the image
  still build. It never starts the container.
- `deploy/e2e.sh` is the only thing that checks the proxy's *behaviour* — WebSocket upgrade through
  nginx, a token minted for one service refused by another, the `redirect_uri` origin. It runs from
  `e2e.yml` (nightly cron) and `release.yml`. Neither is a PR trigger.

So a change to the nginx base image or the template is merged on the strength of "it builds", and
the first behavioural signal is a nightly run against master — or an operator.

This is not hypothetical. `render.sh`'s nginx invariants were added precisely because a header rule
regressed, and the guard as first written passed the regression it was for. The class of failure
that matters here is the one `CLAUDE.md` already names: `X-Forwarded-Proto` deriving from `$scheme`
breaks login **only** behind a TLS-terminating Ingress, where a plaintext compose run stays green.

## Risks

- A base-image bump or a template edit that breaks header forwarding, the `/webhooks` route ordering,
  or the WebSocket upgrade lands on master and is published as `:edge` before anything notices.
  Symptoms are an operator who cannot log in, or every SCM delivery answering 405 with no review
  starting — both of which look like an outage rather than a merge.
- It makes otherwise routine dependency updates expensive to judge. #97 was declined partly because
  its behavioural risk could not be measured cheaply, which is a cost paid on every future bump of
  that base.

## Suggested Solutions

1. **Run `deploy/e2e.sh` on pull requests that touch the proxy** — the same path filter `docker.yml`
   already uses (`**/Dockerfile*`, `spire-ui/nginx/**`), so nothing runs on a PR that cannot affect
   it. This is the honest fix and the expensive one: the script needs the packaged stack plus an
   identity provider, so the job is several minutes even warm.
2. **A cheap subset first.** Start the built `spire-ui` image alone with stub upstreams and assert
   the rules that need no IdP: `/healthz` answers, `/webhooks/...` routes to the gateway upstream
   rather than the SPA fallback, an upstream-supplied `X-Forwarded-Proto` survives, and a
   `Connection: Upgrade` request reaches the upstream as an upgrade. That covers the three rules
   `CLAUDE.md` calls load-bearing without standing up Keycloak.
3. **Do nothing and accept it**, but say so where a reader meets the decision — the `docker.yml`
   header comment reasons carefully about *why the build* runs on a PR and is silent about the fact
   that nothing else does.
