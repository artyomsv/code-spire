# The service-tier test job takes 40+ minutes on a CI runner

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `.github/workflows/ci.yml`, job `service tests + packaging`; root `build.gradle.kts` task `testServices` |
| Found during | The repository's first CI run, 2026-08-05 |
| Date | 2026-08-05 |

## Issue

`./gradlew testServices` runs in about **100 seconds locally** and took **over 40 minutes** on a
GitHub-hosted runner, where it was still going when observation stopped. It sits on the pull-request
path, so as it stands every PR waits on it.

The cause is Quarkus Dev Services rather than the tests themselves. The three deployables hold 73 test
sources, and each `@QuarkusTest` with a distinct configuration profile restarts the application and can
provision its own Postgres and Kafka containers. Locally those images are already in the daemon's cache
and several runs share warm containers; on a cold runner every pull is fresh and every boot is paid in
full. The root `build.gradle.kts` reaper exists precisely because these containers are numerous enough
to accumulate.

This is the tradeoff `CICD-AND-PACKAGING.md` §5.5 flagged — "this is the slow part of the suite and
argues for a fast/slow job split" — and the split was built, but the assumption that the slow half
would still be tolerable on the PR path was never measured. It is not.

## Risks

- **A 40-minute PR gate is a gate people route around.** The failure mode is social: merge-without-
  waiting becomes normal, and the check stops being one.
- It is not currently *incorrect* — `fast tests` and `dashboard` both complete in a few minutes and
  cover 59 test sources plus the whole dashboard, so a broken change is usually caught quickly.
- Bounded, at least: the job now carries `timeout-minutes: 60`, so a genuinely hung run fails in an hour
  rather than consuming the six-hour default.

## Suggested Solutions

In rough order of cost:

1. **Pre-pull the Dev Services images** in the job before Gradle runs, so every container start finds a
   local image. Cheapest thing to try, and it should be measured before anything more invasive —
   determine the exact images first (`quarkus.datasource.devservices.image-name` and the Kafka
   equivalent) rather than guessing tags.
2. **Share containers across test JVMs** with `quarkus.devservices.reuse` / a Testcontainers
   `~/.testcontainers.properties` reuse flag, so the same Postgres serves many test classes. Needs care:
   reuse across tests that assume a clean schema is how a suite starts passing for the wrong reason.
3. **Reduce distinct Quarkus configurations.** Each unique test profile is a fresh application boot;
   consolidating profiles is the largest real win and the most work.
4. **Move `testServices` off the PR path** to a merge-only or nightly workflow, keeping `fast` and `ui`
   as the PR gate. This is the honest fallback if 1–3 do not bring it under roughly ten minutes, and it
   is what §5.5 was hedging about. It weakens the gate, so prefer it last.

Whatever is chosen, record the measured before-and-after here — the mistake this entry documents is
having assumed a duration instead of measuring one.
