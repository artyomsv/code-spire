# A run unit has no network, so it is isolated from nothing and can reach nothing

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Medium |
| Location | `spire-runtime-docker/.../DockerRunRuntime.java` (`createContainer` / `hostConfigFor`: no network is specified, so every container of a run unit lands on the daemon's default bridge); `spire-runtime/.../RunUnitSpec.java` (no network field to specify one with) |
| Found during | PR #95 four-lens review, round 1 (security L-2), carried to round 2. **Raised to High and merged with the M2 finding on 2026-09-04** — see the update below |
| Date | 2026-09-02 (updated 2026-09-04) |

## Issue

`RunUnitSpec` has no network field and `DockerRunRuntime` never sets one, so every container of a
run unit lands on the daemon's **default bridge**. One missing field, two symptoms that were filed
separately and are the same debt.

**Symptom one — the unit is not isolated (M0, security).** The unit's three containers join the
default bridge alongside every other container on the daemon, with a route to the host. In a
developer's stack that includes Postgres (34432), Redpanda (34092), the orchestrator and the
dashboard, all published on the host — reachable from the agent container, which runs an untrusted
model on an untrusted work item at `danger-full-access`. The publisher and the init container need
egress (the forge); the agent needs egress (the model API); none of them needs the host or its
neighbours. `M0WalkingSkeletonTest` relies on this today: its origin container is reached by bridge
address.

**Symptom two — the unit cannot reach the stack (M2, testability).** The runtime's own comment
already names the network gap in the context of *egress*:

> network policy either — egress restriction needs a Kubernetes NetworkPolicy or an explicit
> user-defined network, so the model-provider allowlist is advisory on this arm.

What M2 discovered is that the same gap blocks *ingress to the stack's own services*, and therefore
blocks the exit criterion from being automated. `spire-e2e` runs a real GitLab as a compose service
named `gitlab`, on the `spire-e2e` network. Automating M2's exit criterion — a finding is dispatched
as a fix run, the run pushes to the pull request's source branch, and the next review round
reconciles the finding — requires a run unit to clone from that GitLab. The unit's init container
resolves a clone URL derived from the FACTORY provider's `baseUrl`, which is `http://gitlab/api/v4`.
On the default bridge, `gitlab` resolves to nothing.

## Risks

**Raised to High on 2026-09-04**, on the second symptom rather than the first. The security risk is
unchanged and remains Low in production — the worker is the only thing on its daemon there, and the
services sit behind their own network — while being real in dev, where a prompt-injected agent could
reach the orchestrator's REST surface or the database port with whatever credentials it can guess.

What raises it is *what cannot be known*. The loop M2 exists to close — finding → fix run → push →
reconciliation — is covered in three separate places and joined nowhere: `FixRunDispatcherTest` (the
dispatch, unit), `Adr040ExistingBranchTest` (the push, real containers, real remote), and
`ReviewChainTest` (the review and reconciliation, real GitLab). Each is honest about its own half.
Nothing proves the halves meet, and `docs/UNVERIFIED.md` §B already records that the factory's live
behaviour "has only ever met a WireMock LLM and a local origin".

## Suggested Solutions

1. **A network on `RunUnitSpec`**, set by `RunUnitBuilder` from configuration and applied by
   `DockerRunRuntime` via `HostConfig.withNetworkMode(...)`. Small in code, and it is the one field
   both symptoms need: a per-run user-defined network isolates the unit from other containers while
   still routing egress, and a named stack network is what lets an e2e run unit resolve `gitlab`.
   The test's origin container then joins the run's network explicitly rather than being found on
   the bridge.
2. **The egress policy on top of it**, which M1 owes under FR-F12: an allowlist of model and forge
   hosts enforced *outside* the container — the container is the boundary, and the agent runs
   unconfined inside it. An allowlist is only enforceable once the unit is on a network the
   deployment controls, which is why this waits on (1) rather than being independent of it.
3. **Publish GitLab off loopback in the e2e overlay** so the unit reaches it via
   `host.docker.internal`. **Rejected.** `compose.e2e.yml` binds it to `127.0.0.1` for a stated
   reason: that GitLab holds an admin token whose value is a public constant in this repository and
   has private-network webhook targets enabled, so a wider bind lets anyone reaching the port
   authenticate as admin and pivot into the stack. Undoing a deliberate security control to make a
   test pass is the wrong trade in both directions — it weakens the stack AND the test would then be
   exercising a topology no deployment uses.
4. **Leave the loop unjoined and say so**, which is the state today. Defensible only while
   `UNVERIFIED.md` carries it and nobody claims the exit criterion is automated. It stops being
   defensible when M2 is described as delivered without the qualifier.
