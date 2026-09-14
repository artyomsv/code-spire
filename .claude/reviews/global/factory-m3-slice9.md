# M3 slice 9 — external answers and human takeover

## Scope

Tracker commands, dashboard decisions and supported native PR approvals enter ResolveGate and
record GATE_RESOLVED. Tracker commands bind the gate UUID, generation and artifact; ordinary
approving text is human activity. Native GitHub approval reads verify the named review, latest
decisive state, open PR and live head, then require measured push permission without DENY.
GitLab and Bitbucket native approval channels remain visibly unavailable.

Takeover compares stable identities recorded for the build and the tracker identity recorded at
admission. It supersedes gates, invalidates pending effects and durably queues an exact run-binding
publication hold. The worker stores that revocation independently of M1 cancellation. Resume
requires a verified operator, expected revision and note, re-observes current evidence and creates
a new generation. Retired items cannot resume. ADR-045 records the FR-F22/FR-F25 precedence.

## Four requested discriminating proofs

1. `ordinaryApprovingWordsCannotAnswerAnOtherwiseEligibleGate`: an allowlisted actor supplies
   the exact gate, generation and artifact, but the comment lacks the slash. Removing the slash
   requirement in production makes the test fail. The restored case records takeover, no resolution.
2. `approvalOnOldHeadCannotResolveGateBoundToCurrentHead`: the native approval exists, measured
   permission passes and gate/live PR share the new head; only the review commit is old. Removing
   the review/live-head comparison fails. The dismissed-state case independently leaves the list
   approved while the named reread reports DISMISSED, so the latest-list check cannot mask it.
3. `recordedMachineIdSurvivesRenameAndAccountRotation`: signed push sender ID is the recorded bot,
   while its display name and current configured account have changed. Removing the recorded-ID
   comparison suspends the item and fails. A separate human-ID fixture uses the bot's display and
   commit-author strings and must suspend. Dispatch and admission snapshot tests cover persistence.
4. `takeoverRevocationSurvivesKilledJvmWithoutAnM1CancelClaim`: a first JVM builds and retains a
   real Docker workspace without pushing to the real local Git origin. A second JVM commits the
   hold and is killed before Docker cancellation. No M1 cancel slot is claimed. An otherwise valid
   permit must fail; a third JVM runs watchdog/recovery and cannot push or rebuild. Removing the
   durable SQL revocation predicate fails the permit assertion. The restored case passes.

The [production mutation inventory](factory-m3-slice9-mutations.json) records all five core
mutations and the additional guards. Each has one isolated assertion failure, an exact scratch-byte
restoration and a passing restored test. Final production anchors and hashes were audited again.

## Additional guards and validation

Forced testFast, testServices and assemble passed sequentially on Java 25, each with
--rerun-tasks --no-parallel. The XML totals are **4075 Java tests across 451 suites and
30 modules**, zero failures/errors and 1 existing Windows symlink privilege skip.
The fast tier has 1555 tests; the service tier has 2520. The final worker report contains
all three actual process-recovery cases, including takeover without an M1 cancellation claim.
The concurrent in-flight PR recovery test passes with two readers and exactly one persisted outcome.

The full UI suite passed **730 tests across 92 files**; its production build, including TypeScript,
passed. The accepted shared styling and route assertion remain intact.

**78 checks cover 78 distinct production mutations**:
72 target Java main sources and 6 target UI production sources. No fixture is mutated.
Additional proofs cover source membership and artifact/generation bindings, native review identity
and latest state, measured permission and DENY, signed activity identities, related coordinates,
command precedence, identity snapshots, gate supersession, pending-effect invalidation, durable
hold delivery, worker publication/recovery checks, resume authority and UI honesty.

Pinned Semgrep 1.172.0 reports **zero findings across 82 final changed code files**.
All captured hashes match final source. Its one partial-parser warning covers four unchanged
optional-field spans in api.ts, identical to accepted 9ec2f9db. No suppression was added.

One restored mutation run hit a Kafka-native startup failure (exit 126, “Text file busy”). Its failed
log was retained; only the restored case was retried and passed before recording that pair. It was
not counted as a mutation kill. No outside-container-deletion failure occurred in the final tiers.

Local logs and selected JUnit reports are in the worktree's git-ignored .handoff/s9-* files;
the checked-in inventory includes every production edit, selected case and restored-source hash.

## Boundaries

The first retry-wrapper mutation survived the existing reflection-only port coverage test:
that test proves an override exists, not that it delegates. A new behavioral case checks the
branch/repository arguments, returned head and transient retry. Its production null-return
mutation is recorded separately only after the assertion failure and restored pass.

Provider contracts checked on 2026-09-14: GitHub documents chronological
[PR review history](https://docs.github.com/en/rest/pulls/reviews?apiVersion=2022-11-28), which the
latest-decisive-review scan uses. Native activity fields follow the
[GitLab webhook payloads](https://docs.gitlab.com/user/project/integrations/webhook_events/).
Jira Cloud comment polling uses the
[REST v2 issue comments API](https://developer.atlassian.com/cloud/jira/platform/rest/v2/api-group-issue-comments/).

No live external gate or operator resume is claimed. Native provider observations use WireMock;
the publication-hold process-death proof uses real processes, PostgreSQL, Docker and a local Git
origin. The accepted slice 8b live standalone /fix proof remains separate and is not repeated.
Jira Cloud polls comments; Data Center cannot. Incomplete native review history cannot authorize
an approval. Jira processes individual comments and reports failure at its polling page bound.
Signed deletion/transfer handling still needs live payload evidence. Remote
publication already in progress cannot be recalled; recovery records its outcome and leaves the
item suspended. No atomic ordering with a human's remote push is claimed.

All fixtures are TEST-owned with exact cleanup. No dev run worker was started and no live data
baseline was changed. All seven previously accepted criteria remain unchanged.
