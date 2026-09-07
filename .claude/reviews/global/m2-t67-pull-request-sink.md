# Code Review State: global / m2-t67-pull-request-sink

Last reviewed: 2026-09-04
Rounds completed: 1

Round 1 over `ee46f38` and `8b0442d` on `feat/factory-m2-deliver` (PR #119) — M2 tasks T6 and T7:
the `PullRequestSink` port and all three forge adapters. Fixes in `0b90dbf`.

**qa did not run.** It hit a session rate limit before reading anything, so its section is UNKNOWN,
not clean. The build and mutation evidence below is mine and the other three lenses'; the coverage
questions qa was asked — is any test vacuous, which branches no test reaches, should there be a
cross-forge parity fixture — are unanswered and carried to round 2.

**Semgrep: 7 files scanned, 0 findings.** (The 5 test files are excluded by the rulesets' test-path
filter.)

## Resolved (fixed in code; do not re-raise)

- [code-quality/CRITICAL-1] `findByHead` filtered on the head alone. A pull request is unique per
  (head, base) PAIR on every forge — GitHub's own duplicate refusal fires only when both match — so
  the lookup was strictly WIDER than the rule the forge enforces and could answer a pull request
  aimed at another base, which the caller records as this run's delivery while the correct one never
  opens. ADR-040's existing-branch mode makes it reachable by design. Port signature now takes both;
  all three adapters filter on both. — round 1
- [rules/HIGH-1] `Optional.get()` after `isPresent()` in all three `open()` methods, while
  `recover()` one method below already used `orElseThrow`. Now `orElseGet(() -> create(...))`.
  — round 1
- [security/M1 + rules/M2 + code-quality/M3] The nothing-to-propose classification had no status or
  structure gate and matched generic sub-phrases against a 500-character raw body snippet. Now gated
  on the forge's status, and Bitbucket matches its full phrase rather than `"no changes"`. The
  asymmetry is the argument: an unmatched failure degrades safely, a falsely matched one reports a
  run as "the agent changed nothing" when the forge refused for another reason. — round 1
- [code-quality/M3b] The already-exists wording is gone from all three adapters. That case is
  identifiable by BEHAVIOUR — on any refusal that is not nothing-to-propose, ask the forge — and
  §8's own Bitbucket cell admits the phrasing is unknown, so the guard would never have fired there.
  — round 1
- [code-quality/M3c] A fault on the re-read replaced the original refusal, so an operator saw a
  failed GET and never learned the create was denied. Now attached as suppressed. — round 1
- [security/M3 + code-quality/M5] Bitbucket interpolated the branch name into its query LANGUAGE
  with no escaping. A double quote is legal in a git refname and `URLEncoder` protects the transport,
  not the parser: `x" OR state="OPEN` widens the clause to the repository's first open pull request.
  Reachable — `/fix` reads the source branch from the webhook projection, which a pull-request author
  controls. Refused rather than escaped, because Bitbucket's escaping rule is unverified and a wrong
  escape is indistinguishable from none. — round 1
- [security/M2] `ProviderClients.pullRequestSink` could not assert the FACTORY role. **It could** —
  `ProviderRegistry.resolve` already filters `WHERE role = ?` and `decryptedProvider` simply dropped
  the column. `ScmProvider` carries it now, the assertion is three lines, and all 13 construction
  sites state which account they stand in for. — round 1
- [security/M2b] **A false claim in my own javadoc**, in the port and in `ProviderClients`: that the
  reviewer's author allowlist would skip a pull request the reviewer itself opened. Nothing gates
  pull-request authorship — the bot-authored check covers comments and commands only — and an empty
  allowlist means everyone, so by default it WOULD review its own. Corrected with the real
  consequences (misattribution, an unprovisioned write scope whose 403 names the wrong account, and
  the skip only for an operator who HAS set an allowlist). — round 1
- [code-quality/M2 + security/M4] `FactoryPullRequestBody` claimed the whole body was
  orchestrator-authored with only the paths agent-influenced. The task is `ExecuteRun.prompt`, which
  for a fix run is `FixPrompt`'s output — model-derived and entirely multi-line — and it was
  interpolated raw and unbounded where one line was reserved, while the title beside it already cut
  to one. Now normalised in both, with the two absent-value fallbacks kept distinct on purpose.
  — round 1
- [security/L6] The fence closed on a top-level file named exactly ` ``` `. Fence length is now the
  longest backtick run in the listed paths plus one. — round 1
- [security/L7] `PullRequestRef.url` accepted any non-blank string and becomes an href. Now refuses
  anything but http(s). Host deliberately NOT pinned — Bitbucket's web host is not its API host.
  — round 1
- [code-quality/#6] The three adapter suites had already diverged in round one: GitLab had no
  missing-URL case, so deleting half its `read()` guard left it green. Each suite now carries the
  cases the others had. — round 1
- [code-quality/#7] `ProviderClients.pullRequestSink` was covered by nothing; swapping two case
  labels compiled, passed, and would open the run's pull request through the wrong forge's client.
  Three tests added, asserting `type()` — which is what `type()` was put on the port for and nothing
  was using. — round 1
- [code-quality/suggestion] `read()` hardcoded `"POST"` while also serving the GET lookup path, so a
  malformed lookup response named a request that was never made. — round 1
- [code-quality/suggestion] `title()`'s bound was pinned as `length() < 80` against an actual 68, so
  widening the cut from 57 to 68 passed. Now an exact assertion. — round 1
- [rules/M3 + LOW-6] `60`/`57` were both literal with `57` silently derived, and `MAX_PATHS_SHOWN`'s
  javadoc described a length in characters for a field that counts paths. — round 1
- [rules/LOW-7] A one-element loop over `null` in `PullRequestSinkTest`. — round 1
- [security/L9 + rules/M4 + code-quality/#8] **Doc drift I created between two commits an hour
  apart**: `UNVERIFIED.md` said the GitLab and Bitbucket rows had no implementation, true for one
  commit and false for the next. Both docs corrected, and the `UNVERIFIED` entry now says which
  round wrote each half. — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [code-quality/#1-alt] Extracting a shared adapter base class. **Filed as answered, not ignored** —
  code-reviewer argued it and then argued against it, and I agree with its second answer: the adapter
  modules are deliberately independent framework-free libraries, a base needs a new common module all
  three depend on, and the thing that differs (strings, paths, JSON shapes) is 100% of what a base
  could not hold. Its counter-proposal — a shared contract TEST in `spire-contract` test fixtures,
  after the `RunRuntimeContract` precedent — is the right shape and is **carried to round 2** rather
  than dismissed. (round 1)
- [code-quality/suggestion] Restructuring `ProviderClients`' four `switch (provider.type())` blocks
  onto the `ScmType` enum so an exhaustive switch catches a missing adapter. Correct, and correctly
  timed for the fourth forge (Bitbucket DC is already in §8's table) rather than for a port slice —
  a composition-root refactor in the same diff would be unreviewable. (round 1)
- [rules/#8] `MARK = "<!-- codespire-factory-run -->"` as a "Code Spire" naming-rule violation.
  Ruled NOT a violation: the rule protects the user-facing product name in six prose literals, and
  this is the lowercase internal namespace token `CLAUDE.md` explicitly exempts, in an HTML comment
  invisible in every forge's rendered Markdown. The visible copy beside it carries no product name at
  all. Caveat accepted for the merge gate: it ships into third-party pull request bodies and cannot
  be edited retroactively, so it belongs in `CLAUDE.md`'s internal-surface sentence. (round 1)
- [security/L8] `NothingToPropose` as a sealed result rather than an unchecked exception. The port
  now documents the catch contract and names the dangerous shape (a blanket `RuntimeException` retry
  spending a GET, a POST and a 4xx per attempt with the write credential). Revisit when the consumer
  exists — it does not yet, and changing the shape now would be designing for a caller nobody has
  written. (round 1)

## Carried to round 2

- **qa's whole section.** Coverage gaps, vacuous tests, unreached branches — unanswered.
- **The shared contract test fixture** (`PullRequestSinkContract` in `spire-contract` testFixtures),
  which is the structural answer to suite divergence rather than the three-files-in-sync answer.
- **`docs/CONTRACT.md`'s port block** lists only `DiffSource` and `CommentSink`; a fourth SPI port
  now exists. `ARCHITECTURE.md` already declares that gap in writing, so it is self-declared rather
  than silent — merge-gate item.
- **One live measurement against GitLab** (SMOKE-TEST Mode G) for the nothing-to-propose arm, whose
  adapter constant and §8 row disagree and which may be unreachable. Recorded in `UNVERIFIED.md`.
- **The reviewer's author gate consulting `MARK` or the factory account's id.** Nothing reads the
  mark today, so an operator with an allowlist gets exactly the silent failure AUTONOMY.md names.

## Verification

`testFast` — contract 134, scm-github 86, scm-gitlab 92, scm-bitbucket 79, arch 46, 0 failures.
`:spire-orchestrator:test` — 1142 tests, 0 failures.

Twelve mutations, each killing exactly its intended test:

| Mutation | Fails |
|---|---|
| GitHub: drop the `base` filter | `aPullRequestFromThisHeadOntoAnotherBase…` + the lookup case |
| GitLab: drop the `target_branch` filter | `aMergeRequestOntoAnotherTarget…` |
| Bitbucket: drop the destination clause | `aPullRequestOntoAnotherDestination…` |
| GitHub / GitLab: drop the status gate | `thatWordingOnADifferentStatusIsStillAFault` (each) |
| Bitbucket: revert to the generic `"no changes"` | `aDifferentMessageMentioningChangesIsStillAFault` |
| GitHub: let the re-read fault escape | `aFailedReReadKeepsTheOriginalRefusal…` |
| Bitbucket: allow a quote in a branch name | `aBranchNameThatWouldAlterTheQueryIsRefused` |
| `ProviderClients`: drop the role check | `pullRequestSinkRefusesAnyAccountButTheFactorys` |
| Body: interpolate the task raw | 3 task cases |
| Body: fix the fence at three backticks | `aPathThatWouldCloseTheFenceWidensItInstead` |
| `PullRequestRef`: allow any scheme | `aPullRequestUrlMustBeHttpOrHttps` |
