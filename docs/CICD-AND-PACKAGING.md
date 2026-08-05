# CI/CD and packaging — analysis and plan

> **Status: delivered 2026-08-05.** Nine workflows, four published images, and a `deploy/` tree
> covering Compose, Helm and kustomize from one source of truth. The design is
> `docs/superpowers/specs/2026-08-05-cicd-and-packaging-design.md`; the build order is
> `docs/superpowers/plans/2026-08-05-cicd-and-packaging.md`.
>
> The analysis below is kept as written, because most of it held. Where reality disagreed it is marked
> **[delivered]** rather than quietly edited — §6's ordering argument in particular turned out to be
> only half the story: D10 did not merely *gate* this work, it **added scope to it**, and the biggest
> single piece of the delivery is a thing this document did not anticipate at all.

Scope: GitHub Actions to verify the work, and installation manifests covering local Docker,
Kubernetes/kustomize, Helm/ArgoCD and (a decision on) Terraform, with images published to the GitHub
Container Registry as a public repository.

---

## 1. Starting point

**Historical.** Every row below was true on 2026-08-03 and none of it is true now: `.github/` holds
nine workflows, four production images publish to GHCR, and `deploy/` carries the Compose, Helm,
kustomize and rendered-manifest tree. Kept because "there was no CI at all" explains several later
decisions — notably why nothing here adopted a stricter quality gate at the same time as adding the
scanner that surfaced it.

| | State (2026-08-03) |
|---|---|
| `.github/` | **Does not exist.** There is no CI of any kind. Every "CI-shape" phrasing elsewhere in the docs means a *local* command (`./gradlew build`, `npm run test`, `tsc --noEmit`). |
| Images | **Dev-only.** `Dockerfile.dev` is a JDK 25 image that runs `gradlew quarkusDev` with source streamed in by `docker compose watch`. `spire-ui/Dockerfile.dev` is the Vite dev server. **No production image exists for anything.** |
| `docker-compose.yml` | Infrastructure only — Postgres (`34432`) and Redpanda (`34092`). |
| `docker-compose.dev.yml` | The four services in live-reload mode on `392xx`, plus JDWP, plus the opt-in Cloudflare tunnel profile. |
| Kubernetes / Helm / kustomize / Terraform | **None.** |
| Quarkus container-image extension | Not present in any `build.gradle.kts`. |

Four deployables to package: `spire-gateway`, `spire-orchestrator`, `spire-review-worker`
(Quarkus/JVM) and `spire-ui` (static assets behind a web server).

This is greenfield — there is nothing to migrate, only to build.

---

## 2. Prior art: `artyomsv/marauder`

A public Go project of the same author with a mature pipeline. **11 workflows** and a `deploy/`
tree. What is worth copying is not any individual workflow but the *derivation chain*:

```
deploy/helm/marauder/                ← single source of truth
   ├─→ deploy/kustomize/{base,overlays/*}   inflates the chart: helmCharts: + --enable-helm
   └─→ deploy/k8s/{simple-db,cnpg}/*.yaml   rendered plain YAML, via deploy/render-manifests.sh
                                             CI runs render-manifests.sh --check → drift fails
```

Helm users, kustomize users and "just `kubectl apply` a file" users all receive **the same
manifests**, and they cannot silently diverge — the drift check turns a discipline problem into a
build failure. This is the "unified configuration" requirement, already solved in a repo we own.

### Workflow split, by cost

| Workflow | Trigger | Role |
|---|---|---|
| `ci.yml` | every PR + push to main | Fast feedback, targets < 3 min. Tests, vet, lint, coverage. |
| `docker.yml` | main + tags | Builds every image and Trivy-scans it. **Does not push.** |
| `release.yml` | `v*` tag | Multi-arch buildx → GHCR, cosign keyless signing (OIDC), CycloneDX SBOM, build provenance, GitHub Release from the CHANGELOG section. |
| `helm.yml` | `deploy/**` paths | `helm lint` on both presets, `tests/render.sh`, **`render-manifests.sh --check`**, `kubeconform` (with the CRDs-catalog for third-party schemas), `kustomize build` of base + every overlay, and a kind-based install smoke test. |
| `codeql.yml`, `e2e.yml`, `nightly-build.yml`, `auto-release.yml`, `client-acceptance.yml`, `site.yml`, `dependabot-auto-merge.yml` | various | Everything heavyweight is kept **out** of the PR path so a typo fix is not blocked by a full compose-stack E2E. |

### Deployment presets

Two tiers, expressed as values files and matching overlays: `values-simple-db.yaml` (bundled
Postgres, for a homelab) and `values-cnpg.yaml` (CloudNativePG + Barman → S3, for a real cluster).
The same two-tier shape maps directly onto our needs.

### Two decisions marauder already made

- **Trivy is report-only** (`exit-code: '0'`, SARIF to the Security tab). Rationale recorded in the
  workflow: the residual HIGH/CRITICAL findings on a fully-patched image are language-stdlib CVEs
  fixed only in unreleased toolchain versions — an advisory treadmill no bump can clear. Fixable
  module/OS CVEs are bumped promptly and triaged from the Security tab. Same reasoning will apply
  to a JVM base image.
- **Terraform: zero files.** Not an oversight — see §4.

---

## 3. Recommended shape for Code Spire

Copy the derivation chain verbatim. Adapt the rest.

### Images

Publish four to `ghcr.io/artyomsv/`:

```
spire-gateway   spire-orchestrator   spire-review-worker   spire-ui
```

**Use one parameterised multi-stage Dockerfile for the three Quarkus services**, plus a separate
static-serving image for the UI.

Rationale: `Dockerfile.dev` is *already* one shared image parameterised by compose (the `command`
picks the Gradle module), so this matches an in-repo precedent rather than inventing one. It also
keeps marauder's buildx / GHA-cache / Trivy / cosign / SBOM pipeline copyable almost verbatim.

**Rejected: `quarkus-container-image-jib`.** It is the idiomatic Quarkus route and would avoid a
Dockerfile entirely, but it moves image construction into Gradle and away from the buildx toolchain
that multi-arch, layer caching and scanning all assume. The coupling costs more than the Dockerfile
saves. Revisit only if Dockerfile maintenance becomes a real burden.

**Rejected: Quarkus native images.** Build time and the reflection-heavy dependency set (LangChain4j,
Jackson polymorphism, Flyway) make this a poor trade for a self-hosted service that is not
latency-sensitive at startup.

### Presets

| Preset | Postgres | Kafka | Intended for |
|---|---|---|---|
| `simple` | bundled, PVC | bundled single-node Redpanda | self-host / homelab / evaluation |
| `production` | external, or CloudNativePG | external, or Strimzi | a real cluster |

### Deliverables

```
deploy/
  docker-compose.yml            infra + services, built locally
  docker-compose.ghcr.yml       infra + services, pulled from GHCR   ← the "one command" install
  .env.example                  the config contract
  helm/spire/                   the chart: single source of truth
    values.yaml  values-simple.yaml  values-production.yaml
    tests/render.sh             render assertions
  kustomize/base/  kustomize/overlays/{simple,production}/
  k8s/{simple,production}/spire.yaml     rendered, drift-checked
  render-manifests.sh           the generator, with --check for CI
```

Workflows: `ci.yml`, `docker.yml`, `release.yml`, `manifests.yml`, `codeql.yml`, plus
`dependabot.yml` covering gradle, npm, github-actions and docker.

---

## 4. Terraform — recommendation: **do not build it**

Terraform provisions *infrastructure* (VPC, cluster, managed Postgres, managed Kafka). Helm and
kustomize deploy *the application*. They solve different problems, and only the second is a gap.

Against building it now:

- **No target is chosen.** Terraform is not portable across clouds in any useful sense — an EKS +
  RDS + MSK module shares almost nothing with an AKS or Hetzner one. Writing it means picking a
  cloud we have not picked.
- **It cannot be verified in CI.** Every other artifact here gets a real check (`helm lint`,
  `kubeconform`, `kustomize build`, a kind install smoke test). Terraform's equivalent needs live
  cloud credentials and real spend. It would be the one permanently-untested surface in the set.
- **Precedent.** marauder reached a mature pipeline without any.

Revisit when a specific managed target is actually chosen. Until then, Helm + kustomize answer "run
it on a cluster", which is the question people are asking.

---

## 5. Complications marauder does not have

These are where the work is, and where copying blindly would go wrong.

1. **The gateway's security boundary must survive into the chart.** The gateway holds a *separate*
   Tink keyset (`SPIRE_ENCRYPTION_WEBHOOK_KEYSET`, never the master) and a Postgres role scoped to
   its own schema, so a compromised internet-facing edge can verify signatures but cannot read the
   SCM/LLM token registry or the event store. In compose that is a few lines and an init script
   (`infra/postgres-init`). In Helm it is two Secrets and two database users, and flattening them
   into one Secret and one superuser would **fail nothing and break everything** — the design in
   SECURITY.md would silently cease to hold. Any chart work must treat this as a hard invariant,
   with a rendered-manifest assertion proving the two are distinct.
2. **Kafka.** marauder has no broker at all. Adding one roughly doubles the chart and forces the
   bundled-vs-external decision into the preset split.
3. **Three services, three Flyway-owned schemas**, all migrating at boot on first deploy. Quarkus
   runs Flyway itself so this is mostly self-managing, but the first-boot race deserves a look.
4. **JVM images are large** (~10× a Go binary) and `linux/arm64` via QEMU is slow. Expect release
   builds in the tens of minutes; keep them off the PR path.
5. **Testcontainers.** The split tests need Docker for Kafka + Postgres. GitHub-hosted `ubuntu-latest`
   provides it, but this is the slow part of the suite and argues for a fast/slow job split.
6. **JDK 25 toolchain vs daemon JVM.** Gradle's `test` task uses the toolchain, but Quarkus
   *packaging* runs on the daemon's JVM. With `JAVA_HOME` on an older JDK, every test passes and
   `build` then fails at packaging with `UnsupportedClassVersionError ... class file version 69.0`.
   CI must set JDK 25 via `actions/setup-java`. (Observed locally on 2026-08-03.)
7. **Licensing (ADR-021).** The four deployables are FSL-1.1-ALv2, not Apache-2.0. Publishing images
   publicly is permitted — FSL allows self-hosting, internal commercial use and redistribution; it
   forbids reselling as a competing hosted service. But the images must **carry** the licence (OCI
   `org.opencontainers.image.licenses` label plus the LICENSE in the image), and none of the
   surrounding copy may call the project open source.

---

## 6. Why this was parked

**Historical, and the ordering was right for a reason this section did not state.** D10 landed on
2026-08-03 and this work followed on 2026-08-05. See §8: authentication first was not merely politeness
about inviting strangers in — the packaging *depends* on D10's design, because the single origin the
cookie scoping needs is produced by an artifact this document did not know it had to build.

**D10 (OIDC) is still open: the dashboard and every REST and WebSocket endpoint are
unauthenticated.**

Publishing polished one-command install manifests to a public registry actively invites people to
run this on a reachable network. Today the honest instruction is "bind it to localhost", which is a
strange thing to ship a Helm chart and an Ingress template for. marauder ships Keycloak and an SSO
compose profile; Code Spire has nothing equivalent.

The pipeline is not what *causes* the exposure, and building it does not make the app less safe. But
the ordering is wrong: authentication first, then the packaging that invites strangers in. Hence
D10 first.

When this is picked up, regardless of D10's state:

- A prominent "do not expose this" warning belongs in the chart's `NOTES.txt`, the README, and every
  compose file.
- Tagging a `v1` should be gated on D10, even if CI and images land earlier.

---

## 7. Decisions, as taken

| Question | Decision |
|---|---|
| First-pass scope | **Everything** — nine workflows plus the full `deploy/` tree. |
| Terraform | **No**, per §4. Not overridden. |
| Bundled vs external Kafka as the `simple` default | **Bundled** single-node Redpanda, matching `docker-compose.yml`. Bundled Postgres and **Keycloak** too — the services refuse to boot without an issuer, so a preset aimed at evaluation that expected one already running would not install. |
| Testcontainers tests on the PR path | **Yes, as their own job.** The seam already existed on module boundaries: 13 modules run on a bare JVM, three deployables boot Dev Services. It lives in Gradle (`testFast` / `testServices`) rather than a workflow file, so it stays runnable locally and can be guarded — a module in neither tier would otherwise be tested by nothing while CI reported green. |
| Does `docker.yml` push? | **Yes, `:edge`** — reversing §2's marauder precedent. `e2e.yml` consumes GHCR images, so with releases as the only publisher the topology check could not run until after the release it exists to protect. |
| Release notes | **Auto-generated** from commits. No CHANGELOG file: this repo's commit discipline already reads as release notes, and a maintained file can fail a release by omission. |

## 8. What D10 added — the part §6 missed **[delivered]**

§6 argued that authentication should precede packaging. Correct, but incomplete: ADR-022's isolation
mechanism is a property of the **deployment topology**, not of the code.

Each service scopes its session cookie to its own URL path, and cookies scope by host **and** path — so
that only isolates anything while all four services answer on **one origin**. In dev, the Vite
dev-server proxy silently supplies that origin. A packaged run has no Vite.

So §3's "a separate static-serving image for the UI" is **wrong as written**. The dashboard image is a
**reverse proxy**, and its routing is a security control. Consequences:

- **`/webhooks` must route to the gateway.** Missing, an SCM delivery reaches the SPA fallback instead.
  Measured, not assumed: it answers **405** (nginx refuses POST to a static file), so the SCM sees a
  failure and retries — every delivery fails and no review ever starts, noisily rather than silently.
- **Upstreams must resolve at request time.** With a literal hostname nginx resolves while parsing and
  refuses to start, so the dashboard crash-loops whenever it starts before its siblings.
- **`X-Forwarded-Proto` must pass an upstream value through**, never be derived from `$scheme`. Behind a
  TLS-terminating Ingress that forwards over http, deriving it yields an `http://` redirect_uri and a
  cookie with no `Secure` — broken only behind TLS, where a plaintext compose check passes clean.
- **`proxy-address-forwarding` needs `trusted-proxies`**, per `D10-AUTH-PLAN.md`. And writing
  `${SPIRE_TRUSTED_PROXIES}` with no default does **not** enforce it: an `Optional` config value
  swallows an unresolvable expression, and the image was observed starting cleanly with the variable
  absent — forwarding on, nothing restricting it. It is now a startup refusal.
- **The dashboard must sit at the origin root.** Every redirect target in the services is `/` and the
  cookie paths are absolute, so there is no sub-path deployment.

§5's complications all held. §5.3's Flyway worry was **misdiagnosed**, though: three services own three
schemas with three independent history tables, so there is no cross-service race. The real first-boot
constraint is that the gateway cannot connect at all until its scoped role exists — a Helm hook-ordering
problem, now a pre-install Job.

## 9. Two gates that looked stronger than they were **[delivered]**

Both were caught by measuring rather than trusting, and both are the same shape as the
`ContractSchemaSnapshotTest` vacuity hole:

- **`helm lint` reports missing required values as WARNINGS and exits 0.** A lint gate would pass a
  chart that cannot install. It is now informational; `tests/render.sh` assertion 8 requires
  `helm template` to fail without its required values, mutation-verified by giving them defaults.
- **The repository is not Semgrep-clean** under `p/default + p/secrets` — 54 findings, two pre-existing
  and 45 the advice to pin actions to commit SHAs. Semgrep therefore reports rather than blocks, and the
  pinning decision is `techdebt/global/4-2`. The four findings this work introduced were fixed.

`tests/render.sh --self-test` exists for the same reason: four of its eight invariants are *negative*
("this value must be absent"), and a negative assertion passes trivially when a key is renamed.

---

## References

- Prior art: `github.com/artyomsv/marauder` — `.github/workflows/`, `deploy/`
- [ROADMAP.md](ROADMAP.md) — **D10** (the gate), and the open "Packaging" item this document plans
- [SECURITY.md](SECURITY.md) — the gateway trust boundary that §5.1 must preserve
- [LICENSING.md](../LICENSING.md) — which modules are FSL and which are Apache-2.0
