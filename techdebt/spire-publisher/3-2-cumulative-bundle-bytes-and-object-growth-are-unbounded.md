# The publisher bounds one bundle's size, not the run's cumulative bytes or object growth

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-publisher/src/main/java/dev/codespire/publisher/PublishCycle.java` (each valid bundle is fetched into the bare clone and pushed; nothing prunes), `spire-run-worker/.../RunUnitBuilder.java` (`BUNDLE_MAX_BYTES`, 256 MiB per bundle) |
| Found during | PR #95 four-lens review, round 1 (security-officer, worker side) |
| Date | 2026-09-02 |

## Issue

`SPIRE_BUNDLE_MAX_BYTES` caps one bundle. `HandoffWatcher` now caps the number of bundles per run
(500). Nothing caps the cumulative bytes fetched, the growth of the publisher's object database, or
the number of pushes to the forge — an agent can write 500 bundles just under the cap and have every
one fetched and pushed.

## Risks

- Disk and forge-side rate limits are the bound, and both fail late and loudly rather than early and
  cheaply; the run's containers hold the disk until destroyed.

## Suggested Solutions

- A cumulative-bytes cap per run in `PublishCycle` alongside the bundle count, reported as a
  distinct failure cause; `BUNDLE_UNREADABLE` from an oversized bundle counting against it too.
