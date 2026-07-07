# GitHub webhook ingress does not exist (webhookSecret declared but unused)

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Medium |
| Location | `spire-scm-github/` (no `GitHubIngress`); `spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubConfig.java:15-16` |
| Found during | Full-project code review (4-agent) |
| Date | 2026-07-07 |

## Issue

Bitbucket has `BitbucketCloudIngress` implementing `ScmIngress` with constant-time HMAC
verification. GitHub has no `ScmIngress` implementation at all: no `X-Hub-Signature-256`
check, no webhook event translation. `GitHubConfig` declares a `webhookSecret` that nothing
consumes. GitHub currently works only via manual PR registration (pull mode).

## Risks

If a GitHub webhook route is ever wired to the gateway before this exists, payloads would be
processed unverified (spoofable review triggers, forged PR events). GitHub signs with
`X-Hub-Signature-256` (`sha256=` prefix over the raw body), so the Bitbucket ingress cannot
be reused as-is.

## Suggested Solutions

1. Implement `GitHubIngress` (mirroring `BitbucketCloudIngress`): `HmacSHA256` over the raw
   body compared against `X-Hub-Signature-256` via `MessageDigest.isEqual`, bot-account drop,
   event translation per `docs/SCM-MAPPING.md`. This is the "GitHub active mode" item already
   on `docs/ROADMAP.md`.
2. Until then: ensure no gateway route forwards GitHub webhooks (verified true as of this
   entry — the gateway only exposes `/webhooks/bitbucket`).
