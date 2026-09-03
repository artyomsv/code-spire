# A run unit has no disk bound, so one run can take the host

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Small |
| Location | `spire-runtime-docker/src/main/java/dev/codespire/runtime/docker/DockerRunRuntime.java` (`hostConfigFor`); `docs/factory/RUN-TOPOLOGY.md` §9 |
| Found during | PR #96 whole-PR review (security M2) |
| Date | 2026-09-03 |

## Issue

Memory (4 GiB), CPU (2) and pids (512) are bounded on every container of a run unit. **Disk is not**:

- `/workspace` and `/handoff` are local-driver named volumes with no quota;
- the agent's root filesystem is an unbounded overlay2 writable layer (`--storage-opt size` is unset,
  and only works on xfs with pquota anyway);
- `/tmp` is not a size-bounded tmpfs.

So `fallocate -l 500G /workspace/x` from an agent at full shell access fills the daemon's disk. Every
concurrent and subsequent run fails, and in the dev topology so do Postgres and Redpanda, which share
the host.

`RUN-TOPOLOGY.md` §9 lists six "hard requirements this creates" and none is a disk bound.
`techdebt/spire-publisher/3-2-cumulative-bundle-bytes-and-object-growth-are-unbounded.md` covers only
the publisher's object store, which is a different surface.

## Risks

A single prompt-injected run denies service to the whole fleet, and on a developer machine to the
databases beside it. The trigger is one command; the blast radius is the host.

## Suggested Solutions

1. `.withTmpFs(Map.of("/tmp", "rw,size=1g"))` — cheap and immediate. tmpfs pages count toward the
   memory cgroup, so this half is already bounded by the existing memory limit.
2. A `size=` option on the two named volumes where the driver supports it.
3. A `RUN-TOPOLOGY.md` §9 requirement that the worker's daemon runs on a dedicated disk, or on an xfs
   pquota root so `--storage-opt size=` can be set at all. That is a deployment property this project
   cannot enforce in code, which is exactly what §9 is for.
