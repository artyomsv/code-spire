# The worker's own failure details carry no credential scrub

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-run-worker/src/main/java/dev/codespire/runworker/RunLauncher.java` (`RunFailed` details built from `e.getMessage()`), `spire-run-worker/.../RunDispatcher.java` (`WORKER_FAILED`) |
| Found during | PR #95 four-lens review, round 1 (security-officer, worker side) |
| Date | 2026-09-02 |

## Issue

The publisher scrubs the git secret from every failure line it writes (`OutcomeWriter`), because a
transport exception quotes the URL it tried. The worker's own `RunFailed` details have no such
scrub: they carry exception text from the runtime and the launcher. Today that text is docker-java
and JGit messages, and the plaintext credentials exist only inside `Credentials` and
`RunUnitBuilder` for the length of a build, so no path currently reaches a detail line — hence Low.

## Risks

- A future exception that quotes a container's environment (docker-java includes the create request
  in some errors) would put the model key into `factory_run.failure_detail`, which a viewer reads.

## Suggested Solutions

- Route every `RunFailed` detail through one scrub keyed on the decrypted credentials of the run
  being reported, the way `OutcomeWriter` does with the publisher's secret.
