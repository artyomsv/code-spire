# A cancel for a run that has not started yet is accepted and dropped

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Medium |
| Location | `spire-run-worker/src/main/java/dev/codespire/run/RunDispatcher.java` (registration order); `RunControlListener.java`; `spire-orchestrator/.../factory/RunResource.java` (the 202) |
| Found during | PR #96 whole-PR review (security H1) |
| Date | 2026-09-03 |

## Issue

`POST /api/runs/{id}/cancel` answers **202 Accepted** unconditionally. The worker's control listener
looks the run up in the in-memory `RunRegistry` and, finding nothing, writes a debug line and
returns. Nothing durable is recorded.

`RunRegistry.register` runs only **after** `runtime.create()` returns, and `create` blocks on the
init container's clone — up to fifteen minutes on a large repository. So there are three windows in
which a cancel is accepted and has no effect at all:

1. the command is still queued on `cs.run-commands` and unconsumed;
2. the dispatcher is inside `create`, cloning;
3. dispatch was uncertain (the ack ladder's retry window) and the record may still be redelivered.

A run cancelled in any of them starts anyway and spends its whole wall clock and its whole model
budget.

Two prior task reviews each concluded this window was closed, and each was right about the half it
could see: Task 7 owned the *executing* case (the listener does stop a registered run), Task 9 the
*dispatch-uncertain* one. Neither could see that the gap is **before** either.

## Risks

An operator who cancels a run — because it was launched by mistake, against the wrong branch, or on
a repository they have just realised is sensitive — is told it worked. It did not. The money is
spent, the agent runs with full shell access against a real clone, and the only remaining stop is a
consumer-group reset.

It compounds with the spend cap, which reads `factory_run` and so cannot see a queued command
either: a backlog accepted while the window read empty cannot currently be stopped at all.

## Suggested Solutions

1. **A durable cancel slot in `run_claim`**, taken by the cancel endpoint (or by the control
   listener when it finds no live run) and checked by the dispatcher **before** it creates anything.
   This is the one that closes window 1 and window 3, because it survives a redelivery.
2. **Register the run before `create`**, not after, so a cancel arriving during a fifteen-minute
   clone stops the unit. On its own this closes only window 2.
3. Failing both, **stop answering 202** for a run the orchestrator cannot see as live, and say so —
   a refusal an operator can act on beats an acknowledgement that is not true.

Whichever is taken needs a test that fails when the dispatcher's pre-create check is deleted;
without that this reappears as exactly the same "two reviews each closed half of it" shape.
