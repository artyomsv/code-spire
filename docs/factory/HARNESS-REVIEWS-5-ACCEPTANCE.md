# First reviews on a harness acceptance record

Harness reviews step 5, "first reviews on a harness", is accepted on its live proof (Task 8 of the
[step 5 plan](../superpowers/plans/2026-11-01-harness-reviews-5-first-reviews.md), the
[harness reviews design](../superpowers/specs/2026-10-30-harness-reviews-design.md) and ADR-050, "Step 5"). On
`artyomsv/spire-test`, three TEST pull requests proved a first review made by a read-only Codex review run on a
subscription seat, the same on an API key, and a refusal when the seat is switched off. The human steps were: set
"Review with", open the pull request, and for the refusal switch the seat off.

The proof ran on 2026-11-01 on the dev stack, built from PR #368. The repository's "Review with" was Harness, Codex,
model `gpt-6-astra`, thinking level `high`; Pay with was a Codex subscription for cases 1 and 3 and an API key for
case 2. The egress proxy ran in report mode. "Reviewer may approve" was on, so the reviews carried the verdict
`ReviewVerdictDecision` decided (ADR-047). The proof found one defect, fixed on PR #368 before the proof continued;
it is listed under "What the proof found". PR #368 is not merged.

## The three cases

| | Case 1: seat | Case 2: API key | Case 3: refusal |
|---|---|---|---|
| Pull request | [spire-test #93](https://github.com/artyomsv/spire-test/pull/93) | [spire-test #94](https://github.com/artyomsv/spire-test/pull/94) | [spire-test #95](https://github.com/artyomsv/spire-test/pull/95) |
| Pay with | Codex subscription | API key | Codex subscription, seat switched off |
| Change | adds `Discount.java` with `percent / 100` in integer arithmetic and a `<=` loop bound, plus a canary `AGENTS.override.md` | `Shipping.feeMillicents` tests `grams > 5000` while its comment says shipping is free from 5000 | not part of the proof |
| Run | `run::github:artyomsv/spire-test:review-93-a72b5a3e18cc-81b23d75:1`, kind `REVIEW`, `SUBSCRIPTION`, about 1 minute, succeeded | `run::github:artyomsv/spire-test:review-94-f015aa548ea0-178b677c:1`, kind `REVIEW`, `API_KEY`, about 50 s from dispatch to the posted review | none |
| GitHub review | **CHANGES_REQUESTED**, 4 inline findings on `Discount.java` | **APPROVED**, 1 inline finding at `Shipping.java:11` | none |
| Egress hosts (port 443) | `chatgpt.com` | `api.openai.com` | none |
| Charges | `UNMETERED`, cost 0 | `METERED`, 69,110 millicents | none |
| Review end | posted | posted | `refused`, note "Harness review refused: subscription_unavailable" |

**Case 1** is a first review on a seat. The first attempt hit the defect below and stayed `reviewing`; after the fix
the review was re-run by a `/review` comment, because the review page offers no Re-run while a review is
`reviewing` (follow-up 1). The run took about a minute. The bot posted a CHANGES_REQUESTED review with four inline
findings on `Discount.java`:

- BLOCKER: money held as `int` cents breaks the target branch's `.codespire` rule (money is `long` millicents);
- MAJOR: `percent / 100` is 0 for every percent from 1 to 99;
- MAJOR: the `<=` loop bound throws `ArrayIndexOutOfBoundsException`;
- MINOR: a null input is not rejected with `IllegalArgumentException`, as `.codespire` asks.

The rules came from the target branch, `refs/spire-base/<target>`, not from the change. The pull request also added
an `AGENTS.override.md` asking for one finding, `CANARY-AGENTS-LOADED`. The review has no such finding: Codex did
not follow the head's guidance file under `-c project_doc_max_bytes=0`. The seat called `chatgpt.com` only; the
`oaiusercontent.com` content hosts removed in step 4 were not needed (RUN-TOPOLOGY.md §7.1).

Charges, all under the call ref
`review::artyomsv/spire-test#93|a72b5a3e18cc4369a881ccb639e84af07edadc7e#run2|REVIEW` (the re-run's round), with
`credential_ref` set, `UNMETERED`, cost 0:

| Token type | Tokens |
|---|---|
| `INPUT` | 6,436 |
| `CACHED_INPUT` | 91,520 |
| `OUTPUT` | 2,001 |
| `REASONING` | 342 |

**Case 2** is a first review on an API key. The run took about 50 s from dispatch to the posted review. The bot
posted an APPROVED review with one MAJOR finding at `Shipping.java:11` naming the off-by-one: with no open blocker,
`ReviewVerdictDecision` approves. The run called `api.openai.com` only. Charges, `METERED`, with `credential_ref`
set:

| Token type | Tokens | Millicents |
|---|---|---|
| `CACHED_INPUT` | 76,414 | 15,282 |
| `CACHE_WRITE` | 17,482 | 43,705 |
| `INPUT` | 18 | 36 |
| `OUTPUT` | 1,146 | 8,595 |
| `REASONING` | 199 | 1,492 |
| **Total** | | **69,110** |

**Case 3** proves a refusal falls back to nothing. The operator switched the seat off and opened PR 95. The review
was refused within seconds: status `refused`, note "Harness review refused: subscription_unavailable",
`review_status.harness_refusal` `codex: subscription_unavailable`, no run, nothing posted and no fallback to the API. The
attention bell showed "Harness review refused: codex: subscription_unavailable" for `artyomsv/spire-test#95`
(operator screenshot).

## What the proof found

1. **A repository with no customised review prompt never started a harness review.** With no customised prompt,
   `WorkerPromptTemplates.forKind` returns null: the API path leaves the default to the worker. The harness start
   passed that null to `HarnessReviewPrompt.compose`, `ResultSaga`'s `ContextAssembled` handler threw a
   `NullPointerException` and the message was dead-lettered. The review stayed `reviewing` with no run and no
   attention row. Fixed in `939a1277`: the harness start uses `PromptCatalog.defaultTemplate(REVIEW)`, and any
   failure composing or dispatching refused the review with `harness_review_start_failed`. Three tests were added;
   CI run 41355894965 is green. Case 1's re-run is the fix seen live. The review of PR #368 (code-quality/M-2)
   narrowed the refusal in `de7a905f`: only a failure composing the request refuses, with an attention row. A dispatch
   that throws may already have published its command, so its exception fails the start message, which dead-letters,
   and the review stays `reviewing` for the paid run's answer; one that threw before publishing shows as
   `REVIEW_STUCK` beside the dead-letter row. `HarnessDispatchFailureTest` drives that path from an ambiguous ack to
   the posted answer.

## Follow-ups (not fixed on PR #368)

1. **A stuck review cannot be re-run from the dashboard.** The review page hides Re-run while a review is
   `reviewing` (`OFFERS_RERUN` in `ReviewActions.tsx`), so #93 needed a `/review` comment
   (`techdebt/spire-ui/3-2-a-stuck-reviewing-review-cannot-be-re-run.md`).
2. **A forced re-run's review shows `superseded` while its harness run works.** During case 1's re-run the review
   status read `superseded`, with the note "Reviewing on codex…", until the run completed (docs/UNVERIFIED.md, the
   step 5 entry).
3. **#93's dead-lettered `ContextAssembled` must be discarded, not replayed.** Replaying it from Settings →
   Dead-letter would start the first review again on a review already posted.

## Limits that remain

- **One model and one harness:** Codex `gpt-6-astra` at thinking `high`, one review per payment.
- **Paths not proven live** (docs/UNVERIFIED.md, the step 5 entry): a fork refusal, a start refused for a request
  that could not be composed (`harness_review_start_failed`) after the fix, a dispatch that throws (the start message
  dead-letters and the review stays `reviewing`), `Uncertain` launch recovery, an answer over 256 KiB, a seat
  rejected by the vendor, and the entrypoint's fallback for a bad `SPIRE_ARTIFACT_MAX_BYTES`.
- **The guidance switch is measured once.** One canary was not followed; Codex's loading rule is read from its
  source, and the agent can still read the head's `AGENTS.md` as a file of the change.
- **The answer is posted off the review's ordered consumer.** A push to a new head during the answer is not fenced by
  the review lock, and no test races the two real consumers.
- **Re-reviews stay on the API until step 6;** the agent gets no assembled context yet.
