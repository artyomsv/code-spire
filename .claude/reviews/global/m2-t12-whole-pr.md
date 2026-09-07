# Code Review State: global / m2-t12-whole-pr

Last reviewed: 2026-09-04
Rounds completed: 1

The whole-PR round over `584d61c..HEAD` on `feat/factory-m2-deliver` (PR #119) — 14 commits at the
time of review, 57 files, +6023 lines. Fixes in `47db64c`, `4a6eb8a` and `b537b5a`.

**Three lenses of four.** security-officer, code-reviewer and rules-compliance all reported. **qa
terminated on a session rate limit** partway through preparing a probe, so its report does not exist
and the build lane was run directly instead: `testFast`, `testServices`, the full
`:spire-orchestrator:test`, `spire-ui` vitest and `tsc --noEmit`. That is coverage of the *result*,
not of the question qa was asked (whether the tests are the right ones), so **its questions are open
and should open the next round.**

**One Critical, and it was on the arm with no user.** Every other defect below is a variation on the
same theme: the REST arm was reviewed as a REST arm and got its guard, and the `/fix` arm re-derived
the same lookup without it. On a REST arm a throw is a 500 the caller reads; on a Kafka consumer it
escapes, and the record is redelivered forever while the author who typed the command is told
nothing. That asymmetry is the durable lesson of this round.

## Resolved (fixed in code; do not re-raise)

- [code-quality/C1] `/fix` threw an NPE out of the saga when the FACTORY account had no resolved
  login — `MachineAccounts.resolve` did not guarantee `botUsername`, `ProviderRegistry` stores a
  blank as SQL NULL, and it reached `MachineAccountCredential`'s `requireNonNull`. The guard moved
  INTO `resolve` so both callers get it; `RunResource` reads the registration back on the failure
  path to keep naming which of the two causes it was. `MachineAccountsTest` covers blank, absent and
  the discriminating usable case. Mutation killed — round 1
- [security/M1] the fix claim was keyed on a bare forge comment id, which every ingress passes
  straight through. Two providers, or two self-hosted GitLabs whose note ids both start at 1,
  collide — refusing a legitimate `/fix` while naming another workspace's run id in this review's
  durable history, and dead-lettering the race *after* `pool.select()` spent a rotation slot. V56 is
  unmerged so it was amended rather than stacked: `(review_id, comment_id)`. Two mutations killed
  (the query and the index separately) — round 1
- [security/M3] the allowlist authorising a push matched on username as well as `providerUserId`.
  `/fix` matches on the stable id alone now, with the discriminating test being the same author and
  only the allowlist's spelling changed. Mutation killed — round 1
- [code-quality/I2] `FixRuns` counted `DISPATCH_FAILED` rows, so two broker outages permanently
  exhausted `MAX_PER_FINDING = 2`. Both caps now exclude a dispatch that was never acknowledged, and
  name the CAUSE rather than the status — a run that executed and then died still counts. Two
  mutations killed; the first version of the test survived `status <> 'failed'` and was strengthened
  with a ran-then-failed row — round 1
- [security/L5] V56 admitted a FIX row with a null `comment_id` — counted by the cap, invisible to
  the claim. `CHECK (kind <> 'FIX' OR comment_id IS NOT NULL)`, one-directional so a BUILD row is
  unaffected. Mutation killed — round 1
- [security/L2] the prompt fence was closable from inside it, and the three headers above it were
  unbounded. Both markers are neutered in any value, and each header is bounded to one line. Two
  mutations killed — round 1
- [security/L4] `/fix` proceeded silently on a spend gate that could not read the ledger. Fail-open
  is unchanged (see Dismissed), but the arm now warns in its own log — round 1
- [code-quality/I1] the unrecognised-SCM refusal was duplicated character-for-character in
  `FixDispatch.plan` and `FixRunDispatcher`, and the dispatcher's copy was unreachable and untested.
  `Planned` carries the parsed `ScmType`, so the copy is gone rather than commented — round 1
- [code-quality/I3] `RunLaunch.Outcome.isReArmable()` had no production caller and its test asserted
  the predicate agreed with the type it was derived from. Removed; the tests assert the type — round 1
- [code-quality/I4 + rules/#2] five doc blocks introduced on this branch sat in a stacked pair, so
  the first of each was discarded. Two were the design record itself (`providerType`'s nullability,
  `asFixFor`'s wither rationale). In the two test files the orphan belonged to the test a later one
  was inserted in front of, so those moved down rather than merging — round 1
- [rules/#1 HIGH] the `factory` profile started a run worker the packaged orchestrator could never
  dispatch to: `SPIRE_FACTORY_AGENT_IMAGE_CODEX`, `_FIX_HARNESS`, `_FIX_MODEL` reached neither stack
  and neither `.env.example`. All four keys are in both compose files and documented — round 1
- [rules/#3] the method-size debt entry, which exists "so the rule is not silently suspended for one
  package", did not gain `FixRunDispatcher.dispatch` (87 code lines, 5 parameters). Extended — round 1
- [rules/#4] the two run-unit network entries were one debt filed twice — same root cause, same first
  option, different symptoms and criticality. Merged into one High entry; `SECURITY.md` and
  `UNVERIFIED.md` repointed — round 1
- [rules/#5] the class-size entry carried `~530`/`~450` physical-line estimates for the two factory
  classes. Measured on its own preferred measure they are 350 and 404 code lines, and
  `FactoryRunProjection` crossed 300 on this branch (+66%). Exact figures, in one place — round 1
- [rules/#6] the UI debt entry claimed `spire-ui` referenced no run status and that there was no list
  endpoint at all; this branch built both. Narrowed to the three surfaces that still have none, and
  retitled — round 1
- [rules/#7] a nested ternary in `Runs.tsx`'s For cell, calling `reviewPath` twice and casting the
  result. Extracted as `ReviewCell`, resolved once, cast gone — round 1
- [rules/#8] two `FixRunDispatcher` helpers returned an `Optional` used purely as a control-flow
  carrier and unwrapped with `.get()`. They return the refusal or null — round 1
- [merge-gate] `docs/CONTRACT.md`'s port block listed six of nine SPI ports. `PullRequestSink` was
  this PR's omission; `ThreadSource` and `IdentitySource` predate it. All three added, because a list
  with silent gaps means nothing — round 1
- [merge-gate] `CLAUDE.md`'s Status snapshot and `docs/HISTORY.md`'s M2 entry, with the loop
  qualifier in the same sentence as the delivery claim — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [code-quality/I3, second half] `FactoryPullRequestBody` has no production caller. **Not deleted:**
  it is the orchestrator half of T7, and the step that runs after a fix run pushes — read the result,
  choose a sink, open the request — is M3 work. Deleting it would delete delivered work to satisfy a
  reachability check. The class now says so in its own javadoc, which is the honest form of this
  finding (round 1)
- [security/L4, the behaviour] the spend gate still FAILS OPEN on `/fix` when the ledger is
  unreadable. Refusing on a failed READ turns an outage into something that reads as policy, which
  `SpendGate`'s own javadoc argues at length and the attention row already surfaces. Changing it on
  one arm would also give this project two postures for one gate, which is the drift that bean exists
  to prevent. The log line is the part that was actually missing (round 1)
- [security/M2] `run-worker` holds the schema-owner DB role and the shared Tink keyset, and the
  README's mitigation (a remote daemon) leaves that as the residual without saying so. **Real, and
  not a code change:** it is a deployment-topology gap that wants a least-privilege role and a
  keyset split, which is M5's Kubernetes arm. Escalate it there rather than patching prose here
  (round 1)
- [security/L1] the dlq payload is stored as plaintext. Pre-existing, unchanged by this PR, and
  already covered by the ADR-014 posture (short retention plus broker disk encryption). Not this
  branch's to change (round 1)
- [security/L3] `COMPOSE_PROFILES` is a blind spot — an operator who exports it gets the factory
  without passing `--profile factory`. True, and it is Docker's own mechanism working as designed;
  guarding it would mean the stack second-guessing an explicit operator instruction (round 1)
- [rules/#9] 11 commit body lines exceed 72 characters across 4 of 14 commits. Cosmetic, and the
  history is published (round 1)
- [rules, not raised] `RunListEntry` and `dto-naming.md`: the rule permits only `*Dto`/`*View`/
  `*Payload`, but `DlqEntry`, `TimelineEntry` and `ReconciliationEntry` all predate `master` on the
  same REST surface. `*Entry` is established house style for a read-only list row (round 1)

## Open for the next round

- **qa's questions.** It never reported. Its brief was whether the tests are the RIGHT tests, and the
  direct build run does not answer that. Two specific things it should be asked: whether
  `FixRunDispatcherTest`'s fakes are argument-blind anywhere else (the `RunCredentials` fake returning
  a constant is what hid C1 from that suite), and whether the ADR-040 container test's remaining
  assertions can survive their guards being deleted, given one already did.
- **A live SMOKE-TEST Mode Q pass** — operator action, not automatable here. No column of
  `SCM-MAPPING.md` §8 has been measured against a live API.
