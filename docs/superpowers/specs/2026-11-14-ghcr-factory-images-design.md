# Publish the factory images to GHCR

Operator goal, 2026-11-14: host Code Spire on a home Kubernetes cluster. The services run in the cluster; the run
worker runs on a Docker host next to it (Kubernetes has no run worker until M5, `deploy/README.md`). Every image must
come from a registry. Next PR after this one: the Kubernetes deployment.

## Decided with the operator

- Publish to `ghcr.io/artyomsv/`: `spire-run-worker`, `spire-publisher`, `spire-model-proxy`, `spire-egress-proxy`,
  `spire-agent-codex` (public), and `spire-agent-claude-code` (**private**).
- The Claude Code image stays private: the CLI is "All rights reserved, use subject to Anthropic's Commercial Terms",
  which do not address redistribution (`LICENSING.md:35`: the CLI is not redistributed). A private package for the
  operator's own cluster is the operator's own build in their own registry. Publishing it publicly needs Anthropic's
  written permission. Recorded options for wider use later: ship the recipe (today), build the image on first use,
  or ask Anthropic.
- The Codex CLI is Apache-2.0 and may be published with its licence notice; ADR-032 ("never redistributed") is amended.

## Scope of this PR (`:edge` on master)

1. `docker.yml`: the run worker joins the service matrix (root Dockerfile, `SERVICE=run-worker`, `apkUpgrade`).
   Publisher and model proxy build after `installDist` (Java set up in the job). Egress proxy joins as a plain build.
   The Codex image needs its two-pass build (build, read the catalogue with `codex debug models`, rebuild with
   `AGENT_MODELS`). The Claude Code image builds from `models.json`. PR runs build only, extended path filter
   (`deploy/agent/**`, `deploy/egress/**`, `spire-publisher/**`, `spire-model-proxy/**`). Master scans (Trivy) and
   pushes `:edge` and `:sha-<short>`. `ApkUpgradeIsNotCachedTest` covers every apk-based image.
2. Every published image carries `org.opencontainers.image.source`, `.licenses` and a LICENSE file for its own code.
   The Claude Code image carries **no** source label, so it does not link to (and inherit anything from) the public
   repository; after its first push the operator checks it is private.
3. `deploy/compose.ghcr.yml`: the unit images default to `ghcr.io/artyomsv/<image>:${SPIRE_VERSION:-edge}`, so they
   match the run worker's tag. `SPIRE_RUN_EGRESS_IMAGE`, `SPIRE_RUN_REGISTRY_*` (private pull for Claude Code) and
   `SPIRE_FACTORY_AGENT_IMAGE_CLAUDE_CODE` are settable in both compose files and `deploy/.env.example`.
4. The run worker pulls an image only when the daemon lacks it, so a moving tag never updates. The docs say: set
   `SPIRE_VERSION` to a `sha-…` or version tag; with `edge`, pull the unit images on upgrade.
5. Docs: ADR-032 amendment, `LICENSING.md` (map `deploy/agent`, `deploy/egress`), `docs/CICD-AND-PACKAGING.md`,
   CLAUDE.md "Build & run", `deploy/README.md`.

## Out of scope (own PRs)

- `release.yml` (versioned, multi-arch, signed) and its gate `.env` gaps.
- The Kubernetes deployment, and a compose file for a run worker that reaches a remote cluster's Kafka/Postgres/OIDC.
- The packaged run worker's Docker socket access on Linux (uid 1001): proven in the live check; fixed in its own PR
  if it fails.

## Operator steps after merge

Make the five public packages public in GHCR package settings; confirm `spire-agent-claude-code` is private.
