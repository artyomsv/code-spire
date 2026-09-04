---
name: run-failure-cause-has-no-credential-producer
description: Nothing in the factory run pipeline can emit CREDENTIAL_REJECTED, so RunCredentialFeedback and markRejected have no automatic producer — the pool's whole self-healing half is inert
metadata:
  type: project
---

The factory's producible failure-cause vocabulary is fixed by three places, and **none of them can
produce `CREDENTIAL_REJECTED`**:

- `spire-harness/.../FailureCause` — `PROVIDER_ERROR, NO_MODEL_RESPONSE, TIMED_OUT, OUT_OF_MEMORY,
  SANDBOX_LOST, SANDBOX_UNREACHABLE, EVICTED, DROPPED_COMMIT, SALVAGE_FAILED, PUSH_GATE_REFUSED,
  BLOCKED_EGRESS, HARNESS_EXIT_NONZERO`. No credential value at all.
- `spire-publisher` outcome JSON — `CLONE_FAILED, BUNDLE_UNREADABLE, NON_FAST_FORWARD,
  PUSH_REJECTED, PUSH_TRANSPORT_FAILED, PUBLISHER_FAILED, PUBLISHER_MISCONFIGURED`.
- `spire-run-worker` — `SANDBOX_LOST, WORKER_FAILED, SALVAGE_FAILED, RESULT_UNPUBLISHABLE,
  CANCELLED, AGENT_TIMEOUT, DISPATCH_FAILED, DISPATCH_UNCERTAIN, RUNTIME_UNAVAILABLE`.

`RunFailureCause.ALIASES` maps none of those onto `CREDENTIAL_REJECTED` either.

**Why: ** FR-F12's `RunCredentialFeedback` gates on exactly that one cause, so the automatic path
that takes a dead harness key out of rotation can never fire. Its tests construct the string by
hand (`new RunResult.RunFailed(runId, "CREDENTIAL_REJECTED", …)`), which is why a green suite
proves nothing about reachability. The filed debt entry
(`techdebt/spire-orchestrator/4-2-no-harness-reports-a-rate-limit-…`) says only the *rate-limit*
half is manual; both halves are.

**How to apply: ** whenever a class keys on one member of a closed wire vocabulary, grep for a
producer of that literal before accepting the tests as evidence. Related: [[code-spire-test-gap-pattern]].
