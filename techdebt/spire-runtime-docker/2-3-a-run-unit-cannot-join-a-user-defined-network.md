# A run unit cannot join a user-defined network, so the loop cannot be tested end to end

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Medium |
| Location | `spire-runtime-docker/.../DockerRunRuntime.java` (`hostConfigFor`); `spire-runtime/.../RunUnitSpec.java` |
| Found during | M2 task 11 — automating the exit criterion |
| Date | 2026-09-04 |

## Issue

`RunUnitSpec` has no network field and `DockerRunRuntime` never sets one, so every run unit lands on
Docker's **default bridge**. The runtime's own comment already says this, in the context of egress:

> network policy either — egress restriction needs a Kubernetes NetworkPolicy or an explicit
> user-defined network, so the model-provider allowlist is advisory on this arm.

That is a known limitation for *egress*. What M2 discovered is that it also blocks *ingress to the
stack's own services*, and therefore blocks the exit criterion from being automated.

**The concrete failure.** `spire-e2e` runs a real GitLab as a compose service named `gitlab`, on the
`spire-e2e` network. Automating M2's exit criterion — a finding is dispatched as a fix run, the run
pushes to the pull request's source branch, and the next review round reconciles the finding —
requires a run unit to clone from that GitLab. The unit's init container resolves the clone URL
derived from the FACTORY provider's `baseUrl`, which is `http://gitlab/api/v4`. On the default
bridge, `gitlab` resolves to nothing.

## Risks

High, and the risk is *what cannot be known* rather than a defect in shipped behaviour.

The loop M2 exists to close — finding → fix run → push → reconciliation — is covered in three
separate places and joined nowhere: `FixRunDispatcherTest` (the dispatch, unit),
`Adr040ExistingBranchTest` (the push, real containers, real remote), and `ReviewChainTest` (the
review and reconciliation, real GitLab). Each is honest about its own half. Nothing proves the halves
meet, and `docs/UNVERIFIED.md` §B already records that the factory's live behaviour "has only ever
met a WireMock LLM and a local origin".

## Suggested Solutions

1. **A network on `RunUnitSpec`**, set by `RunUnitBuilder` from configuration and applied by
   `DockerRunRuntime` via `HostConfig.withNetworkMode(...)`. Small in code and it is the same field
   the egress work will need — an allowlist is only enforceable once the unit is on a network the
   deployment controls. This is the option that also moves the *other* recorded gap
   (`4-3-the-agent-container-on-the-default-bridge-reaches-host-published-ports.md`), so the two
   should be designed together rather than one at a time.
2. **Publish GitLab off loopback in the e2e overlay** so the unit reaches it via
   `host.docker.internal`. **Rejected.** `compose.e2e.yml` binds it to `127.0.0.1` for a stated
   reason: that GitLab holds an admin token whose value is a public constant in this repository and
   has private-network webhook targets enabled, so a wider bind lets anyone reaching the port
   authenticate as admin and pivot into the stack. Undoing a deliberate security control to make a
   test pass is the wrong trade in both directions — it weakens the stack AND the test would then be
   exercising a topology no deployment uses.
3. **Leave the loop unjoined and say so**, which is the state today. Defensible only while
   `UNVERIFIED.md` carries it and nobody claims the exit criterion is automated. It stops being
   defensible when M2 is described as delivered without the qualifier.
