# Code Review State: global / m1-task7-cancel-and-steer

Last reviewed: 2026-09-03
Rounds completed: 1

Covers commits `710a6ff` (Task 7 — `cs.run-control` and a cancel that cancels, plus the Task 6 fix
batch) and `d21b335` (Task 8 — steer, capability-gated). Four lenses: security-officer,
code-reviewer, rules-compliance, qa.

The round's own summary, because it is the useful part: **the feature stopped one layer short of the
operator in four separate directions, and each was invisible from inside the module.** A cancelled
run was projected as `failed`; the operator's own transcript line was silently dropped by the store;
a cancel reached a random replica; and nothing published to the topic at all, so neither command was
reachable by any operator. Every one of them passed a green suite.

## Resolved (fixed in code; do not re-raise)

- [security/H1] `run-control-in` shared one consumer group, so Kafka delivered each cancel to a
  single member chosen independently of the work topic's assignment — the cancel landed on a replica
  not running the run and was dropped as late. Group id is now per instance (`${quarkus.uuid}`), which
  is a broadcast; `latest` becomes correct by construction rather than incidentally — round 1
- [security/H2] Worker notes were numbered by a process-wide counter while agent events were numbered
  per run from 1; both write into `run_event` whose PK is `(run_id, seq)` with
  `ON CONFLICT DO NOTHING`, so whichever arrived second was discarded in silence — and the loser is
  usually the operator's line, since an agent stream runs to hundreds of events. Notes are now
  allocated by the run's own `RunEventStream` (`RunNotes`, reached through the registry), so there is
  one sequence authority per run. **Basing notes above the agent's cap was rejected**: the live tail
  reads `seq > ?`, so a note jumping the range would advance the cursor past every later agent event
  and kill the tail — round 1
- [code-quality/1] A cancelled run was projected `failed`, telling whoever pressed the button that
  the thing they stopped had broken. `'cancelled'` had been in the V43 status set since M0 with
  nothing writing it — round 1
- [code-quality/3, qa] Nothing published to `cs.run-control`: cancel and steer were reachable only by
  hand-producing to the topic. `POST /api/runs/{runId}/cancel` and `/steer` added with a
  `run-control-out` channel. The endpoint is also where refusal becomes honest — under a broadcast
  group a listener cannot tell "not running here" from "not running anywhere", but the endpoint reads
  the row: 404 for unknown, 409 for finished — round 1
- [security/M2, rules/9, qa] `registry.find` then `registry.harnessOf` were two unsynchronised reads;
  a run ending between them made the second null, `HarnessRegistry.forName(null)` threw, and it
  escaped into a channel with `failure-strategy: ignore` — dropped with no log and no note, the exact
  silence the class exists to remove. One read (`liveRun`) plus a `catch (RuntimeException)` backstop
  in `onControl`. QA reproduced it with a probe before the fix — round 1
- [qa, code-quality/6] `io.smallrye.common.annotation.Blocking` has no `ordered` attribute and Quarkus
  maps its absence to ORDERED, while the class javadoc claimed the opposite: a cancel hung on a wedged
  daemon blocked every other cancel. Now `io.smallrye.reactive.messaging.annotations.Blocking(ordered
  = false)` — round 1
- [code-quality/5, qa] `run-control-in` declared only topic/group/offset and inherited the 60s ack
  default the work channel was explicitly raised from; its handler is a docker call with no timeout,
  and a wedged daemon is exactly when cancelling matters. Explicit threshold plus no prefetch, and
  `RunAckBudget.verifyControl` refuses to start below it — round 1
- [code-quality/2, rules/11, rules/12, security/L5, qa] `DlqTopics` routed `CancelRun` to
  `cs.run-commands` (where the dispatcher cannot act on it) and had no entry for `SteerRun` at all, so
  it fell through to `cs.commands` — the review worker's topic, the precise defect that class's own
  javadoc records fixing once. New `RUN_CONTROL` set. The dispatcher's `CancelRun` branch now warns
  that control belongs on the other topic instead of logging "cancel requested" and acking — round 1
- [code-quality/7] `registry.forget` / `keeper.settle` were not in a `finally`; a throw from
  `asCancellationIfCancelled` (which reaches `SecretScrub`, unguarded) would leak a registry entry,
  and `isExecuting` is the watchdog's one absolute exemption — a credential-bearing sandbox
  permanently unreclaimable — round 1
- [rules/2] `LOG.errorf` passed `e.getMessage()` instead of the exception, in a file whose sibling
  catch two lines below does it correctly — round 1
- [rules/3, rules/4, code-quality/8] Three orphaned javadoc blocks (`DockerRunRuntime.salvage`,
  `RunLauncher.unobserved`, `RunLauncher.observe`) — a method inserted between a javadoc and the
  method it described, so Java discarded the first block. The remaining 22 repo-wide are filed as
  `techdebt/global/4-2-…` with the scanner — round 1
- [qa] `aRunIsRegisteredWhileItExecutesAndForgottenAfterwards` asserted only "forgotten"; deleting
  `registry.register` left it green. Now asserts `isExecuting` from inside the launch — round 1
- [qa] `aRuntimeThatCannotStopTheAgentStillReportsTheRefusal` could not fail when the stop was
  deleted: cancel is never called, the fake never throws, and both assertions hold anyway — round 1
- [qa] `RunTranscriptTest.anAgentEventCarriesItsRefusalBackToTheCaller` asserted the opposite of its
  own name, and the asynchronous refusal the launcher's gap warning depends on was exercised by
  nothing. Renamed, and the fake can now fail its future — round 1
- [qa] The control listener's `HarnessRegistry` fake ignored its argument, which made the real
  registry's null-rejection unreachable from the suite — so the race above passed all sixteen cases.
  It now honours the name — round 1
- [qa] `DockerRunRuntime.steer` was reachable from no test; an empty body failed nothing — round 1
- [qa] **Nothing asserted the property the topic exists for.** Repointing `@Incoming` at
  `run-commands-in` left all sixteen listener cases green while cancel returned to being a no-op. New
  `MessagingChannelsAreDeclaredTest`, in the `ScheduledWorkIsDeclaredTest` mould — round 1
- [rules/7] `"STEER_REFUSED"` as a bare literal four times; [rules/1] a four-parameter `note` with a
  boolean flag, split into named call-site helpers; [rules/8] duplicate `RUN_ID_MDC`; [rules/14–17]
  unused import, whitespace-only line, stray YAML blank, inline fully-qualified names — round 1
- [rules/13] The plan ticked Task 8 and left Task 7 unticked; Tasks 0–4 were also delivered and
  unticked — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [rules/M3 on Task 4] "Narrow `RunCharges`' `catch (RuntimeException)` to `IllegalStateException`."
  Declined, and the code says why in place: the catch deliberately covers `IllegalArgumentException`
  from `PricingMode.valueOf` / `TokenType.valueOf` on a stored value this version does not recognise.
  Narrowing reinstates the redelivery loop the guard exists to prevent, on a run whose money is
  already spent. This directly contradicts qa's Finding 2 from the same round, which asked for the
  opposite and was implemented.
- [qa/9 on Task 7] "`run_event` has no unique constraint on `(run_id, sequence)`, so nothing breaks
  today." False — `V48__run_event.sql:31` is `PRIMARY KEY (run_id, seq)`. The security lens read the
  migration and was right; this is recorded because two lenses disagreed and the wrong one was the
  reassuring one.
- [code-quality/a] Rename `stopAgent` to `stopUnit`, or add a narrower SPI verb. Real but not now:
  `runtime.cancel` does stop the publisher too, and it is harmless only because `PublisherMain` exits
  immediately after its single refusal line. Deferred rather than dismissed — it belongs with the
  publisher protocol work, not with a rename here.
- [security/L1] Steer text is operator-authored and viewer-readable. Partly mitigated for free: notes
  now go through `RunEventStream`, which scrubs like every other line, and a test pins it. Whether
  STEERED text should be admin-only is a product call, not this round's.
- [rules/10] `RuntimeCapabilities.steering` is read by no production code. Left as is: the gate is
  deliberately the harness's declaration, and the runtime's throw is the backstop. Deleting the field
  is a separate decision about the SPI.
- [rules/5, code-quality/6] `RunDispatcher` at 321 lines and `observe` at 93. Both genuine, neither
  fixed here — a refactor of the dispatcher during a fix batch this size trades a reviewed change for
  an unreviewed one.
- [security/L2] `CancelRun.reason` is unbounded. It now reaches the transcript, where
  `RunEventRecord` clips it; the wire bound is worth adding with the next contract change.
