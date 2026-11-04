# Replies on a harness acceptance record

Harness reviews step 7, "replies on a harness", is accepted on its live proof (Task 6 of the
[step 7 plan](../superpowers/plans/2026-11-03-harness-reviews-7-replies.md), the
[harness reviews design](../superpowers/specs/2026-10-30-harness-reviews-design.md) and ADR-050, "Step 7"). On
`artyomsv/spire-test`, two replies in one finding thread were each answered by one `FOLLOWUP` run, posted in the
thread and charged once per turn. The human steps were: two replies in the thread.

The proof ran on 2026-11-03 on the dev stack built from PR #371: the orchestrator and the review worker rebuilt, the
run worker in dev mode from the branch. The repository's "Review with" was Harness, Codex, model `gpt-6-astra`; Pay
with was a Codex subscription seat. The egress proxy ran in report mode. The proof found one defect: the answer named
its author as plain text, which notifies nobody. It was fixed in `b74e02ac` before the second turn.

## The setup

[spire-test #97](https://github.com/artyomsv/spire-test/pull/97), reviewed on the harness in step 6, has the open MAJOR
finding "The discount uses percent without the 50% cap required by issue #96 (TEST-RULE-COUPON-50)" at
`src/main/java/com/coupon/Coupons.java:11`, thread `4446620058`, at head `8dcc6ec`.

## The two turns

| | Turn 1 | Turn 2 |
|---|---|---|
| The operator's reply | comment `4459736116`: "TEST: is this still a bug if the caller checks the percent before calling apply?" | comment `4460627911`: "TEST: what should the caller contract say, then?" |
| Built at | `c6880fb3` | `b74e02ac` (the mention fix) |
| Run | `run::github:artyomsv/spire-test:review-97-8dcc6ec9045f-90cbc2de:1` | `run::github:artyomsv/spire-test:review-97-8dcc6ec9045f-fa09cc5b:1` |
| Call slot | `4446620058:4459736116` | `4446620058:4460627911` |
| From the reply to the run's start | 7 s | 8 s |
| The posted answer | comment `4459745757`, 53 s after the reply | comment `4460636872`, 55 s after the reply |
| Opens with | "artyomsv, …" (plain text, no notice) | "@artyomsv …" (a mention, notified) |
| Egress hosts (port 443) | `chatgpt.com` | `chatgpt.com` |
| Turn count after | 1 | 2 |

**Turn 1** answered that a caller check covers that one call path only, and that the public `apply` still takes any
percent with no stated precondition, so the finding stays open. **Turn 2** proposed the precondition text for `apply`
and said it resolves the concern only if issue #96 allows the caller to enforce the cap and every caller does. Both
answers stayed on the thread's own finding.

Each turn has one charge set, `UNMETERED`, cost 0, kind `FOLLOWUP`, with `credential_ref`, under
`review::artyomsv/spire-test#97|4446620058:<comment>|FOLLOWUP`, the identity an API reply is charged under; no other
charge kind was written for either comment:

| Token type | Turn 1 | Turn 2 |
|---|---|---|
| `INPUT` | 4,810 | 15,935 |
| `CACHED_INPUT` | 83,712 | 72,704 |
| `OUTPUT` | 548 | 535 |
| `REASONING` | 94 | 97 |

After each answer the review's "answering" flag was cleared and the thread's history held `AuthorReplied`,
`FollowUpGenerated` and `FollowUpRecorded`.

## What the proof found

- **The answer named its author without notifying them.** The reply prompt forbids hand-written mentions, since the
  syntax differs per forge and a wrong one fails to link or notifies the wrong account, so the bot wrote "artyomsv"
  as plain text. Fixed in `b74e02ac` for the API and the harness path: each adapter gives its forge's own mention
  (`CommentSink.mention`: `@login` on GitHub and GitLab, `@{account_id}` on Bitbucket Cloud), and the worker puts it
  in front of the answer. Turn 2 shows it.

## Limits that remain

- **One model, one harness, one forge:** Codex `gpt-6-astra` on a seat, GitHub, one inline thread.
- **Paths not proven live** (docs/UNVERIFIED.md, the step 7 entry): a push during a reply run, a refused seat on a
  reply, an over-cap answer, a summary-thread reply, the GitLab and Bitbucket mentions, and a dead-lettered
  `PostHarnessReply` replayed.
- **The "responding" flag stays up** after the worker's quiet exits at prepare, as on the API path.
