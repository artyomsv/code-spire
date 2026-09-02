# The M0 test's origin container runs as root on the shared bridge, and its images are pinned by tag

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-run-worker/src/test/docker/origin/` (nginx + fcgiwrap + git-http-backend as root), `spire-run-worker/src/test/docker/agent/Dockerfile`, `spire-run-worker/src/test/java/dev/codespire/runworker/TestImages.java` (`alpine:3.20` by tag, `busybox:1.37.0` in the runtime IT) |
| Found during | PR #95 four-lens review, round 2 (security-officer, finding 12) |
| Date | 2026-09-02 |

## Issue

Test infrastructure, deliberately minimal: the origin is a root-run nginx serving plaintext git
over HTTP on the daemon's default bridge, seeded with a test-only secret, and the bases are pulled
by tag rather than by digest. It is reachable by anything on that bridge for the seconds it lives,
and a tag can be repointed upstream.

## Risks

- Only in the test environment; nothing here ships. A repointed base tag would change the test
  environment silently, which is the same reason the CI actions are pinned by SHA.

## Suggested Solutions

- Pin the two bases by digest; run nginx as a non-root user with the socket under `/run` owned by it;
  put the origin on the run's own network once the runtime creates one (the default-bridge entry).
