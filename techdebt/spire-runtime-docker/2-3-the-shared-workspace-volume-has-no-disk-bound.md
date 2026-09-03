# The shared workspace volume has no disk bound on the Docker arm

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Medium |
| Location | `spire-runtime-docker/src/main/java/dev/codespire/runtime/docker/DockerRunRuntime.java` (`create`, volume creation); `docs/factory/RUN-TOPOLOGY.md` §9 |
| Found during | PR #106, closing `2-2-a-run-unit-has-no-disk-bound.md` |
| Date | 2026-09-03 |

## Issue

`RunUnitSpec.diskBytes` now exists and is enforced, but this arm can only spend it on `/tmp`.
`/workspace` and `/handoff` are local-driver named volumes with no quota, so
`fallocate -l 500G /workspace/x` from an agent at full shell access still fills the daemon's disk.

**Why the obvious fixes do not work, both measured rather than reasoned about:**

1. **`--storage-opt size=`** needs xfs with `pquota` (or btrfs/zfs). Docker Desktop runs overlay2 on
   ext4 inside its VM, so it fails at *container creation* on the machines most developers use.
2. **A tmpfs-backed local volume** (`--opt type=tmpfs --opt o=size=…`) does bound correctly — a 32 MB
   write into a 16 MB volume stops at 16 MB — **but it is dropped when the last container using it
   stops.** Two containers that overlap share it; two that do not lose it. Measured: `docker run A`
   writing a file then `docker run B` reading it sees an empty directory, while a container started
   *while* A is alive sees the file.

   This unit runs `init` **to completion** and only then starts the agent (ADR-039, RUN-TOPOLOGY §3),
   so a tmpfs `/workspace` wipes the clone between the two. That is a broken run in place of an
   unbounded one.

**Kubernetes has no such gap**, which is why `diskBytes` belongs on the spec even though this arm
spends only part of it: `emptyDir` is a *pod* volume, so it survives an init container exiting, and
`medium: Memory` with `sizeLimit` bounds the whole unit.

## Risks

A single prompt-injected run denies service to the whole fleet, and on a developer machine to the
Postgres and Redpanda beside it. The trigger is one command; the blast radius is the host.

## Suggested Solutions

Two candidate designs, both real changes rather than a setting:

1. **A keeper container.** Hold the unit's volumes mounted for the unit's whole lifetime with a
   minimal always-running container, which is what Kubernetes' pause container does. tmpfs volumes
   then survive the init→agent handover and the bound applies to the whole unit. Costs one extra
   container per run and touches the unit topology, so it wants an ADR amendment rather than a patch.
2. **Overlap init with the agent**, with the agent waiting on a sentinel the clone writes. Cheaper in
   containers, but it inverts the ordering the design deliberately chose and makes a clone failure a
   race rather than a refusal.

Until one lands, RUN-TOPOLOGY §9 carries it as a deployment requirement: run the worker's daemon on
a dedicated disk, or on an xfs `pquota` root so `--storage-opt size=` can be set at all. That is a
property this project cannot enforce in code, which is exactly what §9 is for.
