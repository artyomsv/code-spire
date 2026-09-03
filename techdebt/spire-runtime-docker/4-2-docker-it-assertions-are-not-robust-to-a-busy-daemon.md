# `DockerRunRuntimeIT`'s assertions are not robust to a busy daemon

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-runtime-docker/src/test/java/dev/codespire/runtime/docker/DockerRunRuntimeIT.java:276` (inspect after stop), `:299` (destroy must remove) |
| Found during | M1 Task 0 review — the half of `techdebt/global/3-2-testservices-races-two-docker-driving-modules.md` that the Gradle daemon lock did not close |
| Date | 2026-09-02 |

## Issue

The entry this replaces proposed two fixes and closed by asking for both: *"Prefer 1, with 2 as
defence in depth. Both are wanted: 1 removes the race, 2 stops the next one from reading as a
lifecycle bug."* Only the first shipped — a Gradle shared build service that lets one test task
hold the daemon at a time. This is the second, re-filed rather than deleted with the entry.

Two assertions read the daemon's state on the first try and take the answer as final:

```
anAgentThatOutlivesItsWallClockIsActuallyStopped
  NotFoundException: Status 404: No such container: 8d1af344…   (inspect, IT:276)
everyPartOfAUnitIsDestroyedByLabelWithNoMemoryOfIt
  expected: <true> but was: <false>                              (IT:299)
```

**The Gradle lock does not subsume this, and that is the whole reason to keep the entry.** The lock
covers one Gradle invocation. A second `./gradlew` in another terminal, a `quarkusDev` run worker, a
running packaged or end-to-end stack, or a shared CI runner all put load on the same daemon while the
suite runs, and none of them is a task this build schedules.

The value asked for was diagnosability, not stability. Both failures name a condition the runtime is
written to prevent — a container that vanished, and a destroy that removed nothing — so the natural
reading is that container lifecycle broke. The lock removes one cause of that reading; it does
nothing about the reading itself.

Worth recording alongside: the root cause of the original incident is still an inference. It rests on
"fails under `testServices`, passes 15 of 15 alone, twice", which is strong evidence of interference
but does not distinguish two tasks colliding from ordinary daemon pressure. The fix below is correct
under either.

## Risks

- A busy daemon reports as a container-lifecycle regression, and the next person spends the
  diagnosis time this entry exists to save.
- The failure arrives on someone else's change, so the cost lands on whoever is least equipped to
  recognise it.

## Suggested Solutions

1. Where a test only wants **absence**, treat `NotFoundException` on inspect as the absence it is
   asserting, rather than letting it propagate as an error.
2. Where a test wants **removal**, poll to a short deadline instead of reading once. Removal is
   asynchronous in the daemon, and the first read is not the answer.
3. Keep the assertion messages naming which of the two conditions failed, so a genuine lifecycle
   regression still reads as one.
