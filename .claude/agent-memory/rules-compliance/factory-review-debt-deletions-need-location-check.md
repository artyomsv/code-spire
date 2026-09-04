---
name: factory-review-debt-deletions-need-location-check
description: Judge every techdebt entry — deleted OR left open — against its own Location row AND every assertion in its Issue section, read at origin/master, never against a commit or plan that claims to close it
metadata:
  type: feedback
---

A commit that deletes a `techdebt/` entry must be checked against the entry's **Location** row and
**every** bullet under Suggested Solutions, read at `origin/master`, not against the commit's own
claim that it closes the entry.

**Why:** in M1 Task 1 (PR #96) four entries were deleted and two were only half-closed, both times
because the fix addressed the headline and skipped a second file the entry named explicitly. The
scrub entry listed `RunDispatcher.java` beside `RunLauncher.java` and only the launcher was changed;
the retryability entry asked to split a transport failure from a forge refusal and only the
non-fast-forward case was split, which inverted the retry answer for transport faults. Neither gap
was visible from the commit message or the diff alone. A deleted entry is invisible afterwards, so a
premature deletion is worse than an unfixed debt.

**How to apply:** for each deleted entry run `git show origin/master:<path>`, list its Location files,
and grep each one in the ref under review to confirm it actually changed. An entry whose stated
solution is partly deferred can still be deleted **if** the deferred half is recorded somewhere
durable (a design doc section the entry itself cites, or a milestone plan) — check that the citation
is real before accepting it. Note also that the local `master` ref in this worktree goes stale;
always use `origin/master`. Commit style is covered by
[[commit-style-is-narrative-not-conventional]].

**The same check runs in reverse.** A milestone plan that says a task closes an entry is not
authority either. In M1 Task 2 the plan claimed the task closed
`techdebt/global/3-3-run-event-accumulation-is-unbounded.md`; reading the entry showed it is about
`RunEventSummary`'s list-shaped SPI and its two-meaning `sawAnyOutput` flag in `spire-harness`,
while the task added a second reader in `spire-run-worker` and `spire-orchestrator` and touched
neither. Confirmed by reading `RunEventSummary` at the ref under review — both meanings were still
live. Leaving the entry open was correct, and the plan is what needed correcting. So read the
Location row first in both directions, and say plainly when a plan's closure claim is the thing
that is wrong.

**Read the Issue section's own assertions too, not only Location and Suggested Solutions.** In M1
Task 3 both Location files really changed and the headline defect was really fixed, so every check
above passed — yet the entry
(`spire-run-worker/2-3-a-failed-salvage-discards-every-push-…`) was still half open. Its Issue
paragraph asserted a second fact in passing: *"a daemon fault during salvage (retryable) … both read
as SALVAGE_FAILED, retryable=false"*. The fix split the cause codes and left both
`RunFailureCause` values at `retryable=false`, so the attribution half closed and the retry half did
not. The commit message and the production javadoc both claimed "opposite retry answers" while the
enum gave identical ones. **How to apply:** turn every sentence of Issue into a yes/no question and
answer it from the ref under review; a claim buried mid-paragraph carries the same weight as the
title.

**A Location row can name columns, not only files.** In M1 the entry
`spire-orchestrator/3-3-run-token-usage-is-dropped-…` named three ledger facts —
`subject_kind='RUN'`, `capability`, `credential_ref` from V42, all "written by nothing". The
closing commit wrote the first two and deleted the entry; `credential_ref` is still written by no
Java in the repo, and `docs/factory/ARCHITECTURE.md` says it joined at M0 *because it cannot be
backfilled* — the identical argument that commit made for `capability`. **How to apply:** when a
Location row names schema, grep each column name across the ref under review and confirm a WRITER
exists, not merely a migration that adds it.
