# Internet-facing webhook endpoints have no tightened request-body size limit

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-gateway` — `GitHubWebhookResource` / `GitLabWebhookResource` / `BitbucketWebhookResource` (`byte[] body`); `spire-gateway/src/main/resources/application.yml` |
| Found during | Security review of the keyed webhook edge (2026-07-16) |
| Date | 2026-07-16 |

## Issue

The webhook resources accept `byte[] body`, which RESTEasy Reactive buffers fully in memory,
and the signature/token is only verified *after* the whole body is read. No
`quarkus.http.limits.max-body-size` is set for the gateway, so it falls back to the Quarkus
default (10M). These are the only internet-facing, pre-authentication endpoints in the system.

## Risks

A flood of large-body POSTs to `/webhooks/{provider}/{key}` forces the gateway to buffer up to
10M each before rejecting them, a memory-pressure DoS vector. Bounded by the default, so not
unbounded — but 10M is far above any legitimate webhook payload (metadata only; the diff is never
in the payload, ADR-011).

Not a regression: the removed legacy `/webhooks/bitbucket` edge had the identical exposure.

## Suggested Solutions

1. Set a tighter `quarkus.http.limits.max-body-size` for the gateway (e.g. 1–2M). Verify against
   a large-PR webhook payload first (GitHub caps webhook deliveries at 25M, but real PR-event
   payloads are metadata and typically well under 1M) so a legitimate delivery is never rejected.
2. Consider a per-path limit if the registry CRUD API ever needs a larger body than the webhook
   edges (it does not today).
