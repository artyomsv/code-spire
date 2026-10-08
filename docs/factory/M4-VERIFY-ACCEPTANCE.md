# M4 verify acceptance record

M4 slice 1, "verify the held build with the operator's checks", is accepted on its live proof (Task 17
of the [M4 verify plan](../superpowers/plans/2026-10-07-factory-m4-verify.md), the
[design](../superpowers/specs/2026-10-07-factory-m4-verify-design.md) and ADR-046). On
`artyomsv/spire-test`, three tickets proved the three outcomes a check can have: passed, failed and then
retried, and unverified. Every build was paid by a Codex subscription seat, at cost 0. The human steps
were write, label, approve the plan, and answer a verify gate where one opened.

`spire-test` has no build, so the checks are honest file checks (`test -f`, `grep -q`) and one tool check.
They prove the verify machinery, not a test suite.

The proof ran on 2026-10-08 on the dev stack, built from PR #184. A first pass (#40, #41) found six
defects before a clean end-to-end run was possible; they are listed under "What the proof found".

## The three runs

| | Run 1: passed | Run 2: failed, then retry | Run 3: unverified |
|---|---|---|---|
| Ticket | spire-test #43 | spire-test #45 | spire-test #47 |
| Checks | `test -f README.md`, `test -f docs/verify-review.md` | `test -f README.md`, `grep -q 'Owner: TEST-team' docs/verify-retry.md` | `test -f README.md`, `cargo --version` |
| Paid with / model | seat / gpt-6-astra | seat / gpt-6-astra | seat / gpt-6-astra |
| Build head | `dcaaf206` | `306d1e1c`, then `d3aab6ca` | `1f6a6fda` |
| Verify | PASSED | FAILED, then PASSED | UNVERIFIED, `tool_missing` |
| Verify gate | none | Retry build | Stop |
| Pull request | #44, marked ready by itself | #46, both commits | none |
| Review | `review::artyomsv/spire-test#44`, 0 findings, on `dcaaf206` | `review::artyomsv/spire-test#46`, 1 finding (not a blocker), on `d3aab6ca` | none |
| Item end | `land / waiting_approval` | `land / waiting_approval` | `verify / stopped / verify_stopped_by_operator` |

**Run 1** went from the plan approval at 11:59 UTC to the land gate at 12:02 with no human step.

**Run 2** proves retry from the checkpoint. The first build wrote only the ticket's line, so the `grep`
check failed. The operator chose Retry build. The second build started from the failed checkpoint: its
commit `d3aab6ca` has parent `306d1e1c`, and it added `Owner: TEST-team`, which it could only learn from
the failed check in its prompt. The retry passed, and PR #46 carries both commits.

**Run 3** proves that a missing tool is not a failure. The screen showed Unverified, reason tool missing,
`cargo --version` exit 127 with `/bin/sh: 1: cargo: not found`, and no tick. The operator chose Stop.

The identifiers:

| Run | Work item id | Factory run id(s) | Verify attempt id(s) |
|---|---|---|---|
| 1 (#43) | `work-v1-1f46f092456b710ab3420d7aa1e79c17e749f8a7816e2a30b33777450e5b762e` | `run::github:artyomsv/spire-test:work-349cba50-b2e7-440a-8b4b-b59ca07357a4:1` | `f129aa0a-6317-4274-a9cd-ae170c7df789` |
| 2 (#45) | `work-v1-b037ad7044ade799da30d99d90babe94f50810be6a968e6608188046b152f05c` | `…work-ccdd7da2-05f6-4b12-a840-99c42305f2c6:1`, retry `…work-069f8276-c803-4174-88dc-66cca3b319f8:1` | `1c4bb000-1c8a-433e-a575-d50a8530016b`, `cbcfc4ed-f532-44ac-a923-782c91253d62` |
| 3 (#47) | `work-v1-731aa5e374e77aa7fdb8dc024cf92b37ff454e643e1a5cf65c911d6e0e3c8407` | `…work-ddd5da43-5af6-4f85-9d24-3962cc1a7f97:1` | `a5067c3d-9b5e-4e79-bbe8-e4f3d24b0525` |

| Run | Gate | Id | Answer |
|---|---|---|---|
| 1 | plan | `9665df6e-3272-4b7b-9093-8649e4675369` | approved |
| 1 | land | `0206d5ea-b600-4914-bc88-212df85f8492` | approved; land is not built, see below |
| 2 | plan | `ea4e6ad0-f70a-43ea-aa22-5833bfd96ff5` | approved |
| 2 | verify | `44356741-fabe-4c8d-bbd9-e2a883d2e7b0` | Retry build |
| 2 | land | `7fe2f899-234e-4416-9c43-e8c7458c7dff` | open |
| 3 | plan | `e978f753-48a7-41ef-a1bb-f99f3b762377` | approved |
| 3 | verify | `756858d3-32cb-46a2-aa76-ce6ea885fce1` | Stop |

## Tokens and cost

Every line is `UNMETERED` at cost 0: the seat paid.

| Run | INPUT | CACHED_INPUT | OUTPUT | REASONING |
|---|---|---|---|---|
| 1 (#43) | 3,368 | 55,040 | 365 | — |
| 2 (#45) build 1 | 3,360 | 55,040 | 358 | — |
| 2 (#45) retry | 5,630 | 52,992 | 439 | 77 |
| 3 (#47) | 5,466 | 52,992 | 370 | — |

The retry's input is larger than the first build's by about 2,300 tokens: the failed check and its
output tail in its prompt.

## What the proof found

The first pass (#40, #41, PR #42) stopped six times. Each stop was fixed with a test on PR #184 and the
pass continued:

1. **A seat expired after 10 days while the screen said Ready.** The agent's copy of a sign-in has no
   refresh token by design, and nothing renewed the access token. `SeatRenewal` now renews a seat 3 days
   before expiry with the vendor CLI's own client, under an advisory lock and a compare-and-set; a refusal
   marks the seat "Sign in again". The first live renewal ran at 09:31:48 UTC (`0d794a10`, `319af176`,
   `98c78838`).
2. **A seat build with no token counts blocked its item for ever** at `run_usage_unknown`. A seat is not
   billed per token, so its spending is now 0, not unknown (`38591483`). #40 kept its old mark and was
   replaced by #41.
3. **The reviewer skipped the factory's draft PR**, so the review step waited for ever. The review step
   now marks a draft ready for review (GitHub GraphQL `markPullRequestReadyForReview`); GitLab and
   Bitbucket refuse with `ready_for_review_unsupported` (`ac4b6867`).
4. **The reviewer's author allowlist skipped the factory account's PR,** at two separate checks. A PR whose
   author is the repository's factory account, matched by stable id, is now reviewed (`feab0f01`,
   `2cce42dd`).
5. **A running or failed review read as "the review and its posted result do not both cover this build".**
   The item now says `review_in_progress` or `review_failed` (`90dbc4a9`).
6. **The repository rules reached the reviewer twice** whenever the PR linked a ticket, which every
   factory PR does. The rules provider is now first-level only (`80144d45`).

Outside PR #184: the Refresh button did not ask the tracker to scan (PR #195), and the review model
`gpt-6-luna` was set to `max_tokens`, which OpenAI refuses for it; the operator moved the review to the
Anthropic model.

## Limits that remain

- **Land is not built.** Approving land on #43 recorded the answer and changed nothing on GitHub. The
  next M4 slice owns merging.
- **Readmit after an approved land rebuilds delivered work.** The operator readmitted #43 after
  approving land, and it opened a new generation at the plan gate, which would build and propose again.
  The screen offers it as a next step; it should not, for an item whose PR is delivered and reviewed.
- **The review is a comment, not a GitHub review.** GitHub shows "No reviews" and no reviewer. The
  operator wants a real review (Approve or Request changes), designed after this slice.
- **A failed review does not name the model it tried;** "Model usage" lists only recorded calls.
- **A superseded run looks waiting.** #45's first run and #47's run stay `awaiting_delivery`.
- **A seat's 401 was reported as `MODEL_UNAVAILABLE`** (#40's first build), which named the wrong cause.
- **Marking a draft ready works on GitHub only.**
- The limits in `UNVERIFIED.md`'s M4 verify entry other than "no live verify" still stand: no egress
  limit for checks, `tool_missing` inferred from exit codes 126 and 127, and the new image clauses at
  Mode S.
