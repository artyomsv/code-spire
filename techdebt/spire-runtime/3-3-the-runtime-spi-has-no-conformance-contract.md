# The runtime SPI has no conformance contract, and three ad-hoc fakes stand in for one

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-runtime/src/main/java/dev/codespire/runtime/RunRuntime.java`; fakes at `OrphanWatchdogTest:46`, `RunControlListenerTest:48`, `RunLauncherTest:125` |
| Found during | PR #96 whole-PR review (code-quality I6) |
| Date | 2026-09-03 |

## Issue

`spire-harness` ships `src/testFixtures/.../HarnessAdapterContract.java` — an abstract test class a
second arm extends, asserting the properties a fake would otherwise paper over: a hyphen-leading
prompt is never read as a flag, an `ARGUMENT` arm marks end-of-options, a `STDIN` arm keeps the
prompt out of argv, a credential never reaches argv, an unparseable line is skipped rather than
fatal, a silent harness reports `unknown()` and never zero.

`spire-runtime` has no `testFixtures` directory and no contract. What exists instead is **three
independent `FakeRuntime` classes**, each satisfying only what its own test needs. A Kubernetes arm
has nothing to run against, and every invariant the callers rely on is enforced only by
`DockerRunRuntimeIT` against the one arm that exists.

Every invariant such a contract would assert has already been paid for once on this branch:

- `destroy` leaves nothing behind (the defect this PR found: it swallowed every removal error);
- `salvage` never destroys, and a destroyed unit stays discoverable until `destroy`;
- all three containers receive `RunUnitSpec.environmentFor` (the CA-bundle defect);
- a `Mount` declared read-only is read-only (the `AccessMode`-vs-`noCopy` defect);
- `attach` delivers whole lines irrespective of frame boundaries (the carry defect);
- `drainWindow()` elapses before the publisher is stopped (the exit-143 defect);
- `cancel` is idempotent and does not destroy;
- `steer` throws exactly when `capabilities().steering()` is false.

## Risks

The second arm is M5's work, and it will be written against three fakes that disagree with each
other and with the one real implementation. Every defect in the list above would be free to recur in
it, because nothing states the property outside the Docker arm's own integration test.

## Suggested Solutions

Put a `RunRuntimeContract` in `spire-runtime/src/testFixtures`, extend it from `DockerRunRuntimeIT`,
and let the three ad-hoc fakes become what they should be — narrow doubles for their callers' tests,
sitting under a contract that says what production requires. The harness module is the worked
example; this is copying it.
