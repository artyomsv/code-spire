---
name: project-code-spire-test-gap-pattern
description: Code Spire's zero-coverage spots are wiring/dispatch classes, the extra call sites of a value added at several places, the other elements of a set a new guard loops over, and the other methods of a partially-overridden test fake — never the collaborators themselves
metadata:
  type: project
---

In Code Spire (this repo), when a commit adds several small, well-tested classes plus one "wiring"
class that calls them in sequence (a dispatcher, launcher, or orchestrator method), the wiring class
is the one most likely to ship with zero tests — because its collaborators are each unit-tested in
isolation and the suite looks comprehensive by count alone.

Confirmed instance (QA pass on PR #95, feat/software-factory, 2026-09-01): `RunDispatcher` (claim
before ack, redelivery guard, poison-record drop) and `RunLauncher` (the `interpret()` method
deciding RunFinished vs RunFailed from finalization/outcome/terminal state) in `spire-run-worker`
had NO test files at all, while their five collaborators (`RunUnitBuilder`, `Credentials`,
`RunClaimStore`, `PublisherOutcome`, `HarnessRegistry`) were each solidly tested and every mutation
against them was caught. Proven by mutating `RunDispatcher.onCommand` to delete the redelivery guard
and `RunLauncher.interpret` to always report success — in both cases `./gradlew :spire-run-worker:test`
was BUILD SUCCESSFUL with zero failures.

**Second shape, confirmed on PR #96 commit ceeead0 (2026-09-02): the N-call-site carry.** When a
commit adds one value to a record and then carries it at SEVERAL return sites, the test suite
typically asserts exactly ONE of them, and the commit message names that one mutation as proof the
whole change is covered. `RunFailed.tokenUsage` was carried at four `RunLauncher` sites plus
`RunDispatcher`'s compact-result path; only the agent-failure site was asserted. Mutating the other
four away left all 107 `spire-run-worker` tests green. The one asserted site is usually whichever
the author happened to write a scenario for, not the riskiest — here the unasserted ones included
the publisher-rejected-the-push path, which is the exact scenario the commit message headlines.

**Why:** this project's CLAUDE.md already documents the same shape recurring elsewhere (e.g. the
LLM cost ledger's re-run/archive defects, the conversation-derived-findings suppression-ordering
bug) — a class-by-class test count looks green while the ORDER or WIRING between classes is what's
actually unverified. The dispatch/interpret layer is exactly where "claim before ack",
"gate before push", "salvage before destroy" type invariants live, and those are ordering properties
a per-class unit test cannot see.

**How to apply:** when QA'ing a Code Spire PR that adds a multi-class feature, explicitly check for
a "dispatcher"/"launcher"/"cycle"/"saga" class that sequences the other new classes, and verify it
has its OWN test file (not just coverage-by-association through the classes it calls). Then grep for
every call site of any newly added field/carry (`git show HEAD | grep -c '\.withX('`) and count them
against the number of tests asserting it — a 4:1 ratio is the norm and the gap is reportable. Confirm
both by mutating and running the full module; a green build after the mutation is the smoking gun.
See also [[feedback-mutation-testing-restore-discipline]] and [[feedback-verify-tree-before-each-build]].

**CDI wiring splits into two mutations, and only one of them is usually covered.** A Quarkus bean
whose rule lives in a `static verify(...)` called from a `@Observes StartupEvent` method has TWO
failure modes, and a test driving `verify` directly covers neither of them fully. Measured on
`OrphanWatchdog` (`e57b76b`):

- **Wired WRONG** (arguments swapped in `check`) — CAUGHT, incidentally. The shipped config then
  violates the rule, boot fails, and every `@QuarkusTest` in the module reddens (here
  `RunClaimStoreTest`). Nothing was written to catch this; the boot tests do it for free.
- **Wired NOT AT ALL** (body of `check` emptied, or `@Observes` deleted) — NOT caught. Green.
  The startup refusal simply stops existing.

So a green suite proves the observer is not mis-wired; it says nothing about whether it still runs.
The module's own precedent closes it in one line: `RunAckBudgetTest.theDrainComesFromTheRuntimeArm`
calls `budget.check(null)` on the instance with its fields set, which pins the observer method's
body. Look for that call; if the new bean's test only calls the static, report it.

**The same asymmetry covers `@Scheduled`.** Deleting `@Scheduled` from `OrphanWatchdog.sweep()` left
ALL 17 suites / 155 tests of `spire-run-worker` green — including four `@QuarkusTest` boots — because
the `%test` profile disables the scheduler outright (`quarkus.scheduler.enabled: false`), so no boot
test can ever observe a timer. Only a reflection assertion on the annotation can. At `e57b76b` the
repo had **7 `@Scheduled` methods in production and exactly one guard**
(`LeaseHeartbeatIsScheduledTest`, on `WorkspaceLeases.heartbeat`) — whose own javadoc says a review
proved the silent-deletion case. Grep `@Scheduled` in `src/main` against `getAnnotation(Scheduled`
in `src/test`; the difference is the reportable gap, and for a watchdog the silent version means the
feature is inert while looking installed.

**A fake that models failure as a THROW can make a rule look tested while it is inoperative.**
The sharpest defect of the `e57b76b` round was found by neither the QA pass nor the review brief.
`OrphanWatchdog.reap` kept the "a failed salvage preserves the sandbox" rule by catching a
`RuntimeException` from `runtime.salvage(...)`. But `Finalization` is a three-outcome value
(`SALVAGED` / `OVERRAN` / `FAULTED`), and the only shipped runtime — `DockerRunRuntime.salvage` —
**never throws**: it returns `Finalization.faulted(...)` at three sites and `overran(...)` at a
fourth. So the rule was honoured only on a path production never takes, and a real "could not
observe the agent" destroyed the evidence the rule exists to preserve. The test asserting the rule
(`aFailedSalvageDuringReapPreservesTheSandbox`) set `salvageFails = new IllegalStateException(...)`,
i.e. it exercised the throw. Green, documented in three comments, inoperative.

**The FIX for the "fake does not answer a new collaborator" trap usually re-arms it.** Same commit
`626b0f6`. The author knew the trap (the comments cite it five times) and set the new
`EnterpriseEnvironmentConfig` collaborator in both `RunLauncherTest.failuresWith` and
`RunUnitBuilderTest.builder` — but with anonymous subclasses that override only the ONE method used
today (`proxiedWith` overrides `proxySecret()`; `corporate` overrides `environment()`). Every other
accessor still reads a null `@ConfigProperty` field or a null `resolved`. Measured: adding one
plausible future line to `RunFailures.scrubFor` that also reads `enterprise.environment()` produced
**44 NPE failures across `RunLauncherTest`, `RunDispatcherTest` and `ProxyCredentialIsRedactedTest`**,
every one reported as an unrelated launcher/dispatcher fault.

**How to apply:** when a review brief says "the fake was updated for the new collaborator", check
WHICH methods the fake answers, not whether it is set. A partial fake is the same trap with a longer
fuse. Recommend one shared helper that answers every accessor of the collaborator.

**A guard applied to a SET of things is asserted for exactly one of them.** Measured on PR #96
commit `626b0f6` (Task 11, FR-F14). `RunUnitSpec` gained two varargs guards run over all three
containers — `requireNoEnvironmentCollision(enterprise, init, agent, publisher)` and
`requireHostPathsDoNotShadowVolumes(...)`. Narrowing the first to `(enterprise, publisher)` and the
second to `(enterprise, init)` left `:spire-runtime:test` **BUILD SUCCESSFUL**, because each guard
has exactly one test and each test's fixture collides on exactly one container's value
(`SPIRE_GIT_SECRET`, the publisher's; `/workspace`, init's and the agent's). The commit message's
own argument is "every container of the unit, so no arm can apply it to two out of three" — and the
suite proves it for one out of three. Same shape as the N-call-site carry above, one level up:
the loop/varargs looks like it makes the property structural, and the fixture makes it single-case.

**How to apply:** when a new guard iterates a collection or takes varargs, mutate the ITERATION
(drop elements) rather than the body. A green build names the elements no fixture exercises. The
cheap fix is a parameterised test or one fixture that collides on every element at once.

**How to apply:** whenever a fake implements an SPI method and a test drives the failure by throwing,
open the **shipped** implementation of that method and check how it actually reports failure. If it
returns a value, the catch-based rule covers nothing, and the fix is to branch on the VALUE and
normalise a throw into it. Grep the real arm for `return <Type>.faulted(`/`.failed(`/`.empty()`
versus `throw`. This is the same seam class as [[the @Observes / @Scheduled wiring gaps]] above —
the unit test and the production wiring disagree about which path exists — and it is the third time
in this project a green suite has covered a branch the real collaborator cannot reach.

**Ninth shape: a fake that overrides BOTH overloads identically erases the argument that
distinguishes them.** Measured on `feat/factory-m2-deliver` commit `13ce642` (`/fix`).
`FixCommandSagaTest` overrides `ReviewProjection.appendEvent` at arity 4 AND arity 5, both bodies
`appendedEvents.add(type + ":" + detail)` — so the 5-arg overload's `threadRef` (which the real
method binds into `review_event.thread_ref`, the column the detail projection groups turns by) is
discarded by the fake. Mutating production to call the 4-arg overload left all 7 tests green. This
is NOT the partial-fake trap: every method is overridden, which is exactly why it reads as safe.

**How to apply:** when production picks one of two overloads, grep the fake for the method NAME and
count the overrides. Two overrides collapsing to one recorded string means the distinguishing
argument is unasserted. Record the extra argument too (`type + ":" + detail + ":" + ref`).

**Tenth shape: two independent limits, and every fixture sets BOTH — so either may be made to
depend on the other.** Measured on `feat/factory-m2-deliver` commit `b4f1e44`. `FixRuns.decide`
takes `perFinding` and `perReview`, deliberately two axes because one cannot bound the loop FR-F32
names. Every call in `FixRunsTest` passes both as positive, or both as zero — `(2,5)`, `(2,99)`,
`(2,3)`, `(0,0)`. So cross-coupling them (`if (perFinding > 0 && perReview > 0 && ...)`) left all
14 tests green, meaning an operator who sets one cap and leaves the other unlimited silently loses
the one they set. The same fixture gap hid a second mutant: `> 0` → `!= 0` survives because
"non-positive means unlimited" is asserted only at `0`, never at `-1`, the usual sentinel.

**How to apply:** when a decision takes N independent limits/flags, check whether any fixture sets
one WITHOUT the others. If not, mutate by ANDing them together — a green build proves the axes are
only jointly tested. Same for a range described in prose ("non-positive", "blank or absent"): assert
every named member of the range, not the one representative value.

**And a closed-set CHECK added by a migration is usually asserted by nothing.** V54's
`factory_run_kind_closed` (`kind IN ('BUILD','FIX','SPEC','PLAN')`) could be deleted outright with
the full 1057-test orchestrator module still green: no test ever inserts an out-of-set kind, because
fixtures only ever write values the code already uses. Its sibling constraint in the same migration
had four tests. Grep the migration for `CHECK (... IN (` and grep the suite for a fixture inserting
a value outside the set; the difference is the gap, and it costs one `assertThrows` to close.

**Eleventh shape: a hand-rolled fake that IGNORES its arguments makes every argument-passing
mutation survive.** Measured on `feat/factory-m2-deliver` commit `ca96ad7` (`FixDispatch`).
`FixDispatchTest` builds anonymous subclasses of `FixTargets` and `FixRuns` whose overrides answer
from mutable test fields and never look at what they were passed (`forReview(String reviewId)`
returns `Optional.ofNullable(target)`; `decide(reviewId, findingRef, perFinding, perReview)` returns
`cap`). Every method IS overridden, so the partial-fake trap does not apply and the fixture reads as
safe. Six mutations survived all 8 tests: swapping `MAX_PER_FINDING, MAX_PER_REVIEW` at the call,
raising `MAX_PER_FINDING` to 999, passing `threadRef` to `forReview`, swapping `nextAttempt`'s two
arguments, and swapping `workspace`/`slug` in BOTH `RunIds.of` and the result record. A control
mutation on an asserted component was killed, so the harness bit.

**How to apply:** an argument-ignoring fake means the collaborator BOUNDARY is untested even when
the collaborator itself is well tested. Mutate the CALL (swap or substitute arguments) rather than
the callee. Cheap fix: have the fake record its arguments and assert them once, or make it answer
from a map keyed by the argument so a wrong key returns empty.

**Corollary: a result record's unconsumed components are asserted by nothing.** The same round's
`FixDispatch.Planned` carries `providerType`, `workspace` and `slug` that no production caller reads
yet (`FixDispatch` had zero callers at `2cac818`). Blanking `providerType` and swapping
`workspace`/`slug` both survived. When a slice lands the DECISION before the WIRING, list the
result-type components with no consumer — they are the ones the wiring commit will silently get
wrong.

**And when a fixture holds a value CONSTANT, mutate the value's ORIGIN, not only the argument
order.** Argument-identity mutations (swap two parameters) are the obvious probe against an
argument-ignoring fake, and a review round will usually find them. The survivors they miss are the
ones where production stops CONSULTING a source at all. Measured on `ca96ad7`: replacing
`ScmType.fromProviderType(target.providerType())` with `Optional.of(ScmType.GITHUB)` survived 8/8 —
it simultaneously makes the unrecognised-SCM refusal unreachable AND hardcodes the platform into
`RunIds.of`, which `RunIds`' own javadoc says is in the key precisely so one workspace name on two
SCMs cannot collide. Every fixture used `"github"`, so the constant and the lookup are
indistinguishable.

Second of the same class: a refusal-reason string that no test asserts can be swapped for ANOTHER
cause's wording. `whyNotPushable` has four arms; three have their wording pinned by a
`why().contains(...)` and the blank-branch arm has none, so making it report the fork explanation
survived 8/8 — in a class whose stated purpose is telling the author WHICH cause fired.

**How to apply:** for each value the production path reads, ask "does any fixture vary it?" If not,
mutate it to a hardcoded constant rather than swapping arguments. And count the refusal/error arms
against the number of tests asserting a MESSAGE, not against the number asserting the outcome type.
