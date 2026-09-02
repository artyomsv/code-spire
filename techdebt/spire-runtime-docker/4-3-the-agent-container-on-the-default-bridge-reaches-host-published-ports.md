# The agent container sits on the default bridge and can reach host-published ports

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-runtime-docker/src/main/java/dev/codespire/runtime/docker/DockerRunRuntime.java` (`createContainer`: no network is specified, so every container of a run unit lands on the daemon's default bridge) |
| Found during | PR #95 four-lens review, round 1 (security L-2), carried to round 2 |
| Date | 2026-09-02 |

## Issue

The run unit's three containers are created without a network and so join the default bridge,
alongside every other container on the daemon and with a route to the host. In a developer's
stack that includes Postgres (34432), Redpanda (34092), the orchestrator and the dashboard, all
published on the host — reachable from the agent container, which runs an untrusted model on an
untrusted work item at `danger-full-access`. The publisher and the init container need egress (the
forge); the agent needs egress (the model API); none of them needs the host or its neighbours.
`M0WalkingSkeletonTest` relies on this today: its origin container is reached by bridge address.

## Risks

- Low in production, where the worker is the only thing on its daemon and the services sit
  behind their own network; real in dev, where a prompt-injected agent could reach the
  orchestrator's REST surface or the database port with whatever credentials it can guess.

## Suggested Solutions

- A per-run user-defined network (isolates the unit from other containers; still routes egress),
  plus the egress policy M1 owes under FR-F12 (an allowlist of model and forge hosts enforced
  outside the container — the container is the boundary, and the agent runs unconfined inside it).
- The test's origin then joins the run's network explicitly rather than being found on the bridge.
