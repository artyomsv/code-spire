# Credential proxy acceptance record

Harness reviews step 3, "the agent holds a placeholder, a model proxy adds the credential", is accepted on its live
proof ([step 3 plan](../superpowers/plans/2026-11-08-harness-reviews-3-credential-proxy.md), ADR-050, "Step 3", and
[RUN-TOPOLOGY.md](RUN-TOPOLOGY.md) §6B.1). On `artyomsv/spire-test`, a seat review, an API-key review and a long seat
review were posted while the agent held only placeholder credentials, and a key the vendor refused failed its run as
`CREDENTIAL_REJECTED` and marked its pool member.

The proof ran on 2026-11-09 on the dev stack built from PR #394 at `01dda15f`: `spire-model-proxy:latest` built
locally, the orchestrator, gateway, review worker and UI rebuilt, the run worker in dev mode from the branch. Defaults:
`spire.run.egress.mode=report` (global) and `spire.run.credential-proxy.mode=REVIEWS`. The repository's "Review with"
was Harness, Codex, model `gpt-6-astra`, effort high; the agent image ran codex-cli 0.156.1. The proof found one start
of the model proxy that was not seen listening; its fix is below.

## The four checks

| | 1. Seat review | 2. API-key review | 3. Long seat review | 4. Rejected key |
|---|---|---|---|---|
| Pull request | [#98](https://github.com/artyomsv/spire-test/pull/98) "TEST: loyalty bonus helper (credential proxy, seat)" | [#99](https://github.com/artyomsv/spire-test/pull/99), head `73824bdf` | [#100](https://github.com/artyomsv/spire-test/pull/100) "TEST: forty pricing rule tables" (40 files, 336 KB) | [#99](https://github.com/artyomsv/spire-test/pull/99), run 1 |
| Run | `run::github:artyomsv/spire-test:review-98-deda47e93248-8a4d83d4:1` | `run::github:artyomsv/spire-test:review-99-73824bdf84eb-cf92182e:1` | `run::github:artyomsv/spire-test:review-100-70750ab99989-fa625c9f:1` | `run::github:artyomsv/spire-test:review-99-517cb4764848-60948e1e:1` |
| Paid with | `SUBSCRIPTION` | `API_KEY` (`openai-api`) | `SUBSCRIPTION` | `API_KEY` (`factory-openai`) |
| Outcome | succeeded, 09:21:01–09:22:49 UTC | succeeded | succeeded in 2 min 24 s | failed `CREDENTIAL_REJECTED` |
| Review posted | `CHANGES_REQUESTED`, 1 inline comment (floating-point money, citing `.codespire`) | `APPROVED` (with a note on int money arithmetic) | `APPROVED`, one finding (floating-point discount methods) | none |
| Model proxy log | not captured (the container was removed before capture started) | 6 × `POST /v1/responses -> 200` | see below | 6 × `POST /v1/responses -> 401` |
| `run_egress` (port 443) | `chatgpt.com` allowed, only (enforced) | `api.openai.com` allowed, only | `chatgpt.com` only | `api.openai.com` allowed, only |

### 1. Seat review (#98)

`docker inspect` (values hidden) showed the agent on `spire-<hash>-agent` only, with no proxy variable and no
credential in its environment except `CODEX_SIGN_IN_FILE`, the placeholder. Decoded, the placeholder has `auth_mode`
`chatgpt`, access and id tokens carrying `spire_placeholder=true` with an expiry in 2100, an empty `refresh_token`,
`account_id` `00000000-0000-4000-8000-000000000000` and `last_refresh` `2100-01-01T00:00:00Z`.

The model proxy was on `…-agent` (alias `spire-model`) and `…-model`, with `SPIRE_MODEL_UPSTREAM=https://chatgpt.com`,
`SPIRE_MODEL_INJECTION=CHATGPT_SEAT`, `SPIRE_MODEL_PATHS_PREFIX=/backend-api/codex/,/backend-api/wham/`,
`SPIRE_MODEL_SECRET` (length 1696), a 36-character `SPIRE_MODEL_ACCOUNT` and `HTTPS_PROXY=http://spire-egress:8888`.
The egress proxy was on `bridge` and `…-model` (alias `spire-egress`), not on the agent network.

`run_egress` held no `auth.openai.com`: Codex accepted the placeholder `auth.json` and made no refresh call.

| Token type (`REVIEW`, `UNMETERED`, cost 0) | Count |
|---|---|
| `INPUT` | 15,526 |
| `CACHED_INPUT` | 67,456 |
| `OUTPUT` | 1,079 |
| `REASONING` | 168 |

### 2. API-key review (#99, head `73824bdf`)

The agent's environment held `SPIRE_MODEL_PLACEHOLDER=TEST-PLACEHOLDER` only: no `OPENAI_API_KEY` and no `codex login`.
Its argv set `-c model_provider="spire"` with `model_providers.spire.base_url="http://spire-model:8080/v1"`,
`env_key="SPIRE_MODEL_PLACEHOLDER"`, `name="spire"` and `wire_api="responses"`. The model proxy had
`SPIRE_MODEL_UPSTREAM=https://api.openai.com`, `SPIRE_MODEL_INJECTION=BEARER` and
`SPIRE_MODEL_PATHS_EXACT=/v1/models,/v1/responses`. The run,
`run::github:artyomsv/spire-test:review-99-73824bdf84eb-cf92182e:1`,
was paid with the `openai-api` credential (its `factory_run.harness_credential_id`), the key the operator added
after check 4.

| Token type (`METERED`) | Count | Millicents |
|---|---|---|
| `INPUT` | 18 | 36 |
| `CACHED_INPUT` | 67,247 | 13,449 |
| `CACHE_WRITE` | 17,407 | 43,517 |
| `OUTPUT` | 1,159 | 8,692 |
| `REASONING` | 1,911 | 14,332 |

### 3. Long seat review (#100)

The model proxy's log, with no path outside the declared prefixes:

| Line | Count |
|---|---|
| `POST /backend-api/codex/responses -> 200` | 11 |
| `GET /backend-api/codex/models -> 200` | 11 |
| `GET /backend-api/codex/models -> aborted` | 2 |
| `GET /backend-api/wham/accounts/check -> 200` | 2 |
| `GET /backend-api/wham/settings/user -> 200` | 1 |

Tokens (cost 0): `INPUT` 62,995, `CACHED_INPUT` 361,216, `OUTPUT` 2,065, `REASONING` 628, about 38k of context per
call. **No compaction happened.** Compaction through the same path is proven only by the 2026-11-07/08 spike, which
forced it with `model_auto_compact_token_limit`.

The two `aborted` lines are model-list reads in a run that succeeded: `aborted` covers the client (Codex) hanging up
mid-response as well as a vendor failure. RUN-TOPOLOGY.md §6B.1 now says so.

### 4. Rejected key (#99, run 1)

The stored key `factory-openai` was invalid at OpenAI. The run failed `CREDENTIAL_REJECTED` with the detail "the model
vendor refused the run's credential (6 model call(s) answered 401); then: exit 1, no output", and the pool member's
`rejected_at` became 2026-11-09 11:58:13 UTC. A cross-check ran the same image with a known-good key (the operator's
test key, never printed): `GET /v1/models -> 200`, so injection was correct and the key itself was refused. The
operator then added `openai-api` and erased `factory-openai`. This is the first live producer of
`CREDENTIAL_REJECTED` (docs/UNVERIFIED.md, A1).

## What the proof found

- **One model proxy start was not seen listening.** #99 run 2,
  `run::github:artyomsv/spire-test:review-99-4e2fc0369992-1b39c37e:1` (12:02:37–12:03:17 UTC), failed
  `RUNTIME_UNAVAILABLE` with "IllegalStateException: the model proxy did not listen within PT30S". The container had
  started at 12:02:46 and kept running with no output and no request until the orphan watchdog killed it at 12:04:45
  (exit 137, OOMKilled false); the unit was left for the watchdog, by design. It was not reproduced: the image listened
  in about 2 s in runs 1 and 3, and in about 3.4 s measured directly with the same limits (0.5 CPU, 192 MiB, read-only
  root). The JVM listens on IPv6 and the probe reads both `/proc/net/tcp` and `/proc/net/tcp6`. The host was busy with
  other stacks at the time. Cause unknown. **Fixed** on PR #394: the wait is 120 s, and a sidecar that never listens or
  exits first fails with its state, exit code, OOMKilled flag and the last 20 lines (at most 2 KiB) of its log
  (`SidecarStartFailure`).

## Final seat check on the merged code (#101)

After the review fixes, one more seat review ran on the final code of PR #394 (`fc71e84f`, merged as `a36424c0`):
[#101](https://github.com/artyomsv/spire-test/pull/101) "TEST: return fee helper (credential proxy, final seat
check)". The model proxy image, the dev stack and the run worker were rebuilt from that commit. The run succeeded in
1 min 29 s and posted an APPROVED review, at cost 0, with `chatgpt.com` the only host in the egress record. The
model proxy's log (route names, as the fixed code logs them):

| Line | Count |
|---|---|
| `POST /backend-api/codex/responses -> 200` | 6 |
| `GET /backend-api/codex/* -> aborted` | 2 |

So an ordinary seat review works on the final code: the header allowlist, the echo mask, the limits and the
route-only log were in the path and did not break it. This run did not make any of those guards act: no credential
was echoed, no limit was reached, and no path was refused. No model-list read finished in this run. A direct read through the same image with the operator's seat (values never printed),
`GET /backend-api/codex/models?client_version=0.156.1`, answered 200 with 480,260 bytes (chunked,
`application/json`) and logged `GET /backend-api/codex/* -> 200`. So the model list can pass the proxy whole. That
does not say why the two reads in the run aborted: `aborted` covers an upstream read failure or stall as well as a
client hanging up, and the log does not say which. Codex ending its own read fits the evidence; the cause was not
established. The list marks `gpt-6-astra` with `"prefer_websockets": true`, so
Codex may try a WebSocket first, which the model proxy refuses, and then fall back to HTTP. That is a guess: no
refused upgrade was seen in the log (docs/UNVERIFIED.md, the step 3 entry).

## Limits that remain

- **One model, one harness, one forge:** Codex `gpt-6-astra` on GitHub.
- **Compaction is not proven live** (the spike only), and a seat's `CREDENTIAL_REJECTED` has no live run.
- **Never observed:** a redirect, a `*.oaiusercontent.com` host, or a WebSocket upgrade through the model proxy.
- **No Claude Code subscription run**, by standing rule; Claude Code declares no credential route yet.
- **The start miss is unexplained.** Whether 120 s is enough on a busy host is unproven (docs/UNVERIFIED.md, the step 3
  entry).
- **The review of PR #394 changed the proxy after the four checks.** Response headers became an allowlist, `Location`
  is never passed, an encoded body is refused, every body is masked for an echo of the credential, requests and
  answers are bounded, and the log names a route (`/backend-api/codex/*`) or `<refused>` instead of the agent's path
  (the model call keeps its name). The four checks predate it, and their log excerpts show the earlier full paths. The
  final seat check (#101) ran on it and shows that an ordinary seat review still works. What the guards do when they
  act (an echoed credential, a reached limit or timeout, an encoded body) is proven against local fakes only
  (docs/UNVERIFIED.md, the step 3 entry).
