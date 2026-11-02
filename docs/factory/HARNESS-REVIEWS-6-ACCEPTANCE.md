# Re-reviews on a harness acceptance record

Harness reviews step 6, "re-reviews on a harness", is accepted on its live proof (Task 8 of the
[step 6 plan](../superpowers/plans/2026-11-02-harness-reviews-6-re-reviews.md), the
[harness reviews design](../superpowers/specs/2026-10-30-harness-reviews-design.md) and ADR-050, "Step 6"). On
`artyomsv/spire-test`, one TEST pull request and three commits proved a first review that cites a requirement found
only in its linked issue, a re-review that closes one finding, keeps two open and reports one new, and a rename that
keeps the open findings at the new path. The human steps were: open the issue, open the pull request, push two
commits.

The proof ran on 2026-11-02 on the dev stack built from PR #370 at `4a02f003`: the orchestrator, the review worker,
the gateway and the UI rebuilt, the run worker in dev mode from the branch. The repository's "Review with" was
Harness, Codex, model `gpt-6-astra`; Pay with was a Codex subscription seat. The egress proxy ran in report mode.
The reviews carried the verdict `ReviewVerdictDecision` decided (ADR-047). The proof found no defect. PR #370 is not
merged.

## The setup

- **Issue** [spire-test #96](https://github.com/artyomsv/spire-test/issues/96) states "Requirement
  TEST-RULE-COUPON-50: a coupon may never take more than 50 percent off a price". No file of the repository states
  it.
- **Pull request** [spire-test #97](https://github.com/artyomsv/spire-test/pull/97), "Implements #96", adds
  `src/main/java/com/coupon/Coupon.java`: `apply` takes the percent with no cap, and `total()` loops from index 1.

## The three rounds

| | Round 1: first review | Round 2: re-review | Round 3: rename |
|---|---|---|---|
| Head | `628e11f` (the opened PR) | `f4548a2`: the loop starts at 0; adds `average()`, which divides by the length | `8dcc6ec`: renames `Coupon.java` to `Coupons.java`, the class name only |
| Run | `run::github:artyomsv/spire-test:review-97-628e11f19d9e-31504680:1` | `run::github:artyomsv/spire-test:review-97-f4548a29a621-f555c15d:1` | `run::github:artyomsv/spire-test:review-97-8dcc6ec9045f-ffff52f7:1` |
| Kind | `REVIEW`, about 50 s | `RECONCILE`, about 1 minute from push to posted review | `RECONCILE` |
| GitHub review | **APPROVED**, 3 inline findings | **APPROVED**, "Reconciliation: 1 closed · 2 still open.", 1 new inline finding | **APPROVED**, "Reconciliation: 0 closed · 3 still open.", no new inline comment |
| Egress hosts (port 443) | `chatgpt.com` | `chatgpt.com` | not recorded |

**Round 1** is a first review with assembled context. The agent read the issue from `/inputs/context.md` and posted
three findings:

- MAJOR `Coupon.java:11`: "The discount uses percent without the 50% cap required by issue #96
  (TEST-RULE-COUPON-50)";
- MAJOR `Coupon.java:17`: the loop starts at index 1, so `total()` drops the first price;
- MINOR: a null input is not rejected, as the target branch's `.codespire` asks.

With no open blocker, `ReviewVerdictDecision` approves. Charges, under the call ref
`review::artyomsv/spire-test#97|628e11f19d9e18dbd56ff38807e6463cf800ff16|REVIEW`, `UNMETERED`, cost 0:

| Token type | Tokens |
|---|---|
| `INPUT` | 17,617 |
| `CACHED_INPUT` | 79,744 |
| `OUTPUT` | 1,647 |
| `REASONING` | 76 |

**Round 2** is a re-review on a harness. One `RECONCILE` run judged the three prior findings and reported one new:

| Prior finding | Verdict | Thread |
|---|---|---|
| Loop from index 1 (`Coupon.java:17`) | closed | replied "Fixed in `f4548a2`. The loop now starts at index 0, so total() includes the first price…" and resolved |
| No 50% cap (`Coupon.java:11`) | still open | open |
| Null input not rejected | still open | open |
| New: `average(new long[0])` divides zero by zero (MAJOR, `Coupon.java:25`) | new finding | new inline thread |

One charge set, under `review::artyomsv/spire-test#97|f4548a29a6217cdee1ca74705b90726e171e331a#run2|RECONCILE`,
`UNMETERED`, cost 0, and none under `…#run2|REVIEW`:

| Token type | Tokens |
|---|---|
| `INPUT` | 19,940 |
| `CACHED_INPUT` | 87,424 |
| `OUTPUT` | 1,546 |
| `REASONING` | 135 |

**Round 3** proves the rename remap on a harness. The three open findings were matched at `Coupons.java`: no thread
was closed, none was reported again, and no duplicate was posted.

## What the proof found

No defect. Each expectation of the plan's Task 8, steps 3 to 5, held: the linked issue reached the agent, the fixed
finding was replied to and resolved, the untouched ones stayed open with no reply, the new bug is a new finding, the
re-review charged one `RECONCILE` set and no `REVIEW` set, and the rename re-reported nothing.

## Limits that remain

- **One model and one harness:** Codex `gpt-6-astra` on a seat, one pull request.
- **Paths not proven live** (docs/UNVERIFIED.md, the step 6 entry): a re-review whose prior head is unreachable (the
  compare is null), a finish fault dead-lettered and replayed, a stale round's failure or prepared message, a seat
  refused during a re-review, a re-review on an API key, an over-cap answer, a fork, and replies (`FOLLOWUP`, step 7).
- **A dead-lettered harness message keeps the context in plain text** in `dlq_entry.payload`, and a redelivery funds
  the run twice (docs/UNVERIFIED.md, the step 6 entry).
