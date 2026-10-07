# M3.5 acceptance record

M3.5, "one ticket to a build", is accepted on its live proof (part P of the
[M3.5 design](../superpowers/specs/2026-09-16-factory-m35-one-ticket-to-a-build-design.md)). On
`artyomsv/spire-test`, in a repository with build defaults, a ticket with its own acceptance criteria
and the `spire:assisted` label produced an open plan gate; approving it produced one held build. The
human steps were write, label and approve. The build setup is saved once per repository, not per
item.

The proof ran three times. The design asks for two runs on the same model; run 2 used another model
because the build setup was changed before its ticket was prepared, so run 3 repeats the API-key run
on the model of run 1. Run 2 is kept because it found a billing defect.

## The three runs

| | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Ticket | spire-test #37 | spire-test #38 | spire-test #39 |
| Date (UTC) | 2026-09-27 | 2026-09-27 | 2026-10-07 |
| Paid with | Codex subscription seat | API key | API key |
| Model / thinking level | gpt-6-astra / high | gpt-6-sol / medium | gpt-6-astra / high |
| Plan gate opened | 00:17:36 | 20:11:38 | 18:34:03 |
| Run started / agent started | 00:23:44 / 00:23:59 | 20:12:41 / 20:12:53 | 18:37:56 / 18:38:09 |
| Active agent time | 52 s | 43 s | 43 s |
| Run status | `awaiting_delivery` | `awaiting_delivery` | `awaiting_delivery` |
| Item stop | `verify / capability_unavailable` | `verify / capability_unavailable` | `verify / capability_unavailable` |
| Checkpoint head | `f121643` | `0990041` | `7f387ee` |
| Change | `docs/factory-smoke-test.md`, 9 lines | same file, byte-identical | same file, byte-identical |

The stop at `verify / capability_unavailable` is the expected end of M3.5: verify, the pull request
and land are M4. Nothing was pushed and no pull request was opened.

## Tokens and cost

Charge lines from `llm_charge`, per run. Rates are the ones the operator entered for each model.

| Token type | Run 1 tokens | Run 2 tokens | Run 3 tokens | Run 3 cost (millicents) |
|---|---|---|---|---|
| INPUT | 4,103 | 14,828 † | 15 | 30 |
| CACHED_INPUT | 69,888 | 108,240 | 52,181 | 10,436 |
| CACHE_WRITE | — | 14,801 | 13,883 | 34,707 |
| OUTPUT | 730 | 1,130 | 853 | 6,397 |
| REASONING | — | 253 | 16 | 120 |
| **Total** | **74,721** | **139,252 †** | **66,948** | **51,690** |
| Pricing | `UNMETERED`, cost 0 | `METERED`, 19,734 † | `METERED`, 51,690 | |

† Recorded before the fix below: run 2's 14,801 cache-write tokens were counted twice, once in INPUT.
Its real input was 27 tokens and its real cost about 13,814 millicents. The recorded lines were left
as they are.

What each run proves against the M3.5 exit criterion:

- **Run 1 (subscription):** real token counts and a cost recorded as an asserted zero (`UNMETERED`),
  with no per-token spend.
- **Run 3 (API key):** a known cost in millicents, and no `run_usage_unknown` stop.
- Both ran the same model and thinking level, and produced the same change.

## What the proof found

**Codex cache writes are part of input** (fixed in PR #182). Run 2 was the first run to report a
non-zero `cache_write_input_tokens`. The Codex session log in the agent's home gave `total_tokens`
equal to input plus output, and one turn's `input_tokens` 14,804 was cached 14,616 + cache write 185
+ 3 plain. The adapter had read the cache write as additional to input, as Anthropic reports cache
creation, and billed those tokens twice: run 2's cost was recorded about 43% above what it was.
Run 3 ran on the fixed adapter: INPUT 15, CACHE_WRITE 13,883, with no overlap.

**Operator screens** (PRs #179–#181). The first subscription build walked the operator through
screens that needed fixing: a harness key could not be deleted, a build setup refused to save
without saying which field was missing, a ticket's arrival by the scanner was not explained, the
work item page did not refresh, and the model dialog did not fit a laptop window. Each is fixed.

## Limits that remain

- **No verify, pull request or land.** All three runs stop before publication. M4 owns these.
- **The subscription run is one run.** The open questions in `docs/UNVERIFIED.md` stay open: two
  builds on one seat at the same moment, a run that outlives the access token, and whether
  `tokens.account_id` is stable across sign-ins.
- **Run 1 reported no cache write.** Whether a subscription run reports cache writes at all is not
  established by one run.
- **The run's own token total is not cross-checked.** The `--json` stream the adapter reads carries
  no total; the cache-write reading was settled from the session log, which the adapter does not read.
