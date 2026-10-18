# GitHub reviews acceptance record

The slice "the reviewer posts a GitHub pull request review" is accepted on its live proof (Task 13 of
the [GitHub reviews plan](../superpowers/plans/2026-10-15-github-reviews.md), the
[design](../superpowers/specs/2026-10-15-github-reviews-design.md) and ADR-047). On
`artyomsv/spire-test`, three tickets proved the three verdicts a review can carry: Approve, Request
changes, and Comment when the repository setting "Reviewer may approve" is off. The bot was requested
as a reviewer on every factory PR. A fourth proof, the dashboard re-run, proved that a new run posts a
new review and dismisses the bot's own standing hold.

The proof ran from 2026-10-16 to 2026-10-18 on the dev stack, built from PR #196. It found two
defects, both fixed on the branch with tests and mutation checks before the proof continued; they
are listed under "What the proof found". The human steps were: write the issue, label it, approve the
plan, and for the setting proofs toggle "Reviewer may approve" and click Re-run review.

The reviewer bot is `code-spire-mole`, a collaborator of `spire-test`. `spire-test`'s `.codespire`
carries, under Money, the line "A monetary amount in any type other than long is a BLOCKER. It must
stop the merge"; the operator added it during the proof (see #51 below). The build check on
`spire-test` is `test -f README.md`.

## The three cases

| | Case 1: Approve | Case 2: Request changes | Case 3: setting off |
|---|---|---|---|
| Ticket | spire-test #50 | spire-test #54 | spire-test #56 |
| Change asked | add `docs/reviews-approve.md` | return the charge from `Pricer` as a `double` | add `docs/reviews-comment.md` |
| Pull request | #52 | #55 | #57 |
| "Reviewer may approve" | on | on | off |
| Reviewer request receipt | `reviewer-request:c5469fba-8a5d-4fc9-916a-0819ffa5dd13:52` → `reviewer_requested` | `reviewer-request:eef53047-cd29-452b-aaa2-c4b23c5f4514:55` → `reviewer_requested` | `reviewer-request:56d996de-ddff-420e-9e54-e59fdcd7bc88:57` → `reviewer_requested` |
| GitHub timeline | `review_requested` (bot), then `reviewed` approved | `review_requested`, then `reviewed` changes requested | `review_requested`, then `reviewed` commented |
| Review | 5552819299 **APPROVED**, 2026-10-16 19:42:18 UTC | 5553198213 **CHANGES_REQUESTED**, 20:15:44 UTC, 3 inline comments in the review (1 BLOCKER, 2 MAJOR) | 5554124533 **COMMENTED**, 21:53:48 UTC, body line `**Verdict:** approve` under the header |
| Merge box | Approved | Changes requested | untouched (`reviewDecision` empty) |
| `review_status.posted_verdict` | `APPROVE`, with the review link | `REQUEST_CHANGES`, with the review link | `COMMENT`, with the review link |
| Dashboard | "Posted as Approved", link to the review | "Posted as Changes requested", link | "Posted as Comment", link |
| Timeline row | `ReviewVerdictDecided verdict:APPROVE · assessment:APPROVE · no_open_blockers` | `ReviewVerdictDecided verdict:REQUEST_CHANGES open_blockers=1`; `CommentsPosted 3 inline comments`; all 3 in `review_thread` | `ReviewVerdictDecided verdict:COMMENT · assessment:APPROVE · setting_off` |
| Item end | `land / waiting_approval` | `review / review_blockers_open` | `land / waiting_approval` |

**The first attempt at case 2 was #51 (PR 53):** "return the charge as an `int`". The reviewer graded
the cast MAJOR, not BLOCKER, and approved (review 5552821270). The repository rules, not the
reviewer's taste, are the operator's lever: after the `.codespire` BLOCKER line above, #54 was flagged
as a blocker and held. PR 53 also exposed defect L-1 below.

The land gates of #50, #51 and #56 were left unanswered on purpose (production land is not built).
They have since expired: the items read `land / stopped / gate_expired`.

The identifiers:

| Ticket | Work item id |
|---|---|
| #50 | `work-v1-a5aae9d76b753acbb3e831925defb7eca4efeddcd08d691fef231c35f2ab6fd0` |
| #51 | `work-v1-7e18c5a67e17bf2d18a776e4ff46beb3bd1360c569effcb68e1e7ae4a64a0aea` |
| #54 | `work-v1-fdb58f643eb5953d5233ca7e52f089f7711ec8b9a01b64da2c851972d8848e8f` |
| #56 | `work-v1-d702fbe9558bead32444e9343f53c0e31c81377a046008086dd7df0186d26243` |

## The re-run proof (dismissal, D11)

Case 3 as designed expected case 2's hold to be dismissed by the Comment review. The two cases are
different PRs, so nothing stood to dismiss on PR 57. The dismissal was proved on PR 55 instead, by
the dashboard's Re-run review on the same head `f0f4c624`. A draft → ready toggle on PR 55 did
nothing first: an already reviewed commit is not reviewed again, by design.

| Re-run (UTC) | Setting | Result on PR 55 |
|---|---|---|
| 2026-10-16 22:20 | off | **Defect L-2.** The look-first matched the earlier Request changes 5553198213 by its bare header, reused it, posted nothing, and dismissed it with "Superseded by review 5553198213" (itself). |
| 2026-10-16 22:54, after the fix | off | Comment review 5554508062, header marker `<!-- code-spire-run:f1f09d53… -->`, `**Verdict:** changes requested`; `posted_verdict=COMMENT`; `run_token` stored. Nothing stood to dismiss. |
| 2026-10-16 23:27 | off (the setting change was not yet saved) | Comment review 5554686164, marker `22168e48…`. |
| 2026-10-16 23:29 | on | Review 5554693017 **CHANGES_REQUESTED**, marker `2bc04592…`; `reviewDecision` CHANGES_REQUESTED; `posted_verdict=REQUEST_CHANGES`. |
| 2026-10-18 15:40 | off | Comment review 5563717456, marker `a14b2b84…`, `**Verdict:** changes requested`; **5554693017 dismissed** at 15:40:53 with "Superseded by review 5563717456: the reviewer may not change merge state on this repository"; `reviewDecision` empty; `posted_verdict=COMMENT`; timeline `ReviewVerdictDecided verdict:COMMENT · assessment:REQUEST_CHANGES · setting_off`. |

The last row is the dismissal proof: the hold the bot placed while the setting was on is released by
its next review once the setting is off, and the message names the new review. The GitHub markdown
API confirmed that the HTML comment marker renders hidden. Every review stays on the PR; a re-run
never edits or reuses an earlier run's review.

The last re-run reported `ReviewGenerated 0 findings` yet an assessment of REQUEST_CHANGES: the
three open blocker and major threads from review 5553198213 still stand in `review_thread`, and
open blockers decide the assessment.

## What the proof found

Two defects, each fixed on PR #196 with a test that failed first in CI, then a mutation check
(`.claude/reviews/global/github-reviews-mutations.md`, "Live-proof fixes"):

1. **L-1: inline comments read back from the wrong endpoint.** `GET /pulls/{n}/reviews/{id}/comments`
   answers in GitHub's legacy shape (`path`, `position`; `line`, `side` and `start_line` null), so the
   read-back after a review post matched no anchor. PR 53 carried its inline comment on GitHub while the
   body said "Could not be posted inline" and `CommentsPosted` had 0 inline comments. `readBack` now
   walks `GET /pulls/{n}/comments` and keeps the rows whose `pull_request_review_id` is this review
   (`abd2b323` RED, `73f3ab9c` GREEN; `readsBackFromThePullRequestCommentsNotTheReviewComments`).
   Proved live on PR 55: 3 of 3 inline comments in `review_thread`. Mutant #228.
2. **L-2: a re-run on the same head reused the earlier run's review.** The look-first found the old
   review by header prefix and head, so a new run could post nothing, and the setting-off path then
   dismissed the old review as superseded by itself. Every claimed run and every re-run now gets a
   `run_token` (`review_status.run_token`, V89); `PostComments` carries it; the worker writes it on the
   header line as `<!-- code-spire-run:… -->`, and the look-first matches that marker only. A retry or
   a header refresh keeps the token, so a redelivery of the same run still finds its own review
   (`58132e7b` RED, `e7b6f92a` GREEN; five tests named in the review record). Mutants #223–#227.

Also from the proof:

- **The review link comes from the forge.** `PostedReview.reviewUrl` travels on
  `CommentsPosted` into `review_status.posted_review_url`; the orchestrator composes no GitHub URL
  (`CoreIsProviderNeutralTest`).
- **The reviewer request also runs for a PR the factory opened ready** (not only after a draft is
  marked ready).
- **Operator finding, not fixed here:** on the Repositories page, "Edit repository and accounts" opens a
  second side panel over the detail side panel. The operator asked for this to be fixed after the
  testing; it is a follow-up outside this branch.

## Limits that remain

- **Proven against fakes only, not GitHub:** the self-authored refusal (the reviewer account opened the
  PR), a finding whose anchor GitHub rejects, the crash window between a review post and its mark, and
  a reviewer login that is not a collaborator. `UNVERIFIED.md` keeps the entry; the dismissal and the
  look-first on a redelivery are now live.
- **GitLab and Bitbucket keep the summary comment.** Native reviews and approvals there are a later
  slice.
- **A legacy summary comment stays stale.** A PR that got the old issue-comment summary before this
  slice keeps it; the new review is posted beside it and nothing edits the old comment.
- **The review's hold needs write access to dismiss.** With a read-only reviewer account the Comment
  path posts but cannot release its own hold; the WARN log names it (API knowledge, not measured).
- **Deferred minors from the branch review** (`.claude/reviews/global/github-reviews.md`, M-4 to
  M-10): a redelivery with an unknown bot login emits a null posted verdict; a transient fault on the
  reviewer request becomes a permanent receipt; every 422 on `requested_reviewers` is named
  not-a-collaborator; `reviewUrl` renders without a scheme check; `repository_unknown` replaces
  `open_blockers=N` in the timeline reason; a fully qualified `java.time.Instant` in the review
  transcript; the stale legacy comment above.
- **Branch protection is not read or changed.** Whether a bot Approve counts toward required approvals
  is the repository's rule.
- **Production LAND remains unavailable;** the land gate expires unanswered.
