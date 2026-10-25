# Land acceptance record

M4 slice 2, "land the approved pull request", is accepted on its live proof (§9 of the
[land design](../superpowers/specs/2026-10-21-factory-m4-land-design.md), the
[land plan](../superpowers/plans/2026-10-22-factory-m4-land.md) and ADR-048). On `artyomsv/spire-test`,
five cases proved a clean merge under each ticket rule, a conflict fixed by a land fix and merged on a new
gate, a merge by hand, and a protection block handed to the operator. The human steps were write, label,
approve the plan and approve land; for the conflict, the hand merge and the block the operator also made
the change on GitHub that the case needed.

The proof ran from 2026-10-23 to 2026-10-25 on the dev stack, built from PR #243, under the profile
`spire:assisted`. The repository's land settings were merge method `squash` and fix attempts 3; the ticket
rule was `comment` except where a case says `close`. Every build was paid by a Codex subscription seat.
The proof found five defects, all fixed on PR #243 before it merged; they are listed under "What the proof
found". PR #243 merged to master on 2026-10-25 as `1e237631`.

The factory account is `code-spire-beaver`. `spire-test` has a REVIEWER webhook and no FACTORY webhook,
and its "Reviewer may approve" setting is off, so every review in this proof was a COMMENTED pull request
review (ADR-047, as designed).

## The five cases

| | Case 1: clean, `comment` | Case 2: clean, `close` | Case 3: conflict | Case 4: hand merge | Case 5: protection block |
|---|---|---|---|---|---|
| Ticket | spire-test #58 | spire-test #60 | spire-test #62 | spire-test #64, re-test #68 | spire-test #66 |
| Pull request | 59 | 61 | 63 | 65, re-test 69 | 67 (still open) |
| Approved head | `98964158` | `737ec47c` | `7c4bb08b`, then `d9283f04` | none (merged at the land gate) | `b23b0a1c` |
| Land effect (UTC) | 2026-10-23 23:38:07 → 23:38:18 | 23:46:38 → 23:46:48 | 23:54:03 → 23:54:08 `fixing`; 23:56:52 → 2026-10-24 00:00:33 | #64: 2026-10-24 01:12:59 → 01:13:02 | 2026-10-24 00:15:30 → 00:15:33 |
| Merged by | factory, 23:38:16 | factory, 23:46:46 | factory, 2026-10-24 00:00:31 | `artyomsv` (GitHub id 3218389): 00:02:56; #68: 2026-10-25 10:54:26 | not merged |
| Method / merge commit | squash / `aaeff22e` | squash / `c4090230` | squash / `72b5ba47` | `unknown` / `92e0d6cd`; #68: `11211831` | none |
| Branch | deleted | deleted | deleted | deleted, both | kept |
| Ticket | commented, stays open | commented, then closed | commented, closed (the `close` rule was still set) | commented; #68 stays open | untouched |
| Item end | `complete / completed / landed`, no Readmit | `complete / completed / landed` | `complete / completed / landed` | `complete / completed / landed`, both | `land / stopped / land_stopped_by_operator` |

**Case 1** is the whole slice in eleven seconds: the land effect read the pull request, merged it with
squash at exactly `98964158`, deleted the branch, commented on the ticket and completed the item. The
ticket stayed open, as the `comment` rule says, so testing can follow. The item page offered no Readmit.

**Case 2** repeated case 1 with the ticket rule set to `close`: the comment was posted, then the issue was
closed.

**Case 3** proves the land fix. Before approving land the operator created, by hand on `main`, the same
file the build had added. The first land effect read the conflict and ended `fixing` with
`land_conflict`, five seconds after it started. The land fix run merged the base into the item branch and
resolved the conflict; verify ran again, the pull request was reviewed again, and a new land gate opened on
the new head `d9283f04`. The operator approved it, and the second land effect merged at that head. The
ticket was closed because the `close` rule from case 2 was still set.

**Case 4** first failed and found defect 1 below. `artyomsv` merged PR 65 by hand while #64 waited at
`land / waiting_approval`; with no FACTORY webhook nothing noticed, and the item stayed at its land gate.
After the fix was deployed, #64 converged by itself: the land effect recorded the merger as 3218389,
deleted the branch and commented "Merged by artyomsv: …/pull/65 (92e0d6c)". The re-test on #68, with the
`comment` rule restored, ran from the hand merge of PR 69 at 10:54:26 to `complete / completed / landed`
at 10:54:45, 19 seconds; the branch was deleted, the comment posted, and the ticket stayed open. A hand
merge records the method as `unknown`: GitHub's pull request read does not report it.

**Case 5** proves a block that only a person can clear is handed over, not fixed. The operator added a
GitHub ruleset on `main` requiring one approval. The land effect on `b23b0a1c` read the block and handed
the item over with `land_blocked` after three seconds; no fix run started. The operator chose Stop land
with a note, and the item ended `land / stopped / land_stopped_by_operator`. The bot's review could not
satisfy the ruleset, because with "Reviewer may approve" off it was a Comment. The ruleset was removed
afterwards.

## What the proof found

Five defects, each fixed on PR #243 with a test that failed first in CI (`b39ab16a`, `af362a0b`,
`5c3d21d9`), then the fix. Three mutants were raised as their own pull requests and each was killed in
CI: #283 (the reviewer close's machine rule), #284 (the periodic read's live-effect rule) and #285 (the
ghost button's disabled style). The branch head after the fixes was `355be3e8`, CI run 40046885352.

1. **A hand merge was never noticed without a FACTORY webhook.** No `work_land_effect` row exists before
   approval, and `spire-test` sends only the reviewer's events, so PR 65's merge left #64 at its land gate.
   The REVIEWER webhook's close of a pull request a work item delivered now reaches the same land
   observation, and a periodic read (`WorkPullRequestWatch`, every 2 minutes, 20 items per check) reads the
   pull request of each item at deliver, review or land with no live land effect (`f14614ee`, `deb76a6c`).
   Proved live by #64 converging after the redeploy and by #68.
2. **Two land decisions rendered identically** as "APPROVED by <uuid>". They now show the person's name,
   the time and the short sha, latest first (`67cf7fd1`).
3. **Disabled `.btn` and `.btn-ghost` buttons had no disabled style,** so Stop land looked clickable
   before a note was written. They now look disabled, with a hint naming the note (`3abf1d7d`).
4. **The Stop land note was not shown.** It is now shown with who wrote it and when (`67cf7fd1`).
5. **The dashboard polled every 10 s (detail) and 15 s (list)** while the operator watched a land run. It
   now polls every 3 s on the detail page and 5 s on the list while work is unfinished, and 15 s otherwise
   (`5a13ed50`).

## How the build was proven

PR #243 was built in nine slices, each RED then GREEN in CI. Chosen mutations were killed in three
rounds, 36, then 3, then the 3 from the live proof above; none survived. A four-lens review covered the
whole branch, and an external review of the pull request found five defects, all fixed; it approved at
`36b21943`.

## Limits that remain

- **Paths not proven live,** each proven against a WireMock GitHub only (`UNVERIFIED.md`, the M4 land
  entry): the failing-check land fix (`spire-test` has no CI to fail), a check conclusion that is not code
  (`cancelled`, `timed_out`: `land_checks_not_code`), fix attempts exhausted
  (`land_fix_attempts_exhausted`), Retry land end to end, the recovery of an uncertain merge, a branch
  delete that fails, and the Jira `close` skip (`ticket_close_unsupported`).
- **A hand merge records the method as `unknown`.** Only the factory's own merge records the method it
  sent.
- **The two new observers are not proven apart.** PR 65's close arrived before the fix was deployed, so
  #64 was brought home by the periodic read; for #68 this record does not say whether the REVIEWER
  webhook's close or the periodic read was first.
- **Land `auto_if_green` stays unwired;** such an item waits at `land / capability_unavailable`.
- **GitLab and Bitbucket land are unavailable.** An item there waits at land, and Stop land is the way on.
- **Branch protection is read only as GitHub's `blocked`.** Case 5 shows the hand-off; nothing names which
  rule blocked the merge, and a merge queue has not been met.
