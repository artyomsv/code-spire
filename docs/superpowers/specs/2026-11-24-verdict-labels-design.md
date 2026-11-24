# Verdict labels: the reviewer marks who acts next

Status: design, 2026-11-24. Part of #391.

## Why

On the first production review (code-spire PR #443) the bot approved the pull request, and the pull request kept
its `review: requested` label. The operator's workflow reads one `review: …` label per pull request to see who acts
next without opening it (the author waits on `review: requested`; a reviewer sets `review: approved` or
`review: changes-requested`). Code Spire has no label support, so a pull request it reviewed still says it is
waiting for a reviewer.

## Goal

After Code Spire decides a review's verdict and posts it, the pull request carries exactly one verdict label:

| Verdict | Label set | Labels removed |
|---|---|---|
| approve | `review: approved` | every other label starting `review: ` |
| request changes | `review: changes-requested` | every other label starting `review: ` |

The verdict is the one the orchestrator decides (ADR-047, "Block approval at"), not the posted review event: with
"Reviewer may approve" off the review is posted as a Comment, and the label still follows the decided verdict.

## Non-goals

- Labels while a review runs (`review: requested` stays the author's to set, as in the operator's rule).
- `review: ci-failed`. It needs the CI-aware review that is the next feature; this design leaves room for it.
- Configurable label names. The names are fixed to the operator's convention for now.
- GitLab and Bitbucket. GitLab has labels and gets the same behaviour later; Bitbucket Cloud has none.
- Refused, failed, degraded or superseded reviews change no label.

## Design

1. **Per-repository setting** "Verdict labels", off by default (an install that does not use the convention must
   not get labels it never asked for). Stored on the repository (next migration number), shown in the repository's
   review settings beside "Reviewer may approve", with a one-line hint naming the two labels.
2. **Where:** the review worker, right after the review is posted and its link is reported, using the reviewer
   account (it has write access; labels need no other scope). One labels call set: read the pull request's labels,
   remove each `review: ` label that is not the target, add the target. Create the label in the repository when it
   does not exist yet (GitHub refuses to add a label that is not defined).
3. **Failure:** labelling is best effort. A failure is logged with the reason and raises an attention row
   (`REVIEW_LABEL_FAILED`, warning) for that repository; it never fails, retries or re-posts the review.
4. **Idempotent:** a replay of the same posted verdict leaves the same single label; a later review of a new head
   replaces it.
5. **Forge port:** a small capability on the SCM port (`setVerdictLabel(prRef, label, prefix)` or similar); GitHub
   implements it; the others report "not supported" and the setting is hidden for them.

## Tests (CI only)

- GitHub adapter against WireMock: adds the target, removes other `review: ` labels, keeps unrelated labels,
  creates a missing label, does nothing when already correct.
- Worker: approve and request-changes set the right label only when the repository setting is on; a Comment-posted
  approve still labels `review: approved`; refused/failed reviews call nothing; a label failure leaves the review
  posted and raises the attention row.
- UI: the setting renders for GitHub repositories only and saves.

## Live proof

On production (`spired.dev`), with the setting on for `artyomsv/code-spire`: a pull request with
`review: requested` ends with only `review: approved` after an approving review; a pull request with a blocking
finding ends with only `review: changes-requested`.

## Open question for the operator

Repositories reviewed by both Code Spire and another reviewer (the operator's code-review pane) get the label from
whichever posts last. Accepted for now: the setting is per repository, so it can stay off where that matters.
