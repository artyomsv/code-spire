# Dockerfile changes are unverified by any pull-request check

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `.github/workflows/docker.yml:12-15` (trigger block) |
| Found during | Reviewing the dependabot backlog, 2026-08-23 |
| Date | 2026-08-23 |

## Issue

`docker.yml` is the only workflow that builds the four `Dockerfile`s, and it is triggered
exclusively on `push: branches: [master]` plus `workflow_dispatch`. No `pull_request` trigger
exists.

So a pull request that changes nothing but a `Dockerfile` gets a full green check run in which
**not one job reads the file it changed**. The image is first built after the change is already
on `master`, in the workflow that also publishes `:edge` to GHCR.

Dependabot PR #50 (`node:22-alpine` to `node:26-alpine`, touching only `spire-ui/Dockerfile` and
`spire-ui/Dockerfile.dev`) demonstrated it: fourteen checks, all green or skipping, none of which
built either file. The change was declined for unrelated reasons — Node 26 is not an LTS line —
but had it been a genuinely broken base image, the checks would have looked identical.

The same shape hides a second gap. Two of the repository's four `codeql-action` references live
in `docker.yml` and `semgrep.yml`, which are likewise master-only. When #56 moved all four to v4,
the two in `codeql.yml` were proven by that PR's own run and the other two could only be proven
after merge.

## Risks

- A broken base image, a removed apt/apk package, or a bad multi-stage `COPY` reaches `master`
  before anything builds it, and the first failure is in the job that publishes `:edge`.
- Worse than a red build: an image that builds but is subtly wrong — a runtime that no longer
  matches CI, a missing file that only the healthcheck would catch — ships as `:edge` with a
  green PR behind it.
- `spire-ui/Dockerfile` carries the nginx reverse-proxy config that makes ADR-022's cookie-path
  scoping real. A change there is a security change, and it currently merges on evidence from
  workflows that never open the file.

## Suggested Solutions

1. **Add a build-only `pull_request` trigger with a path filter** (preferred). Same matrix, with
   `push: false` on `docker/build-push-action` and the scan and SARIF-upload steps skipped, gated
   on `paths: ['**/Dockerfile*', 'spire-ui/nginx/**']`. Costs a build only on PRs that touch these
   files, and needs no registry credentials.
2. **Extend `deploy/e2e.sh` coverage to PRs touching Dockerfiles.** Stronger, since it exercises
   the proxy rules rather than just the build, but far more expensive and it needs images to exist
   first — which is the problem being solved.
3. **Accept it and document it.** Defensible only if Dockerfile changes stay rare; they are not
   rare, because dependabot proposes base-image bumps on its own schedule.
