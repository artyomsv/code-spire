# M4 verify grew methods and classes past the size rules

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-publisher/.../CloneMain.java` (`run` 36 lines), `spire-run-worker/.../RunUnitBuilder.java` (`build` 65 lines; class 323 lines), `spire-orchestrator/.../WorkRunAssembly.java` (`assemble` 53 lines), `spire-orchestrator/.../BuildDefaults.java` (`save` 70 lines), and three classes already over 300 lines that grew: `HarnessCredentialPool` (796), `DockerRunRuntime` (1162), `WorkItemTransitions` (447) |
| Found during | PR #184 branch review (rules-compliance M-10), 2026-10-09 |
| Date | 2026-10-09 |

## Issue

`clean-code-java.md` caps a method at 30 lines and a class at 300. M4 verify added a checkpoint start to
the clone, a verify branch and a checkpoint mount to the unit builder, a retry section and a checkpoint
start to dispatch, check commands and a time limit to the build setup, and seat renewal to the credential
pool. Each addition was small and sat where its neighbours already were, so the methods grew instead of
splitting. The same review fixed the cases where the growth hid a defect (`DockerVerifyUnits`' flag
parameter, `WorkVerifyDispatcher.claim`); these are the ones where it did not.

## Risk

The next guard added to `RunUnitBuilder.build` or `WorkRunAssembly.assemble` lands in a method nobody can
read top to bottom, and a reviewer reads the diff instead of the method. `WorkItemTransitions` decides every
phase change; at 447 lines a wrong branch order is hard to see.

## Fix

- `CloneMain.run`: move the environment parsing into a `CloneEnv.from(env)` record.
- `RunUnitBuilder`: move the checkpoint mount and the verify unit into a `VerifyUnits` collaborator.
- `WorkRunAssembly.assemble` and `BuildDefaults.save`: one small type per gate, as `SpendGate` already is
  (the shape `4-2-the-factory-dispatch-path-exceeds-the-method-size-and-parameter-rules.md` proposes).
- `HarnessCredentialPool`: move seat renewal storage (`seatsToRenew`, `storeRenewal`) and seat identity
  (`identifySeats`, `seatFor`, `replaceSubscription`) into a `SubscriptionSeats` class.
- `WorkItemTransitions`: split the verify-result path (`verified`, the result gate, retry and stop in
  `answer`) into a `WorkVerifyTransitions` class that shares the lock and store.
