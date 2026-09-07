# Code Review State: global / m2-t1-observe-gate

Last reviewed: 2026-09-04
Rounds completed: 1

Round 1 over commit `e6f1a9e` on `feat/factory-m2-deliver` (PR #119), M2 task 1 of 12 — close the
observe-mode gap for every `/command`. Four lenses. Semgrep 2 files, 0 findings. qa reproduced
1016/1016 on a `git archive` copy before reporting and ran every probe there, never in the worktree.

**The gate as written was correct. Everything found was a path it could not reach.** Three lenses
independently reported the same thing: `policy.observeOnly()` had two call sites, and closing the
`/command` hole left two more open — one of them wider than the hole being closed, and one of them
structurally unreachable from where the gate sits.

**The lesson worth keeping.** The task was scoped from a debt entry whose own suggested fix said one
gate "closes all three paths (`/review`, `/finding`, and any future `/command`)". That sentence is
true and was the wrong frame: it enumerates *commands*, and the contract is about *action commands*.
Scoping a fix from the vocabulary of the bug report rather than from the vocabulary of the invariant
is how two of these survived. The invariant is one line of `ReviewPolicy`'s javadoc — "emits NO
action commands" — and it is now asserted over the whole event vocabulary rather than per branch.

## Resolved (fixed in code; do not re-raise)

- [sec/H1, cr/I-2, qa/1] **An author's reply still spent an LLM call and posted a comment in observe
  mode.** The widest of the three paths: an @-mention makes a reply eligible regardless of thread
  ownership AND removes the per-thread turn cap, so where `/review` lost one paid call this loses an
  unbounded number. Not reachable on a never-active deployment (the conversation level defaults to
  report-only), which is why it reads as theoretical — but the realistic case is the operator gesture
  the slider exists for: running active, then flipping to observe to pause the bot, at which point
  every thread is still bot-owned. qa proved it by probe against unmodified `HEAD` rather than by
  argument. Gated in `IntegrationSaga`'s `AuthorReplied` branch, after `markThreadLocation` (where a
  thread sits is a fact about the thread, not an action) — round 1
- [cr/I-1, sec/L1] **The archived-review notice posted a real comment in observe mode**, and no
  placement inside `onManualCommand` could ever have reached it: the archived gate runs in `handle()`
  ahead of the whole switch. Reachable end to end — observe registers with `status='observed'`,
  `archiveRow` refuses only `reviewing`, so an observed row archives cleanly and the author's next
  push or `/review` triggers the notice. Refused in `archivedNotice`, the one builder all three
  triggers converge on; the once-ever claim is taken worker-side, so declining early does not burn
  it — round 1
- [cr/S-1] **A mutation left all five original tests green**: `observeOnly() && allowlistFor(id)
  .isEmpty()`. Every observe case used an empty allowlist, and the ordering case's author is refused
  one branch earlier, so the gate would have been inert on every deployment past first contact while
  every test passed. Closed by a case pairing a CONFIGURED allowlist with a listed author — round 1
- [qa/3, M2] **The note's lane and text were pinned by nothing** — the timeline fake recorded only
  the type, so moving the note to another lane and blanking its text passed 31/31. This matters more
  than the usual untested-string case because the refusal is deliberately silent: the note is the
  operator's ONLY signal, so naming the refused command is the feature — round 1
- [cr/S-2] **The refusal left no durable trace.** The timeline is a 500-entry in-memory ring lost on
  restart, and ROADMAP claimed an operator could tell the two refusals apart — false after a restart.
  Now writes a review-history row too. The reason the sibling authorization refusal withholds one (a
  prober could grow it without bound) **cannot reach this gate**, because it sits downstream of the
  allowlist and only a listed colleague arrives — round 1
- [cr/S-4, qa/2] **`findingCommandIsRefusedInObserveMode` reddened by crashing, not by asserting.**
  Under the mutation it exists to catch it died on an NPE from `ReviewProjection.registered` reaching
  a null `DataSource` — the project's recorded fake-coverage trap, instance eight. Half-fixing it
  relocates it, exactly as the `TokenCount` lesson predicts: the path reaches `registered`, then
  `rootOf`, then `summaryRefOf` in sequence. All three fakes completed — round 1
- [qa/4] The type-distinction property was asserted in one direction only. The converse (an allowed
  author is NOT reported as unauthorized) is now asserted too — round 1
- [qa/M5] **`aCommandWithNoHandlerIsAlsoRefusedInObserveMode`'s javadoc overstated what it pins.**
  Replicating the gate inside every `case` arm passes it, so it does not prove "the gate precedes the
  switch". Softened to what it genuinely pins — that an unenumerated command is covered, which is the
  property that will still be doing work when `/fix` lands — rather than chasing a contrived
  mutation. The placement is argued at the call site instead — round 1
- [cr/S-6] **A guard for the CLASS, not the three instances.** All three defects are "an action
  command escaped under observe mode by a path that is not `onPullRequestEvent`", and per-branch
  tests found them one at a time — which is how the second and third survived the round that fixed
  the first. `observeModeEmitsNoActionCommandForAnyIngressEvent` asserts the contract over every
  ingress event, live and archived, so a branch added later inherits it. Its own coverage is guarded
  by a second test, since an event list that silently lost a case would stay green while covering
  less — round 1
- [rules/1] **The behaviour widened and the DEFINITION did not** — six surfaces still said observe
  governs PR events. This is the exact condition the deleted debt entry named as the deciding factor
  for leaving it alone ("defensible only as long as observe mode is documented as governing automatic
  triggers rather than explicit operator commands — which it currently is not"). Fixed in
  `ReviewModeToggle.tsx` (the tooltip an operator reads while flipping the switch), `ReviewPolicy`'s
  class and predicate javadoc, its boot-log literal, `application.yml`, `.env.example` and
  SMOKE-TEST Mode B — round 1
- [sec/M1, cr/I-3, rules/1b, qa/6] **The admin REST override was real and recorded nowhere.** See
  Dismissed for the decision; the asymmetry is now written into all six surfaces above — round 1
- [rules/5] `docs/HISTORY.md` did not say the retired debt file was deleted, unlike the sentence
  three lines above it — round 1
- [pre-existing, found while verifying] **`ApkUpgradeIsNotCachedTest` could not pass on Windows.**
  Its matrix parser matched `\n` while `core.autocrlf` gives CRLF on disk, and Java's `.` excludes
  `\r` — so `.*\n` never reached the newline and the `include:` block "was not found". Green in CI on
  Linux, red on every developer machine, which makes `testFast` — the pre-commit loop `CLAUDE.md`
  prescribes — permanently red locally. Confirmed by running it against pristine `origin/master`. The
  Dockerfile splitter in the same file already used `\r?\n`; two patterns did not. Now `\R`, and
  mutation-verified: dropping `apkUpgrade` from a matrix entry still fails it — round 1

## Filed as debt (not fixed here)

- [sec/L2] **A settings-read fault falls back to the seed mode, which may be `active`.**
  `AppSettingRepository.get` collapses "unset" and "unreadable" into one empty, so a single failed
  `SELECT` makes one event fail OPEN on a deployment seeded active and later flipped to observe.
  Pre-existing and shared by every `observeOnly()` caller, but this task added three more, so it now
  covers more paths than it did. `techdebt/spire-orchestrator/4-2-a-settings-read-fault-falls-back-to-the-seed-mode.md`

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [sec/M1, cr/I-3] **Gate the admin REST re-run and `POST /api/runs` too.** I initially argued FOR
  gating, reasoning that an HTTP 409 is not a comment so the "silence is forced" argument does not
  apply. Security supplied the fact that overturned it: **the admin re-run is the only route an
  operator has to review a single pull request while still observing.** Gating it leaves "go globally
  active" as the only option and removes the evaluation workflow observe mode exists to serve. Both
  endpoints are `spire-admin`, so the argument that justifies refusing a `/command` — the author is
  gated by the per-provider ALLOWLIST, not by operator role, and an empty allowlist means everyone —
  does not describe them. Kept ungated, and the line is now written down in six places rather than
  left to be re-derived: **SCM-originated triggers are refused; an operator's own authenticated REST
  action is the override.**
- [cr/S-3, qa/5] **Normalize `e.command()` before the gate.** A null renders `"/null"` in the note and
  never throws — verified, no path throws. The gate is consistent with both siblings above it, which
  use it unnormalized too, and that consistency is a better argument for leaving it than tidiness is
  for changing it. Hoisting the normalization to the top of the method is a fine future tidy-up; it
  is not a defect.
- [cr, third position] **Put the backstop in `CommandsEmitter.emit`.** Tempting — one funnel every
  orchestrator `ActionCommand` passes through, and it would have closed all three at once with no
  path able to route around it. **It is wrong and would be a worse bug than the ones it fixes.**
  `ResultSaga` emits to CONTINUE a pipeline that already started, and the mode is a live slider, so a
  flip mid-review would refuse the next stage of an in-flight run and strand it in `reviewing` with
  nothing on the bus to move it on — the permanent-`reviewing` failure this saga's own comment
  records having fixed once. Closed at each decision point instead, with the class guard as the
  structural protection.
- [rules/2] **`onManualCommand` is 37 physical lines against a 30-line rule.** 26 statements and 11
  comments; the overage is entirely comment. The rule's stated purpose (one thing at one level of
  abstraction) is met — the method is guards then dispatch throughout — and extracting a helper would
  satisfy the count while hiding the ordering argument the comment exists to make.
- [rules/3] `IntegrationSaga` is 630 lines against a 300-line rule. Pre-existing (619 before this
  task); tracked already in `techdebt/spire-orchestrator/3-4-three-orchestrator-classes-past-the-size-guideline.md`.
- [rules/4] The log line puts context in the message string rather than structured fields.
  Pre-existing pattern shared by all four refusals in this method; `reviewId` is already an MDC key
  in the prod profile, and `quarkus-logging-json` escapes control characters. Not worth changing one
  of four.
