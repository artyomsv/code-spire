# Code Spire — project context

> **"Code Spire" is a working name, not the final product name.** Treat it as provisional: don't
> register trademarks, buy domains, or design brand assets around it, and don't add new user-visible
> occurrences of it. The user-facing name lives in **six production literals across four files**:
> `PromptCatalog.REVIEW_PERSONA` and `.FOLLOWUP_PERSONA` ("You are Code Spire…"), `ReviewWorker`'s
> summary header (`"### Code Spire review"`, also asserted in `FindingConversation.test.ts`), the bot
> display name in `FindingConversation.tsx` and `render.tsx`, and copy in `PromptsSettings.tsx`.
> Renaming means all six — it spans backend *and* UI. Centralising them into one constant is the
> obvious cheap win if a rename looks likely. The internal surface (`dev.codespire` package group,
> `spire-*` modules, `SPIRE_*` env vars, docker volumes) is private and need not follow a product
> rename.

Self-hosted, event-driven, plugin-first AI code reviewer. One bot account reviews every PR in a
workspace via webhooks (no per-seat licensing); SCM platform, LLM provider, context sources, and
storage are pluggable. Bitbucket Cloud first. **Source-available, split per module** (ADR-021):
Apache-2.0 for the plugin SPI, libraries and reference adapters; FSL-1.1-ALv2 for the runnable
services — see `LICENSING.md`. Never call the project "open source" in docs or UI.

## Read first

Before writing a new dashboard widget, check [spire-ui/docs/WIDGETS.md](spire-ui/docs/WIDGETS.md)
for the existing components, vocabulary and account/person helpers.

The design is fully specified in `docs/` — **treat those files as the source of truth**:

| Doc | Contents |
|---|---|
| `docs/PRD.md` | Problem, users, goals, FR-1..13 / NFR-1..9, scope, success criteria |
| `docs/ARCHITECTURE.md` | Event-driven plugin-first core; module layout; build sequencing |
| `docs/EVENT-MODEL.md` (+ `docs/diagrams/event-model.html`) | Slices S1–S11 in Event Modeling notation |
| `docs/CONTRACT.md` | Event/command catalog, `ReviewLifecycle` decide table, SPI ports, topics, Bitbucket mapping |
| `docs/DATA-MODEL.md` | Value types, event store, object store, read models, encryption boundaries |
| `docs/SCM-MAPPING.md` | Provider-neutral SCM model verified against Bitbucket/GitHub/GitLab/DC APIs |
| `docs/SECURITY.md` | Trust boundaries, OIDC/RBAC, Tink encryption, LLM threat model, cost gaps |
| `docs/TLS.md` | The five requirements a TLS terminator must satisfy, the identity-provider leg included, three worked topologies, and a symptom table. Code Spire terminates no TLS by design |
| `docs/REPO-RULES.md` | The `.codespire` file: format, the target-branch rule and why, writing effective rules |
| `docs/DECISIONS.md` | Architecture decisions and their rationale. ADR-029..040, ADR-042..045 cover the software factory; `docs/factory/` explains them in context |
| `docs/UNVERIFIED.md` | **Read before claiming something works.** The register of claims the code or the docs make that no test establishes — known-broken-and-guarded, fixed-but-never-run-live, paths no test reaches, and claims needing a corpus or spend. Three milestones in a row shipped a feature that was green, documented, and did not work |
| `docs/RESEARCH.md` | Market landscape + the PR-Agent code evaluation that justified greenfield |
| `docs/ROADMAP.md` | Phases P0–P4 with exit criteria |
| `docs/HISTORY.md` | The per-milestone delivery log: what shipped, what each review round found, the traps each one paid for. **Append new milestones there**, then rewrite the Status snapshot below |
| `docs/factory/` | **M0–M3 implemented (PRs #95/#96/#119/#153); M3 final review pending, M4–M6 designed.** The software factory: work item → spec → plan → sandboxed agent runs → branch → PR reviewed by the existing reviewer. PRD (FR-F1..F32), architecture, module reference, execution layer (harness terms quoted with retrieval dates), run topology, autonomy model, product packaging, prior art, M0–M6 build order, and `AGENT-IMAGE-CONTRACT.md` — the published contract any agent image may satisfy, checked by `spire-agent-image verify`. Decisions are ADR-029..ADR-040. ROADMAP's M0 section records what the build taught that the design had wrong |
| `docs/CICD-AND-PACKAGING.md` | **Parked plan.** No CI exists today; analysis of GitHub Actions + GHCR images + Helm/kustomize/ArgoCD, why Terraform is declined, and why it waits for D10 |
| `docs/D10-AUTH-PLAN.md` | **Planned, not started.** The auth gate: hybrid OIDC, per-service URL prefixes so cookie scoping is real, the spike that must precede code, and the two designs review falsified |

## Status (a snapshot — rewrite it, never append to it)

Measured on **2026-09-16**. Delivery history is in docs/HISTORY.md; the consolidated
[M3 acceptance record](docs/factory/M3-ACCEPTANCE.md) maps all seven verified criteria to their
proving slices and reviews. **M3 is on master**, pushed directly on 2026-09-16 by the operator's
decision rather than merged through PR #153, together with the operator-experience fixes.
**M3.5, "one ticket to a build", is next** (operator-experience specification §2.4), then M4 with
verify as its first slice.

- **The reviewer (P0–P4) is delivered.** Gateway, orchestrator and review worker communicate over
  Kafka, with the React dashboard. Bitbucket Cloud, GitHub and GitLab have measured live reviewer
  parity. Jira, Confluence, GitHub/GitLab Issues, repository rules and rung-2 code knowledge supply
  context. Repository prompts, /finding, learned memory, analytics and operator SCM sign-in remain.
- **Operations are delivered.** Hybrid OIDC uses separate service prefixes and session cookies;
  stored sensitive content is encrypted with Tink. The charge ledger, fleet caps, refused status,
  archive policy, attention, circuit breakers, provider-neutral boundaries and split licensing
  remain. CI, Compose, Helm/kustomize and the separate GitLab e2e tier are present.
- **M0–M2 execution and standalone /fix are delivered.** The Docker run unit, trusted publisher,
  durable transcript/control, salvage and orphan watchdog, harness pool, corporate environment
  and image contract remain. Per-finding and per-review fix caps still apply. The live chain was
  proved on 2026-09-12 by runs 3987682681:1 and 3987682176:1, then re-proved on TEST PR #32 by
  run 4003204361:1 on 2026-09-14: automatic push, next review, resolved thread and persisted
  verdict, with the other six prior findings UNCHANGED. The TEST PR was closed and its exact
  branch deleted; review/run history was retained. The development run worker remains stopped.
- **M3 owns repositories, people and work-item policy.** Explicit repository-role bindings replace
  account workspace lookup. People resolve to stable provider IDs and render observed handles;
  source actor allowlists remain independent. /fix measures effective push access through the
  selected reviewer, with explicit ALLOW/DENY overrides. V72 now drops only the unused account
  workspace column after a fresh validated backup. IDs, ciphertext/AAD, context references,
  bindings, immutable legacy mapping evidence and live history are preserved.
- **M3 intake and approvals are durable.** GitHub/GitLab/Jira source parity is derived from adapters
  and test sources. Unknown label attribution selects nothing. Current labels, pinned admission
  bounds and the current ceiling restrict each next action. Scanner pages and uncertain tracker
  writes survive real process death. Humans register actual specification and single-step plan
  references; bodies stay transient. Suggest stops before BUILD, assisted requires PLAN approval,
  and autonomous admits one prepared build. UI proofs compare phases, gates, decisions and runs.
- **M3 item publication and takeover preserve authority through restart.** Item builds checkpoint
  without pushing and retain their workspace. An exact current permit resumes only the publisher.
  Dashboard answers, bound tracker commands and supported native PR reviews share ResolveGate.
  Ordinary approving prose cannot approve; stale/dismissed reviews cannot resolve a gate.
  Takeover uses recorded stable machine IDs across rename/rotation, supersedes gates, invalidates
  pending effects and persists publication revocation before stopping compute. Actual JVM death
  without an M1 cancel claim cannot restore publication authority. In-flight PR recovery records
  the observed outcome once and keeps the item suspended. Resume requires a verified operator,
  expected revision, note and fresh evidence; retired items cannot resume.
- **The operator's first live item test shaped the work-item screens.** Item #36 stopped at
  `verify / awaiting_input / run_usage_unknown`, and the ten findings, the operator's decisions and
  the chosen mockups are in docs/superpowers/specs/2026-09-15-factory-operator-experience-design.md.
  Work items is one triage list with filter counts; an open decision opens beside it, and Approve
  waits until the texts on screen match the binding the gate stores. The detail page shows eight
  journey steps with their proof, and `/approvals` redirects to the Needs-you filter. Refusals name
  their rule, harness and model are selects, the branch head comes from the forge, and run detail
  names the key that paid. Spec and plan tickets, the plan JSON and the pricing stop remain (M3.5).
- **Measured final validation (2026-09-14):** 4076 Java tests across 452 suites and 30 modules,
  zero failures/errors and 1 existing Windows symlink privilege skip. Forced testFast,
  testServices and packaging passed sequentially. The full UI passed 742 tests across 93 files,
  TypeScript and production build. Slice 10 adds four verified production mutations (the real
  migration and separate read/INSERT/UPDATE guards); slice 9 has 78 distinct production mutations.
  Earlier per-slice counts and selectors remain in the acceptance record, without claiming a
  globally deduplicated total. Pinned Semgrep reports zero findings across 5 changed code files.
  The final scan and rollout evidence are in
  .claude/reviews/global/factory-m3-slice10.md.
- **Re-measured on 2026-09-16,** after the operator-experience work and master's dependency updates:
  the full UI passed 847 tests across 98 files, TypeScript and production build; spire-orchestrator
  passed 1764 tests in 202 suites; testFast passed. The other testServices modules and packaging
  were not re-run.

**Limits that remain:**

- **Production VERIFY and LAND remain unavailable.** M4 owns the verifier; M3 does not ship one.
- **No live item-build proof.** TEST PR #32 proves standalone /fix; real local-origin item tests
  do not prove an item-linked build against a real forge.
- **The automated GitLab run-unit gap is still open.** RunUnitSpec has no network field, so a
  run unit cannot reach the e2e stack's GitLab. A live GitHub run does not close it.
- **The two factory images are still not on GHCR.**
- Per-forge identity and permission limits remain separate UNVERIFIED entries. Native external
  gate answers and operator resume have no live proof. GitLab/Bitbucket native PR approvals and
  Jira Data Center comment polling remain unavailable. Publication already in progress cannot
  be recalled; no atomic ordering with a remote human push is claimed.

## Build & run

JDK 25 (SDKMAN `25.0.3-tem`) + Docker required.

```bash
cp .env.example .env                      # set POSTGRES_PASSWORD (dev-only)
docker compose up -d                      # Postgres :34432 + Redpanda :34092
./gradlew build                           # unit + per-service split tests (Testcontainers: Kafka + Postgres)
./gradlew :spire-orchestrator:quarkusDev  # dashboard at http://localhost:34080
./gradlew :spire-gateway:quarkusDev       # webhook edge :34081
./gradlew :spire-review-worker:quarkusDev # worker :34082
./gradlew :spire-run-worker:quarkusDev    # factory run worker :34083 (drives the local Docker daemon)
cd spire-ui && npm install && npm run dev # React dashboard :34000 (UI_PORT)
```

**The factory's two images** are not on GHCR yet and are built locally (SMOKE-TEST Mode Q):

```bash
./deploy/agent/build-codex.sh          # builds, then bakes in the model catalogue
./gradlew :spire-publisher:installDist && docker build -t spire-publisher:latest spire-publisher
```

`M0WalkingSkeletonTest` (`spire-run-worker`, `testServices`) builds the publisher image itself, plus
a test agent image and a smart-HTTP git origin, so it needs Docker and nothing else; leftover units
from a crashed run are `docker ps -a --filter label=dev.codespire.runId`.

**Fast local verification** — the same two tiers CI runs, so this is the pre-commit loop:

```bash
./gradlew testFast                        # 19 Docker-free modules, ~1 min
./gradlew testServices                    # 6 service modules: the 4 deployables on Dev Services (Postgres +
                                          # Kafka) plus spire-runtime-docker and spire-agent-image;
                                          # those two and spire-run-worker drive a real Docker daemon
```

**The packaged stack** (`deploy/`, host ports 347xx — distinct from dev's 34xxx and 392xx):

```bash
cp deploy/.env.example deploy/.env        # every value required, no defaults
docker compose -f deploy/compose.yml --env-file deploy/.env up -d --build   # built here
docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env up -d      # from GHCR
./deploy/e2e.sh http://localhost:34700 http://localhost:34767               # 21 checks
./deploy/helm/spire/tests/render.sh --self-test                             # chart invariants
./deploy/render-manifests.sh --check                                        # rendered-manifest drift
```

**The GitLab end-to-end suite** (`spire-e2e`, host ports 348xx — its own compose project, so it can
run alongside a packaged stack):

```bash
# Bring it up ONCE. GitLab needs ~6 minutes before it answers; poll rather than guess:
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env up -d --build
until curl -fsS http://localhost:34880/users/sign_in >/dev/null 2>&1; do sleep 15; done

set -a; . deploy/.env; set +a     # the suite reads POSTGRES_*, SPIRE_OIDC_*, DEV_OPERATOR_PASSWORD
./gradlew testE2e                 # re-runnable in seconds against the warm stack

# Tear it down when you are finished — this does NOT happen automatically.
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env down -v
```

**The stack deliberately outlives the run.** Nothing in `testE2e` starts or stops a container: GitLab
is a six-minute boot, so tearing down per run would make the iteration loop unusable and every local
failure expensive to reproduce. CI does the opposite — `.github/workflows/e2e.yml` ends with
`down -v` under `if: always()`, because a runner is thrown away and a leaked volume is nobody's
convenience. If you are wondering why `docker ps` still shows a GitLab: that is why, and the line
above is the cure.

Results land in `spire-e2e/build/reports/tests/test/index.html` locally; the nightly job uploads the
same report plus the JUnit XML as an artifact, and writes a per-suite pass/fail table into the run
summary. On a red run it additionally uploads `deploy/e2e-diagnostics.sh`'s capture — service logs,
the LLM mock's request journal, and GitLab's own webhook-delivery history.

## Conventions (enforced by design docs — do not regress)

- **Everything between components is an async event/command** — the only sync edge is webhook
  ingress returning 202. New capabilities subscribe to events; the core is never edited for them.
- **Domain events are appended ONLY by the aggregate** (single writer, ADR-010); workers emit
  integration events; sagas translate. All messages keyed by `reviewId`.
- **No hard-coded LLM/SCM provider** — config-selected, fail-fast when unset. No defaults for
  credentials anywhere; `.env.example` is the contract.
- **Diffs are never persisted** (ADR-011) — re-fetched by commit. Findings ride inline in events.
- **Sensitive fields (findings/context — may quote source) are Tink-encrypted at rest** in
  app-managed stores; the Kafka bus is covered by short retention + broker disk encryption
  (ADR-014), not app-layer crypto.
- **Money in millicents.** Host-exposed dev ports in the **34xxx** range.
- **Author identity** is data (stable `providerUserId`), never a gate; `email` never logged/persisted.
- Java 25 / Quarkus 3.38.3 / Gradle Kotlin DSL; **pure domain code stays free of framework imports** —
  build-enforced for `spire-contract` and `spire-diff` by `PureModulesAreFrameworkFreeTest`
  (`spire-arch`), which permits only the JDK, those modules themselves, and one documented
  exception: **`jackson-annotations`** (annotations only, no databind) on the wire
  event/command hierarchies and repository envelope records, because those types *are* the Kafka wire
  contract and their discriminators belong with them. Per-service mix-ins were considered and
  rejected: they spread one registry across every `ObjectMapper` in three services, where a missed
  site is a runtime wire break rather than a compile error. Adding a second exception means
  amending that allowlist, on purpose.
- **Test tasks that drive the real Docker daemon hold a lock, one at a time.** `spire-runtime-docker`,
  `spire-run-worker`, `spire-e2e` and `spire-agent-image` create, inspect and destroy containers on the one daemon, and
  `org.gradle.parallel=true` used to let two of them meet there — `DockerRunRuntimeIT` failed two
  cases whose symptoms ("no such container", a destroy that removed nothing) impersonate the exact
  defects the runtime exists to prevent, while passing 15/15 run alone. They now share a build service
  with `maxParallelUsages = 1`; the other service modules stay parallel, which is why this is a
  service and not `org.gradle.parallel=false`. `DockerTestsAreSerialisedTest` **derives** the module
  list by scanning test sources rather than trusting the declaration, so a module that starts driving
  the daemon and forgets to declare itself fails the build. Two bounds are deliberate: the lock covers
  one Gradle invocation (a second `./gradlew` or a `quarkusDev` worker still contends), and it is held
  for a whole `Test` task, since Gradle schedules tasks and not suites.

## Gotchas (each one cost a milestone — details and dates in `docs/HISTORY.md`)

- **Dev images bake their source.** `docker compose up -d` without `--build` runs the previous tree;
  a startup check once passed against code that did not contain the guard under test. A hash-route
  navigation does not reload the SPA, so a screenshot after a redeploy can show the old bundle.
- **A `RUN` with no changing input is cached forever, so a "build-time upgrade" runs once.**
  `apk --no-cache upgrade` sat in three images keyed only on the base image — i.e. it refreshed
  exactly when the base retagged, the wait it exists to skip — and 102 Trivy alerts stood open on
  packages Alpine had already fixed. `docker.yml` now passes `APK_UPGRADE_BUST=${{ github.run_id }}`,
  and the `RUN` **echoes** it: BuildKit keys a `RUN` on the args it actually references, so a
  declared-but-unmentioned `ARG` looks exactly like a fix. `ApkUpgradeIsNotCachedTest` holds both
  halves.
- **Scanning a floating tag locally measures your last pull, not the tag.** `docker run` and
  `docker build` resolve `:1.30-alpine` from the local store before the registry, so a Trivy result
  can be six weeks stale — which is how `spire-ui`'s base was reported as carrying four HIGH CVEs it
  did not have. `docker pull` first, then scan, and say which digest was measured.
- **A saga test fake with an un-overridden method opens a real database** from a plain unit test.
  Seven instances so far (`setNote`, `recordCharges`, `roundOrUnknown`, `markSuppressed`, …); six
  failed loudly with an NPE, the seventh sat under a `catch (RuntimeException)` and was **silent**.
  Override every method a new path reaches, with a comment saying why.
- **Adding a component to a wire record silently drops it at every rebuild site** — the shorter
  convenience constructors stay valid, so everything compiles. Use withers that enumerate the
  components once, next to the record (`withTruncated`/`withFindings`). And `ContractSchemaSnapshotTest`
  never recurses into a nested wire type (`techdebt/spire-contract/3-2-…`), so a change inside
  `ReviewResult`, `ModelUsage` or `Finding` is approved by nothing.
- **A new backend status is invisible to the UI's type system.** `ReviewStatus` in `api.ts` is a
  compile-time union and the status arrives as runtime JSON, so an unlisted value falls into the
  *success* branch — `refused` once rendered as five green segments, a degraded run as "✓ clean".
  Add the union member, `STATUS_LABEL`, the `miniPipeline` branch and the `needsAttention` predicate
  together. The TS `ReviewDetail` derives from `ReviewSummary`; the Java side is two independent
  records, so a field added to one arrives `undefined`.
- **Under vitest, Vite's `?raw` import returns an EMPTY string for a stylesheet** — a guard reading
  CSS must first assert it read something (`styles.contract.test.ts` uses `node:fs`). `vi.spyOn`
  re-wraps the same module function, so call history leaks between tests in a file;
  `vitest.setup.ts` restores all mocks.
- **Mutation-verify every guard**: break the production line, confirm exactly one test fails.
  Tests here have passed with their guard deleted because the list they iterated was empty, because
  a second guard covered the same case, or because the round chosen let a bound alone exclude the
  row. Write the discriminating case, not the obvious one.
- **A Quarkus `${VAR}` with no default does not enforce presence when the target is `Optional`** —
  `trusted-proxies` was silently empty with `proxy-address-forwarding` on. Pair it with a startup
  refusal.
- **nginx: a `location` that sets any `proxy_set_header` discards every header from the server
  block.** Keep all six on the server block and let no location set one; `render.sh` asserts both
  and `--self-test` proves the assertions bite.
- **Context providers fail soft**, so a provider resolving nothing looks exactly like a PR with no
  context. The only tell is `Context resolution for <KIND>: extracted=N resolved=0` in the worker
  log (`techdebt/global/3-3-…`).
- **The Kafka ack budget must exceed one command's worst case** — two LLM calls plus a 180s posting
  backoff. `LlmTimeoutBudget` refuses to start otherwise. A record that ages out is redelivered on
  every restart and stalls the consumer again (a poison pill cleared only by `rpk group seek`).
- **A dependency `force` in the root build pins DOWN once the platform catches up** — check the
  jackson/lz4 forces whenever Quarkus is bumped.
- **Agents agreeing is not evidence.** A wrong diagnosis of the e2e code-context failure reached
  five documents before anyone read the guard it blamed, and a four-lens review split on it. Verify a
  review's proposed *fix* as independently as its claim.
