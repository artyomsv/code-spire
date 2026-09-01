# Software Factory — Module Reference

High-level description of every new module: what it does, how it is used, what it depends on, and
which licence it carries under [ADR-021](../DECISIONS.md).

**The invariant is unchanged: no Apache-2.0 module may depend on a service module.** Permissive flows
into restrictive, never the reverse. That is why, for example, the credential pool lives in the
worker and not in `spire-harness` — the same reason the LLM circuit breaker could not live inside
`spire-llm`.

---

## 1. New modules at a glance

| Module | Licence | Kind | Milestone |
|---|---|---|---|
| `spire-harness` | Apache-2.0 | SPI + normalized types | M0 |
| `spire-harness-codex` | Apache-2.0 | adapter | M0 |
| `spire-harness-pi` | Apache-2.0 | adapter | M5 |
| `spire-harness-opencode` | Apache-2.0 | adapter | later |
| `spire-harness-claude` | Apache-2.0 | adapter | later |
| `spire-runtime` | Apache-2.0 | SPI + capabilities | M0 |
| `spire-runtime-docker` | Apache-2.0 | adapter | M0 |
| `spire-runtime-k8s` | Apache-2.0 | adapter | M5 |
| `spire-workspace` | Apache-2.0 | library | M0 |
| `spire-worksource` | Apache-2.0 | SPI | M3 |
| `spire-worksource-github` | Apache-2.0 | adapter | M3 |
| `spire-worksource-gitlab` | Apache-2.0 | adapter | M3 |
| `spire-worksource-jira` | Apache-2.0 | adapter | M3 |
| **`spire-run-worker`** | **FSL-1.1-ALv2** | deployable | M0 |

Plus additions to existing modules: `spire-contract` (new events, commands and value types),
`spire-orchestrator` (decider, saga, gates, entitlements), `spire-gateway` (tracker webhooks),
`spire-arch` (extended neutrality check), `spire-ui` (four new screens).

---

## 2. `spire-harness` — the agent-execution SPI

**Purpose.** Define what it means to drive an agentic coding tool, without naming one.

**Owns.** `HarnessAdapter`, `HarnessType`, `HarnessCapabilities`, `HarnessInvocation`, the normalized
`RunEvent` hierarchy, `TerminalOutcome`, `UsageReport`, and `FailureCause`.

**Does not own.** Process spawning, sandboxes, credentials, retry, or cost. An adapter turns an
invocation into argv plus environment, and turns one line of the tool's output into one normalized
event. Everything else belongs to the runtime or the worker.

**Framework-free**, like `spire-contract` and `spire-diff`, and added to
`PureModulesAreFrameworkFreeTest` on arrival. The one permitted exception is the existing
`jackson-annotations` carve-out, and only if these types cross the Kafka wire.

**Why the normalized event type lives here and not in `spire-contract`:** most run events never reach
the domain log (ADR-033). Putting the high-volume vocabulary in the contract module would imply a
durability guarantee that tier does not have.

---

## 3. `spire-harness-codex` — the first arm

**Purpose.** Drive OpenAI Codex CLI (Apache-2.0) non-interactively.

**Shape.** `codex exec --json` with `--sandbox` and `--ask-for-approval never`, producing
newline-delimited JSON events and an honest process exit code. Capabilities declared: streaming yes,
cancel yes, structured output yes, steer no, resume no.

**Notable.** Codex reaches any OpenAI-compatible endpoint through `model_providers` with `base_url`
and `env_key`, so this arm is not model-locked. It also **ignores `model_provider` set in a
repository's own config**, which is the same defence as ADR-035 and means the adapter must supply
provider configuration from the operator side, never from the checkout.

---

## 4. `spire-harness-pi` — the second arm

**Purpose.** Drive `pi` (MIT), and thereby prove the seam.

**Shape.** `-p` for one-shot, `--mode json` for NDJSON events, and `--mode rpc` for a bidirectional
JSONL session over stdio — the only arm of the four that can carry **steer**. Capabilities: streaming
yes, cancel yes, steer yes, resume yes.

**Notable.** `pi` is not a model vendor; it points at whatever provider credential it is given, so it
uses the existing encrypted LLM provider registry and its runs are fully **metered**. Building this
arm second is what turns `spire-harness` from an assertion into a seam.

---

## 5. `spire-harness-opencode`, `spire-harness-claude` — later arms

`opencode` (MIT) is shaped like `pi`: provider-agnostic, `opencode run` headless, with `opencode
serve` available where a plain-text stream is too weak to parse.

Claude Code carries **no open-source licence**, so it is installed into an image, never redistributed,
and is API-key only — Anthropic's Consumer Terms prohibit automated access except via an API key. See
[EXECUTION-LAYER.md](./EXECUTION-LAYER.md) for the quoted terms.

---

## 6. `spire-runtime` — the placement SPI

**Purpose.** Define where a workload runs and how its lifecycle is controlled, without naming a
runtime.

**Owns.** `RunRuntime`, `RuntimeType`, `RuntimeCapabilities`, `RunSpec`, `RunHandle`, `Finalization`.

**The load-bearing detail:** `finalize` and `destroy` are separate operations. Salvage — commit,
push, usage extraction, artefact collection — happens in `finalize`; only then may `destroy` run.
Merging them is how completed work gets thrown away, and it was the second most common failure cause
in the observed prior art.

`discoverOrphans()` exists because a control plane restart must be able to find sandboxes it no
longer remembers. Both arms can implement it; it simply has to be somebody's job.

---

## 7. `spire-runtime-docker` — the first placement arm

**Purpose.** One sibling container per run, over the Docker socket.

**Capabilities.** Resource limits yes, network policy coarse (deny by default plus an allowlist),
steering per-harness, archival yes, GC yes.

**Security note, stated rather than mitigated:** access to the Docker socket is root-equivalent on
the host. Rootless Docker or Podman narrows it; the Kubernetes arm removes it. This belongs in
`SECURITY.md`, not in a footnote.

---

## 8. `spire-runtime-k8s` — the second placement arm

**Purpose.** One Pod per run, no socket, real `NetworkPolicy`, real resource limits, TTL cleanup.

Lands at M5 alongside `spire-harness-pi`, so the two seams are proven together. The deployment
artefacts already exist — Helm chart, kustomize inflation and rendered manifests — so this arm lands
into infrastructure rather than beside it.

---

## 9. `spire-workspace` — git, as a library

**Purpose.** Everything the run plane does with a repository that is not a network call to a forge:
clone or worktree at an explicit base commit, branch naming, commit detection, push, and the
"commits ahead" check that distinguishes real work from an empty run.

**Why a module rather than code inside the worker.** It is pure, it is heavily conditional (shallow
vs full, worktree vs clone, force-push vs fast-forward, empty-diff detection), and it is exactly the
kind of logic that deserves tests without a container. It is also the natural home for the
`empty push` signal that a plan step with nothing to commit must produce, or a plan deadlocks
waiting for a pull request that never opens.

**Depends on** a git-push credential brokered to it; it never mints one itself. **That brokering does
not exist yet.** Today the provider registry token is handed to a command for API calls only, and
nothing in Code Spire has ever pushed a commit — so "whether the registry token doubles as the push
credential, per forge" is an M2 decision, not an assumption. Under ADR-037 the credential belongs to
the **dedicated machine account**, not to the review bot.

---

## 10. `spire-worksource` and its arms — the tracker as a queue

**Purpose.** Read a tracker as a work queue and write back to it.

**Owns.** `WorkSource`, `WorkSourceType`, `WorkSourceCapabilities`, `WorkItemRef`, `WorkItem`,
`WorkQuery`.

**Relationship to `spire-context-*`.** The context modules already hold credentials for Jira,
Confluence, GitHub Issues and GitLab Issues and already speak those APIs through the SSRF-guarded
`spire-http` client. The work-source arms reuse those clients. The difference is **rights, not
transport**: a context provider reads an issue as context; a work source also claims, comments and
transitions it. That distinction is what makes Knowledge and Build separate product packs.

**Capability flags matter here.** Jira has transitions and a workflow; GitHub Issues has labels and
state; GitLab has both plus epics. The domain reads capabilities and degrades, rather than assuming
a workflow exists.

---

## 11. `spire-run-worker` — the only deployable that opens a sandbox

**Purpose.** Consume run commands, drive a runtime and a harness, stream events, salvage, push, and
report an outcome.

**Owns.**

- The **credential pool** and its rotation state machine (see [EXECUTION-LAYER.md](./EXECUTION-LAYER.md)).
- The **idempotency claim** per `(run_id, slot)`, so a redelivered command never re-spends.
- The **orphan watchdog**.
- Credential injection and redaction.
- Its own `worker` schema, unreachable from the orchestrator's role at the database level — the same
  separation the existing worker already proves in the packaged end-to-end checks.

**Explicitly does not own.** Autonomy decisions, gates, budgets or entitlements. Those are read in
the orchestrator before dispatch. A worker that could decide policy is a worker whose compromise
grants policy.

**Consumes** `cs.commands`; **produces** `cs.results` and `cs.run-events`; dead-letters to `cs.dlq`.

**FSL-licensed**, like the other three deployables.

---

## 12. Additions to existing modules

### `spire-contract` (Apache-2.0, framework-free)

New sealed hierarchy members for the commands, results and domain events in
[ARCHITECTURE.md](./ARCHITECTURE.md) §6, plus value types shared across services: `AutonomyProfile`,
`GateMode`, `PhaseName`, `Entitlements`, `RefusalReason`, and the `ArchivedNotice`-style constant
slots for run claims.

**Contract-snapshot caveat.** The existing snapshot gate renders a nested record component as
`name: TypeName` and does not recurse, so nested wire types are invisible to it — a known tech-debt
entry that has already let two changes through. Every new *nested* type introduced here must be
reviewed by hand until that gate recurses.

### `spire-orchestrator` (FSL)

`WorkItemLifecycle` decider; `RunSaga` owning staleness and retry; gate open/resolve/expire; the
entitlement check placed **beside** `SpendGate` and the priceability check, so every reason a
dispatch was refused reads in one place.

### `spire-gateway` (FSL)

Tracker webhooks on the existing per-repository keyed registry edge, so a work source registers the
way a forge already does. No new secret-handling shape.

### `spire-arch` (Apache-2.0)

The neutrality scan extends to harness, runtime and work-source names, with the composition roots
allowlisted and reasoned exactly as the SCM ones are. Added in the same commit as the first seam.

### `spire-ui` (FSL)

Four new areas: **Work items** (list, detail, phase timeline), **Runs** (list, detail with lifecycle
strip, live event stream, budget panel, prompt), **Approvals** (open gates, expiring soon), and
**Factory settings** (profiles, label mapping, image allowlist, credential pool health).

**One rule from hard experience:** every new backend status must be added to the UI's status union,
its label map, its pipeline renderer and its chip filter in the same change. A status the union does
not know arrives as runtime JSON, renders through the default branch — which is the *success* branch
— and `tsc` has nothing to check. That has happened twice.
