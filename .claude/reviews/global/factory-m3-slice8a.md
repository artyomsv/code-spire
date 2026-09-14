# Factory M3 slice 8a — prepared tasks and distinct build journeys

Round 11 accepted slice 7 on 2e42ffca with no findings and independently verified criteria 2 and 4.
Criteria 2–7 are verified. This slice supplies criterion 1 for independent review. The measured
validation and production mutation inventory are recorded below.

## The criterion boundary

`WorkItemJourneyIT.threeProfilesProduceDifferentVisibleJourneys` creates three otherwise identical
TEST tasks under an autonomous ceiling. It uses real source/actor/profile registrations, tracker
reads, encrypted event history, SQL projections, HTTP detail and the existing M2 launcher. Only
the final broker emitter and the explicitly declared test publication capability are replaced.

- Suggest records the fetched artifact references and stops at BUILD=off, with no gate, run or PR.
- Assisted stops at an OPEN PLAN gate with zero runs. Its dashboard answer records the verified
  human, note and channel; one BUILD attempt and one associated dispatch follow.
- Autonomous reaches one BUILD dispatch with no approval gate. Repeated drains create no extra run.

The named UI test, `renders distinct suggest assisted and autonomous journeys`, receives no profile
names. It checks actual statuses, phases, zero/one run counts, approval and run links, and the
recorded human decision after assisted approval. The required approval-policy and identical-status
mutants each fail their named test alone and pass after scratch restoration. Additional UI mutants
remove the phase, run count, gate and recorded decision independently.

Production item builds remain capability-unavailable until slice 8b supplies a trusted publication
hold. Both the default transport and its dispatch guard have direct tests; the journey fixture's
explicit test capability does not stand in for them. A build result reaches VERIFY and reports its
missing capability. No specification generator, multi-step planner or M4 verifier is claimed.
Draft/regular delivery and real-container publication proof remain the separate slice 8b work.

## Artifacts, decisions and dispatch

Administrators can read a specification version before creating the plan ticket. The form exposes
its digest and a JSON plan template, then checks both references before registering their versions
with the displayed item revision. The server derives the operator from the verified session.
Editing invalidates the checked references; late responses and saves cannot update a later screen.

All three tracker arms resolve scoped issue keys to stable identities through the selected source
account. They inherit the shared reference cases. Reads reject a foreign source, a mismatched
returned identity, unavailable/blank/oversized content, changed digests and invalid single-step
plans. The plan binds the exact specification digest. Artifact bodies remain transient; the event
stores only references, digests and execution coordinates. Gate bindings include both artifact
identities/versions plus the base branch, full commit, harness and model. Replacement supersedes the
prior gate. Outages record no approval; changed content requires a new decision.

M2 assembly selects the explicit FACTORY identity, priceable model and harness credential pool,
checks deployment spending, and carries the bounded wall time and protected paths. V69 binds one
factory run and durable effect to a phase attempt. The transaction commits an uncertain claim
before the emitter can observe it. A definite miss or explicit authenticated never-ran resolution
can re-arm that same association. An ambiguous acknowledgement never authorizes an automatic resend.

Current policy is compared with the persisted PHASE_STARTED admission decision. Intake can record
a newer observation while dispatch is pending; that observation cannot rewrite the authorization
which admitted the attempt. The corresponding test lowers BUILD to off, reconciles intake, proves
the source and actor still eligible, then requires a durable refusal with zero runs.

## Result recovery and accounting

The result saga projects and charges before handing an associated result exclusively to the item
bridge. The direct standalone-proposal guard is tested separately with an otherwise eligible
pushed head; it must return before contacting the forge or writing a proposal error.

The encrypted result inbox retains its first terminal result. Tests stage recovery before aggregate
completion and after completion but before inbox acknowledgement. One case already has a distinct
active VERIFY attempt when the old BUILD result redelivers; the old completion is acknowledged
without changing that new attempt. Failed, refused and unobserved builds do not advance to verify.
Measured wall time, cost and the existing M2 agent-call unit survive readmission; unmeasured potential
spend blocks further work and is never converted into a known zero. Proven pre-agent failures reuse
M2's existing cause-and-usage classification: no call was bought, so readmission after repair is
allowed. These are durable-state tests on
real PostgreSQL, not additional process-kill or live-worker claims.

## Defects found during verification

- An overflowing JSON schema version could narrow to integer 1. A discriminating large-integer
  case now requires exact version-one integer validation. Malformed plans report invalid input.
- Comparing dispatch against the latest observed policy could authorize a pending build after
  intake had recorded a lowered ceiling. Dispatch now binds the original attempt decision.
- Treating every absent ledger row as unknown usage would permanently block readmission after a
  proven pre-agent rejection. The bridge now reuses M2's existing narrow no-purchase classification.
- Removing a configured harness image after preparation raises M2's real HTTP validation exception.
  Dispatch now converts that exception into a durable configuration refusal, releases its reservation
  and leaves no pending retry. The test changes only deployment configuration and uses the real parser.
- The full UI stylesheet contract found an undefined button class. The form uses the existing
  button style and its existing responsive fieldset layout. All route assertions remain intact.
- A new inline array type import added a Semgrep parser span. It now uses a static type import;
  a separate pinned scan of current and accepted 2e42ffca API files confirms zero findings and
  exactly the same four optional-field parser spans. The new import span is gone.

## Validation and exact cleanup

Forced `testFast` and `testServices`, followed by `assemble`, passed sequentially with JDK 25:
**3739 Java tests across 425 suites and 30 modules**, zero failures and 1 existing Windows symlink
privilege skip. The complete orchestrator suite, architecture checks and packaging passed again
after the final late-configuration refusal fix. The full UI passed **711 tests across 88 files**; all 40 route cases passed shuffled
seeds 814, 2718 and 5192. The final form/journey/style checks passed 12 tests and the production build.

**92 isolated checks cover 92 distinct production mutations**: 77 Java-main and 15 UI.
Every check produced exactly one selected assertion failure, restored the scratch bytes and passed
again. The required approval-bypass mutation was repeated on the final journey fixture, whose
run-count assertions precede its presentation checks. No test fixture was mutated.

Pinned Semgrep 1.172.0, CI digest `65dcd4408adda7c183a6b4550cb1e9b19f7f627a6fbb7e0559bd466bedc44d7b`,
with p/default and p/secrets reports **zero findings across 46 changed source/test/configuration/migration
files**, including Java tests. Final LF-normalized source hashes match the scan. The sole parser
warning is api.ts's four existing optional-field spans; a separate accepted-2e42ffca comparison
reproduces exactly their source text and zero findings. No suppression was added.

`WorkFixture.cleanWork` binds each owned item ID when deleting run effects, RUN ledger rows,
associated factory runs, tracker/gate/phase/Kafka projections and event history. It then deletes
only the fixture's sources, actors, policy pins, generated profiles, repository bindings and account.
`WorkPreparedFixture.cleanWork` removes its exact harness credential and model ID; rates cascade
with that model. Every synthetic identity and artifact is TEST-prefixed. Testcontainers owns the
isolated service stack. No dev database, second backup, live tracker write or live run worker was used.
