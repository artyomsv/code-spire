# `DockerRunRuntime` is past the class-size guideline

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-runtime-docker/src/main/java/dev/codespire/runtime/docker/DockerRunRuntime.java` (about 560 lines, about 360 without comments, against the 300-line cap in `clean-code-java.md`) |
| Found during | PR #95 four-lens review, round 3 (rules-compliance, below-bar observation) |
| Date | 2026-09-02 |

## Issue

The Docker arm grew a method at a time through three review rounds — image pull, init timeout,
the drain window, the carry cap, orphan discovery — each right on its own and none split out. It
is the only new class on the branch over the cap once comments are excluded; the precedent for
tracking rather than refactoring under review pressure is
`techdebt/spire-orchestrator/3-4-three-orchestrator-classes-past-the-size-guideline.md`.

## Risks

- Readability only. The class is exercised by a real daemon in `DockerRunRuntimeIT`, so a split
  has a net to land in.

## Suggested Solutions

- Extract the log-stream reader (the frame callback with its carry and clipping state) into its
  own class, and the image and container lifecycle helpers (`ensureImage`, `createContainer`,
  `killQuietly`, the label lookups) into a second; `salvage`/`drainPublisher` stay.
