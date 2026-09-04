---
name: docker-java-wait-semantics
description: docker-java 3.5.1 timed awaitStatusCode throws one exception type for timeout, interrupt and daemon error; awaitCompletion(timeout) is the boolean that splits them. Verified by javap, no sources jar in the Gradle cache.
metadata:
  type: reference
---

`WaitContainerResultCallback.awaitStatusCode(long, TimeUnit)` in docker-java 3.5.1 throws
`DockerClientException` for THREE different things: the timeout ("Awaiting status code timeout."),
an `InterruptedException` ("Awaiting status code interrupted:"), and any error the daemon reported
on the stream (rethrown by `throwFirstError()` inside `awaitCompletion`). A catch on
`RuntimeException` around it cannot tell an agent overrun from a daemon fault.

`ResultCallbackTemplate.awaitCompletion(long, TimeUnit)` (docker-java-api) returns `false` on
timeout, calls `throwFirstError()` before returning, and closes the callback in a `finally`.
That boolean is the clean split. `getStatusCode()` throws when no `WaitResponse` arrived, so a
`null` status code means the daemon answered with a response lacking `StatusCode`, not a timeout.

**How to apply:** when reviewing `DockerRunRuntime.salvage` or any wait-with-timeout on
docker-java. No sources jar is in `~/.gradle/caches`; verify with
`javap -c -p` on the class extracted from `docker-java-core-<ver>.jar` (callback) and
`docker-java-api-<ver>.jar` (template) — `javap` is at `/c/Program Files (x86)/jdk/bin/javap`.

Related: [[semgrep-on-windows-git-bash]]
