# `copyOutOfReach` checks and then opens, on a path the agent can write

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Trivial |
| Location | `spire-workspace/src/main/java/dev/codespire/workspace/PublishRepo.java` (`copyOutOfReach`) |
| Found during | PR #96 whole-PR review (security L2) |
| Date | 2026-09-03 |

## Issue

`Files.readAttributes(bundle, …, NOFOLLOW_LINKS)` and `Files.newInputStream(bundle)` are two
syscalls, and `newInputStream` FOLLOWS symlinks. A rename-over between them makes the publisher copy
an arbitrary file it can read — `/proc/self/environ` holds `SPIRE_GIT_SECRET`.

CWE-367. The window is microseconds and the copy never leaves the publisher, which is why this is
Low. It is filed because the method's own javadoc is about closing exactly this class of hole.

## Risks

An agent that wins a microsecond race reads the publisher's own environment into a file the publisher
then treats as a bundle. It does not leave the container, so the practical exposure is small.

## Suggested Solutions

Open with `O_NOFOLLOW` so the check and the open are one operation:

```java
Files.newByteChannel(bundle, EnumSet.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
```
