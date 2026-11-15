# Release the factory images on a version tag

Follow-up to [the GHCR factory images design](2026-11-14-ghcr-factory-images-design.md), which put the run worker
and the five run-unit images on GHCR as `:edge` and `:sha-<short>` from `docker.yml` and left `release.yml` out of
scope. Goal: a `v*` tag publishes them the way it publishes the four services, so an operator can pin
`SPIRE_VERSION=<version>` for every image `compose.ghcr.yml` names.

## Decisions

1. **One `publish` matrix, ten images.** `release.yml`'s matrix gains `spire-run-worker`, `spire-publisher`,
   `spire-model-proxy`, `spire-egress-proxy`, `spire-agent-codex` and `spire-agent-claude-code`. Each is built by
   `build-push-action` for `linux/amd64,linux/arm64`, pushed as `:<version>` and `:latest`, with provenance and an
   SBOM, and signed keyless by digest, exactly as the four services are. The same `gate` job holds all of them.
2. **The matrix mirrors `docker.yml`'s fields** (`apkUpgrade`, `gradleDist`), so `APK_UPGRADE_BUST=github.run_id`
   reaches every apk-based image and the publisher and model proxy run `installDist` first.
3. **The agent images get their model catalogue from their own build scripts.** A multi-arch push cannot run the
   image it builds, so the job first runs `deploy/agent/build-codex.sh` (or `build-claude-code.sh`) on the runner's
   default builder, amd64, reads `dev.codespire.agent.models` back from that image, and passes it to the multi-arch
   build as `AGENT_MODELS`. One recipe again: the trim logic stays in the script. The step runs before
   `setup-buildx`, because a docker-container builder would not load the image the script then reads.
   The catalogue comes from the amd64 binary; the arm64 binary is the same CLI version from the same npm release.
4. **Every image is multi-arch.** Checked statically: the JVM images use `eclipse-temurin:25-jre-alpine`, which the
   four services already publish for arm64; the egress proxy uses `alpine:3.20`; the agents use
   `node:22-bookworm-slim` (amd64 and arm64), and both CLIs ship a `linux-arm64` optional dependency on npm
   (`@openai/codex@0.156.1`: `@openai/codex-linux-arm64`; `@anthropic-ai/claude-code@2.1.319`:
   `@anthropic-ai/claude-code-linux-arm64`).
5. **The spire-arch checks read both workflows.** `ApkUpgradeIsNotCachedTest` holds `release.yml`'s matrix to the
   same two directions as `docker.yml`'s, and a new check fails when the two workflows publish different image sets.

## Gate fixes

- The gate's `.env` lacked `SPIRE_OIDC_RUN_WORKER_SECRET`, which `compose.ghcr.yml` requires with `:?` on the
  profiled run worker; compose interpolates every service before it filters by profile, so `up` refused to start
  (`e2e.yml` already carries the value for this reason). The last dry run (2026-08-05) predates the requirement.
- The gate still builds and starts only the reviewer stack at `:edge`. It does not start the factory profile; it
  now also renders `--profile factory` config, so a variable the run worker requires fails the gate.

## Out of scope

The Kubernetes deployment; live proof of the run worker image and the Claude Code image (docs/UNVERIFIED.md).
