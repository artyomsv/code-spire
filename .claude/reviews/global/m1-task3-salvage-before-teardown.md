# Code Review State: global / m1-task3-salvage-before-teardown

Last reviewed: 2026-09-02
Rounds completed: 1

## Resolved (fixed in code; do not re-raise)
- [cr-C1 / sec-C1 / qa-1] A pushed-then-overran run was stored as `succeeded`, with `ended_at` set and nothing distinguishing it from a clean delivery — so a run whose agent was killed mid-thought reviewed like a finished one. The old bare failure hid delivered work; this traded it for the opposite wrong answer. `RunFinished.agentUnobserved` carries both facts and `V49` gives them the `delivered_unfinished` status, following the shape `V47` and `push_gate_refused` already set twice in this schema — round 1
- [sec-H1 / qa-2] The split existed only in the launcher's fake. `awaitStatusCode` raises one exception type for the timeout, an interrupt, a response with no status and any stream fault, so `DockerRunRuntime` labelled a dropped daemon connection `AGENT_TIMEOUT` — more confidently wrong than the value it replaced. The wall clock is now measured here rather than inferred from the library, a no-status response is `faulted` on its own path, and matching on the exception's message was rejected as coupling to a string upstream can change — round 1
- [qa-1] Nothing asserted the arm reports an overrun at all: reverting both call sites left `spire-runtime-docker` fully green while the feature went inert. `DockerRunRuntimeIT` asserts `overran()` against a real container, which is the only place it can be proven — round 1
- [sec-M2 / cr-I5] The unobserved branch looked only for a push, so a **gate refusal** fell through to `AGENT_TIMEOUT` with its blocked paths discarded and `RUN_PUSH_GATE_REFUSED` could never fire for a long run, and a **forge rejection on the final checkpoint** — FR-F7's own example — was reported as the clock running out around it, dropping the publisher's cause and detail. Ranked exactly as the salvaged branch already ranks them — round 1
- [qa-4 / cr-I8] The throwing-salvage path carried the pushed ref in its failure detail TEXT only, so the run record had no `pushed_ref` and an operator was sent hunting for a branch that exists. It now routes through the same ranking — round 1
- [sec-M3] The launcher never stopped a preserved unit. "An overrun kills the agent" is the Docker arm's private promise, not something the SPI states, and a result now saying the run finished makes an operator rely on it. `cancel` is called and is best-effort: a throwing cancel must not lose the terminal result, because a run stuck in `running` forever is worse than a sandbox the watchdog reaps — round 1
- [qa-3] An overrun that pushed reported `null` usage although the fold had measured it — and a run that spent its entire wall clock is the most expensive one the system produces, so this was the largest single charge the ledger could lose. The result carries it — round 1
- [sec-L1] `killQuietly` swallowed every failure under a comment reading "already stopped", so a kill the daemon refused left a live agent with no log line anywhere. Quiet about `NotFoundException`/`NotModifiedException` only; anything else is logged, without changing the outcome — round 1
- [rules-1] `interpret` reached 39 lines, past the 30 limit. The `unobserved(...)` extraction fixes that and the magic strings with it — round 1
- [rules-2] Cause strings were literals beside a closed enum that exists for exactly this. `RunFailureCause.*.name()` — round 1
- [rules-5] The omitted-paths warning was skipped by an early return on the new path. Extracted and called from both — round 1
- [rules-3] `salvageFailed(...)` built `FAULTED` after the outcomes split, so the name no longer matched the value — and reaching for a name like that on a timeout is precisely the bug the split prevents. Renamed `faulted(...)` — round 1
- [rules-4] A comment explaining why the exit-code sentinel is guarded on both sides was deleted, keeping the guard and losing its reason. Restored, with the Docker behaviour that makes the second half load-bearing — round 1
- [cr-S9 / rules-6] The `Finalization` javadoc claimed the two outcomes "deserve opposite retry answers". Both are non-retryable in the taxonomy, and correctly so: retrying puts a second agent on a branch the preserved unit's agent may still hold. Recorded as considered and declined — round 1
- [cr-S10] `pushedNote(...)` was provably dead on the line that called it (the ref is absent by then) while reading as though the detail still named it. Deleted with its last caller — round 1
- [qa-mutation] My commit message named a mutation that does not catch the ordering test: it duplicated `destroy` rather than moving it, so `teardownNeverPrecedesSalvage` failed on the extra element, not on order. Moving `destroy` ahead of `interpret` passes everything. The claim is withdrawn here rather than left standing in history — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)
- [cr-S?] Make a daemon fault retryable, as `techdebt/spire-run-worker/2-3` suggested. Declined with the reason recorded in `Finalization`'s javadoc and `V49`: a preserved unit may still hold a live agent, so a retry puts a second agent on the same branch. Both outcomes stay non-retryable on purpose (round 1)

## Open (tracked; not fixed in this round)
- `techdebt/spire-run-worker/3-3-a-preserved-workspace-is-invisible-to-the-control-plane.md`, amended: two of its claims were corrected by this round (the run is no longer invisible, and the unit is stopped rather than running), so the remaining cost is disk and a credential in a stopped container, not memory and tokens. Owner is FR-F8's orphan watchdog, Task 6
- `techdebt/spire-run-worker/3-3-a-gate-refusal-does-not-stop-the-agent.md` is unchanged. This round makes a refusal *reportable* when the clock runs out around it; it does not make the gate stop the agent
- `SPIRE_RUN_TRANSCRIPT_RETENTION_DAYS` in `.env.example` and the CLAUDE.md M1 entry remain the documentation gate before PR #96 merges

## Notes
- **The contract snapshot gate stopped this change**, which is worth recording next to `techdebt/spire-contract/3-2`: `RunFinished` is a top-level root, so adding a component was caught. The same gate passed `Finding.category` in silence because it was nested. The golden was re-baselined only after establishing why the addition is wire-safe — an omitted boolean deserializes `false`, which means "observed", exactly what every earlier record meant.
- `techdebt/spire-run-worker/2-3-a-failed-salvage-discards-every-push-the-publisher-reported.md` is deleted: both halves are closed, and the one suggestion not taken is recorded as a dismissal above.
- The Apache-2.0 reference adapter needed a logger for one warning. `System.Logger` rather than a framework, so a module with two dependencies keeps two.
