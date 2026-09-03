# Code Review State: global / m1-task9-idempotent-dispatch

Last reviewed: 2026-09-03
Rounds completed: 1

Covers commit `567a125` (Task 9 — idempotent dispatch, and ambiguity that fails closed, FR-F10).
Four lenses: security-officer, code-reviewer, rules-compliance, qa.

The round's headline, because it is the useful part: **the commit argued at length that ambiguity
must be the default, and then implemented the opposite.** `mayHaveLanded()` asked
`cause instanceof RetriableException`, so every unrecognised failure answered "definitely did not
land" — the re-armable direction. The javadoc immediately above said the reverse. A test asserted the
inverted behaviour and its comment defended it as "delegating the judgement to the client rather than
guessing", which reads as principled and conflates two different questions: *is a retry safe?* is not
*did the append happen?*

Two reviews disassembled the shipped Kafka client rather than reasoning about it, and named real
non-retriable exceptions raised after a record is on the wire. That is what turned a plausible-looking
classification into a demonstrated money hazard.

## Resolved (fixed in code; do not re-raise)

- [security/M1, qa/3] **The classification's default was inverted from the principle the class
  states.** Replaced by an ALLOWLIST of causes that cannot be raised once a record has reached a
  partition; everything else, including anything a future client version adds, is ambiguous. Named
  by the reviews and now all ambiguous: a producer closed mid-send (`KafkaException`),
  `UnknownServerException`, `InterruptException` — which the sibling branch in `KafkaSends` already
  treated as ambiguous when it arrived as a `java.lang.InterruptedException`, so the file contradicted
  itself six lines apart — and `DuplicateSequenceException`, whose literal meaning is *the broker
  already has this record* and which was being reported as a certain miss — round 1
- [qa/5] **`KafkaSends.awaitAck` had no test at all**, and QA proved it rather than asserting it:
  rewriting the elapsed-wait branch to report a definite miss — verbatim the pre-commit duplicate-run
  behaviour — left all 949 orchestrator tests green. `KafkaSendsTest` now drives the seam; `awaitAck`
  takes its timeout as a parameter so the elapsed branch costs milliseconds — round 1
- [code-quality/2, security/M3] **Resolving "it ran" made the row deaf to the run's own result.**
  `failed`/`DISPATCH_UNCERTAIN` was outside the live set, so a real `RunFinished` touched zero rows
  and the branch reached the remote with no row pointing at it. The asymmetry gave it away: resolving
  *"it never ran"* left the row live, so the answer that ASSERTS a run is executing was the one that
  discarded its outcome. Security added the half the code-quality patch alone would have missed —
  `started()`'s reopen predicate needed the same widening. Re-arming stays blocked by `queued()`'s own
  guard, verified directly — round 1
- [code-quality/1, security/L2] **An uncertain run could not be cancelled** — the only live state with
  no stop lever, and the one whose entire premise is that a paid agent may be executing right now. A
  `queued` run, where nothing can be running, accepted one — round 1
- [security/M2] **`acks` was unset on every outgoing channel**, so SmallRye's default of leader-only
  applied — verified by disassembling the connector's config class. A lost ack after the 201 leaves a
  row `queued` for ever with no resolution path, which is FR-F10's mirror image. `acks: all` on the
  two run channels, which also re-enables producer idempotence. The review pipeline's three channels
  are filed rather than changed inside a factory commit — round 1
- [code-quality/9, security/L1, qa/4] **V51's `DO` block kept the silent success its own comment
  rejects a name guess for.** QA proved it: neutering the `LIKE` made the migration apply with exit 0
  and no notice, and the first uncertain write then failed against the surviving constraint. Now
  `SELECT ... INTO STRICT` with both `NO_DATA_FOUND` and `TOO_MANY_ROWS` raised explicitly — round 1
- [code-quality/5] **Three messages asserted a publication that may not have happened.** The
  unclassified branch is reachable — a terminated channel or full emitter buffer throws before a
  record is offered to the producer — so the log, the 503 and the stored detail all claimed the
  command was on the topic. They say "dispatched" now, which is true of every input to the branch —
  round 1
- [code-quality/6] **The resolution 409 named a status read before the change**, so on the very race
  the feature exists for it read "run … is dispatch_uncertain, not awaiting a dispatch resolution".
  Re-read after the refusal — round 1
- [code-quality/13, rules/1] **`resolveDispatch`'s boolean flag split** into `resolveAsNeverRan` /
  `resolveAsStarted` over one private write. Both reviews, same reasoning: the answer was branched on
  three times in one request, and `false` at a call site is the answer that permanently forbids the
  retry — round 1
- [rules/2] **`RunFailureCause.DISPATCH_FAILED`'s javadoc read "The broker never acknowledged the
  dispatch"** — which is exactly `DISPATCH_UNCERTAIN`'s meaning, one line below, in the file that IS
  the closed vocabulary — round 1
- [rules/3] **`resolveDispatch` wrote the enum's name on one branch and a raw string constant on the
  other**, five lines apart. Every dispatch write now goes through `RunFailureCause`, closing half 1
  of `techdebt/spire-run-worker/4-1-…`, whose own text named Task 9 as its trigger — round 1
- [code-quality/3, code-quality/4, code-quality/7] **`LIVE`'s javadoc no longer described `LIVE`**;
  `update`'s javadoc was orphaned by inserting a block ABOVE an existing one (the same shape filed as
  debt hours earlier, arriving from the other direction); `RunAttentionRows`' class javadoc described
  one of its two rows and asserted an acknowledgement mechanism the new row deliberately does not use.
  Also found and fixed while there: `queued()`'s two javadoc blocks had been documenting a record —
  pre-existing — round 1
- [rules/5] **`RunAttentionRows` duplicated nine lines of cap-and-overflow scaffolding**; the refusal
  collector is now its own method, symmetric with the new one — round 1
- [code-quality/11, code-quality/10] **The stored detail named no endpoint**, though it is the only
  one of four messages about the condition that survives a page reload and there is no factory UI. It
  is per-run now, and phrased as the consequence rather than as the order "Do NOT retry", which stops
  being true the day a resolution UI exists — round 1
- [code-quality/12] `"its result was lost"` stated the code's inference as the operator's finding —
  round 1
- [rules/8, rules/9] **The resolution body is a record with a boxed `Boolean`**, because the
  absent-vs-false tri-state is load-bearing and a primitive would default the unanswered case to the
  answer that forbids the retry. Cancel and steer stay on maps: their fields are optional strings with
  real defaults — round 1
- [rules/7] `RunResource.dispatch` was exactly at the method-size cap and doing two jobs; split into
  `recordDefiniteMiss` / `recordUncertainDispatch` — round 1
- [code-quality/14] Each guarded call site now says that a zero row count there is a refusal rather
  than the redelivery `update` usually reports — round 1
- [qa/6, code-quality/16] Coverage QA named: `{"neverRan": false}` over HTTP, and that acknowledging
  does NOT silence an uncertain row — an invariant stated in three places and enforced in two, and
  asserted by nothing — round 1
- [rules/4] `techdebt/spire-orchestrator/3-4-…` quoted 302 and 316 through four more tasks that grew
  both files. Corrected, and the entry now records the measure argument rather than leaving it to be
  re-litigated — round 1
- [rules/5] `CLAUDE.md`'s factory migration range V42–V47 → V42–V51 (stale before this commit, by
  three) — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [code-quality/15, rules/6] **Do not extract `FactoryRunProjection` during M1.** Both reviews reached
  this independently. Code lines are ~203 against ~450 physical — the file is around 40% comment, and
  that comment is load-bearing rationale. The seam is three-way and all three parts depend on the
  shared status vocabulary, which is the property V51 exists to defend. The recommendation is recorded
  on the debt entry so it is not argued again each round; revisit when the read side grows a
  list/filter query.
- [code-quality/8] **A dispatch whose own status write fails leaves the row `queued` with no exit.**
  Real, pre-existing (`dispatchFailed` had it), and needs a database fault concurrent with a broker
  fault. Filed as `techdebt/spire-orchestrator/3-3-a-dispatch-whose-status-write-fails-burns-its-subject.md`
  rather than fixed inside a fix batch this size.
- [security/M2, remainder] The review pipeline's three outgoing channels still ack on the leader alone.
  Filed as `techdebt/spire-orchestrator/4-2-…` — changing the review path's durability inside a factory
  commit is the scope creep that makes a bisect useless, and the exposure there is a review that never
  runs rather than money spent twice.
- [security/L3, qa] A synchronous emitter refusal (terminated channel, full buffer) is filed as
  uncertain although nothing was sent, and blocks the subject until an operator resolves it. Correct by
  the stated rule and now stated in the javadoc; the operator cost is understood and accepted.
- [rules/10] Commit body wraps at ~80 rather than 72. House style across the branch, and the user's
  global rules constrain only the subject line.
- [qa] `RunAttentionTest.aRunThatStartsAfterAllClearsItsOwnRow` passes under a mutation that removes
  the whole row source, because it asserts an absence. Not vacuous — the `started()` mutation fails it —
  but load-bearing only alongside its sibling. Accepted as is.
- [qa] The `RunAttentionRows` overflow row for uncertain dispatches is untested. So is the refusal
  overflow beside it, so this is a pre-existing gap rather than a new asymmetry.
