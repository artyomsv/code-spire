# Code Review State: global / m2-t23-fix-command

Last reviewed: 2026-09-04
Rounds completed: 1

Round 1 over `85c1398` + `13ce642` on `feat/factory-m2-deliver` (PR #119) — M2 tasks 2 and 3, the
`/fix` command vocabulary and its saga handler. Four lenses. Semgrep 7 files, 0 findings. qa
reproduced 1030/1030 orchestrator and 74/74 gateway on a `git archive` copy before reporting.

**The three orderings the slice was built around all held.** Every defect was somewhere else: a
javadoc asserting a behaviour the code does not have, a target the code accepts that cannot specify a
fix, an actor gate the design document already forbids, and four fixture holes that let real
regressions pass.

**Two lessons worth more than any single fix.**

*A test can be killed by a later commit in the same pull request.* `aCommandWithNoHandlerIsAlsoRefused
InObserveMode` was written in T1 driving `"fix"` to prove an UNENUMERATED command is gated. T3 gave
`fix` a handler two commits later, so the case silently became a test of an enumerated command while
staying green. qa proved it dead by narrowing the gate to the enumerated set — the suite still passed.
Vacuity does not only arrive with the test; it can arrive afterwards, from the same author.

*Writing the test qa asked for falsified my own production comment.* `FIND_BY_THREAD`'s javadoc
claimed several rows share a thread ref across rounds and the newest is live. `ATTACH_THREAD_REF`
orders by `(thread_ref = ?) DESC`, so a row already carrying the ref beats the newest unattached one —
deliberately — and **at most one row ever carries a ref**. That is also why qa's `DESC`→`ASC` mutation
survived: the match set has one element. The honest fix was to correct the reasoning, not to invent an
assertion that would make a false claim look tested.

## Resolved (fixed in code; do not re-raise)

- [sec/H1] **An empty author allowlist admitted everyone to a command that pushes code.** The
  allowlist means "review everyone" by deliberate design — right for one spend-capped model call,
  wrong for a branch pushed as the machine account — and `allowlistFor` answers `List.of()` for an
  unresolvable provider too, so that is a second everyone-answer. `AUTONOMY.md` Rule 3 already names
  the threat in as many words ("a drive-by contributor … the factory writes and merges their code
  using the operator's credentials") and rules the factory's actor list must be its own. `/fix` now
  denies by default; `/review` and `/finding` are untouched. Taken in-round rather than deferred for
  the reason security gave: the tests encoded "empty = allowed" as the PASSING case, so every hour it
  stayed the gate got harder to change — round 1
- [cr/C1, rules/1, qa/1, sec/2] **Three places claimed the refusals SPEAK and nothing was emitted.**
  `/finding`'s refusal emits `RefuseFinding` and reaches the author; there is no `RefuseFix` anywhere
  in the tree. The corroborating detail is the one that settles it: the test fixture never assigns
  `saga.commands` or `workerCredentials`, so it could not have supported a speaking refusal — evidence
  of unimplemented, not merely unasserted. **Reworded rather than built**: the reply needs a new
  `ActionCommand` member, a contract-snapshot update and a worker handler, all of the dispatch slice's
  surface. Emitting a whole new wire type so a javadoc stops lying is the tail wagging the dog. The
  javadoc now says so, and the refusals gained the durable row the observe gate's own argument
  demands — round 1
- [cr/I2] **A `/finding`-filed finding was a valid `/fix` target with no description.** Its `message`
  and `suggestion` are NULL by design (DATA-MODEL §5), so FR-F27's "complete task specification" would
  have been a severity, a path and a line — and `TargetFinding` carried no `origin`, so the dispatch
  could not have detected it either. Refused here, because by dispatch the target is accepted and the
  only options left are paying for a run on an empty spec or retracting — round 1
- [cr/I3, rules/2, qa/5] **`"RESOLVED"` was a literal where `FindingVerdict.Status` exists**, is
  already imported in that file, and is what the write side spells. `review_finding.verdict` carries
  no CHECK constraint, so a rename would keep compiling and silently stop matching — on the guard that
  decides whether a paid agent run is dispatched — round 1
- [cr/I4, qa/3] **This branch's own T1 guard went vacuous.** See the lesson above. Now drives
  `"nonesuch"`, and the javadoc records how it died so the next reader does not repeat it —
  round 1
- [qa/M2] **Filing the durable row under the branch ref instead of the conversation root passed every
  test.** A NEW trap shape, and worse than the usual one: the fixture overrides BOTH `appendEvent`
  overloads — which is exactly why it read as safe — while both bodies discarded the argument that
  distinguishes them. The real 5-arg method binds it into `review_event.thread_ref`, the column the
  detail projection groups a conversation by. The recorder now captures the ref and it is asserted —
  round 1
- [qa/M1, qa/M3] **The finding's description was asserted on two of its four parts.** `startLine ==
  endLine == 44` in the fixture made the two components interchangeable, and the assertion checked the
  path and the number but never the severity. Fixture is `44, 48`; the assertion is an exact match,
  which closes both at once and also pins WHO asked on the durable row — round 1
- [cr/I1] **The no-finding refusal asserted something false on Bitbucket.** That SCM threads by
  immediate parent and only the bot's comments get a `review_thread` row, so a `/fix` typed as a reply
  to another HUMAN's reply matches nothing while the finding sits visibly a few comments up. `rootOf`'s
  javadoc documents the gap and calls it "harmless for the anchor" — it is not harmless for a message
  that makes a claim about the reader's repository. Now says what it could not do. The functional gap
  is filed, not absorbed:
  `techdebt/spire-orchestrator/3-3-fix-cannot-find-its-finding-two-replies-deep-on-bitbucket.md` —
  round 1
- [qa/4] **`findByThread` was asserted by nothing** — faked in every saga test while containing real
  SQL, a deliberate throw and a row mapping. Two tests in the existing `FindingProjectionTest`; the
  first is what falsified the production comment — round 1
- [rules/3, cr/Q2] **`FindingProjection`'s class javadoc said "Nothing here is a source of truth"**,
  which the new read makes false. Scoped to writes, with the exception named — round 1
- [rules/8] **All four refusals were typed `skipped:`**, flattening a distinction `/finding` makes:
  `skipped:` when a precondition means the command could not be evaluated, `refused:` when it was
  understood and declined — round 1
- [sec/M1] **`args` was attacker-typed, carried on the wire and unspecified.** The rule is now written
  into `CommentCommands.FIX`'s javadoc while it is still cheap: `/fix` takes no arguments and the text
  after it must never reach a prompt's instruction part. Feeding it to an agent holding a clone and a
  push token would let a commenter author instructions to it — round 1
- [qa/severity-blank] `FindingRows` writes `severity` as `""` when null, so the description could
  render with a leading space and no severity — round 1
- [cr/S5] The `/fix` parity case omitted the `prId` assertion its `/finding` sibling makes — round 1
- [rules/4] `IntegrationSaga` is past the size cap on BOTH measures (724 physical / 425 code) and the
  existing entry named five classes, not it. Added with the measured numbers — round 1

## Deferred to the dispatch slice (recorded, not forgotten)

- [sec/M2, cr/S2] **The durable `FixRequested` row is not idempotent on `commentId`.** A redelivered
  webhook writes it twice. No money moves yet, so this is storage rather than spend — but the forward
  requirement is the sharp part and is why it is recorded rather than fixed here: **the dispatch's
  spend claim must key on `commentId`, not `(reviewId, threadRef)`.** A second genuine `/fix` on the
  same thread after a failed run must be allowed; the same comment redelivered must not pay twice.
- [cr/C1] The in-thread refusal reply — a `RefuseFix` command, its contract-snapshot entry and a
  worker handler.

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [cr/S4] **Extract the four gates into a `FixTargets` collaborator**, mirroring `ConversationFindings`.
  The argument is good and the precedent is real. Not taken in a review round: it is a refactor of
  working code whose shape will change again when dispatch lands, and doing it now means reviewing the
  same logic twice. Worth doing when `requestFix` grows its dispatch half.
- [cr/S3, rules] **Make the refusal reasons a closed set** like `CapRefusal` / `RunFailureCause`. Those
  earn their enums by crossing a wire and meeting a CHECK constraint; these are prose read by a human
  in one place. It becomes right when the refusal is ALSO posted to the SCM and each reason needs two
  renderings — which is the same slice as the reply, so it lands with it.
- [rules/6] `target.get()` after an `isEmpty()` guard rather than `orElseThrow()`. The global rule is
  unconditional, but this is the established house shape — thirteen same-guard uses in
  `spire-orchestrator/src/main` alone. Singling out one line makes the codebase less consistent, not
  more correct.
- [rules/9] Nine commit-body lines are 73 characters against a 72 wrap. Already pushed; not worth a
  rewrite, and later commits are within it.
- [qa/uncovered] `ACKNOWLEDGED` / `SUPERSEDED` / `UNCHANGED` verdicts are untested and all fall through
  as fixable. That is the documented rule — only `RESOLVED` closes the door — and a parameterized test
  over the enum would assert the absence of behaviour. The enum binding (above) is what protects the
  one value that matters.
