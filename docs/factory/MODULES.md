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
| `spire-secrets` | Apache-2.0 | library | M1 (debt round) |
| `spire-agent-image` | Apache-2.0 | conformance checker (CLI) | M1 |
| **`spire-publisher`** | **FSL-1.1-ALv2** | **deployable (sidecar image)** | **M0** |
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
the domain log (ADR-034). Putting the high-volume vocabulary in the contract module would imply a
durability guarantee that tier does not have.

---

## 3. `spire-harness-codex` — the first arm

**Purpose.** Drive OpenAI Codex CLI (Apache-2.0) non-interactively.

**Shape.** `codex exec --json --sandbox danger-full-access --skip-git-repo-check -C <dir>`, producing
newline-delimited JSON events and an honest process exit code. Capabilities declared: streaming yes,
cancel yes, structured output yes, steer no, resume no.

**Two facts verified against the binary, not the documentation (2026-09-01):** `--ask-for-approval`
does **not** exist in 0.152.0, and the event stream is shaped
`{"type":"item.completed","item":{…}}` / `{"type":"error","message":…}` rather than the
`agent_reasoning` / `exec_command_begin` names the docs suggest. The sandbox mode is
`danger-full-access` because Codex's own sandbox is bubblewrap-based and cannot initialize under
Docker's default seccomp profile — see [RUN-TOPOLOGY.md](./RUN-TOPOLOGY.md) §1.

**Notable.** Codex reaches any OpenAI-compatible endpoint through `model_providers` with `base_url`
and `env_key`, so this arm is not model-locked. It also **ignores `model_provider` set in a
repository's own config**, which is the same defence as ADR-036 and means the adapter must supply
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

`discoverUnits()` exists because a control plane restart must be able to find sandboxes it no
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

**Where it runs — not in the worker (ADR-039).** This library is linked into the **publisher image**
and runs inside the run pod. The worker holds no workspace and no git at all; that was the design
that made it stateful and broke run recovery.

**Why a module rather than a shell script in the image.** The gate's correctness lives in details a
script gets wrong: rename detection on both sides, deletions counting, case-insensitive matching for
the CI floor. Keeping it as tested Java means `./gradlew test` covers the security-critical part, and
one glob dialect serves the whole product.

**Depends on** a git-push credential handed to the publisher container; it never mints one itself.
**That brokering is DELIVERED** (M0): the FACTORY-role registration's token is packed per command,
unpacked into read and write slots, and used by `PublishRepo.push` against a real remote. Under
ADR-038 the credential belongs to the **dedicated machine account**, never the review bot. Still
missing is a read-scoped clone token — one token serves both legs today (`docs/UNVERIFIED.md` §E).

---

## 9b. `spire-secrets` — one credential scrubber, carrying nothing

Removes a run's credentials from text about to be stored or logged, in the three forms a credential
takes: the literal, percent-encoded inside a URL, and `base64(user:secret)` in a Basic header. The
worker's `RunFailed` details and every failure line the publisher writes both go through it.

**Why a module and not a class in an existing one.** The worker and the publisher shared no module
at all, and the obvious home — `spire-workspace`, which the publisher already depended on — turned
out to be the wrong one for a reason worth recording. That module exposes JGit as `api`, so
depending on it put `org.eclipse.jgit` on the run worker's compile and runtime classpath: the
process whose entire claim is that it runs **no git** (§11, ADR-039) suddenly carried a git library.
A source scan can refuse an import; it cannot refuse a capability that is merely present, so the
build guard added alongside was green while the invariant was gone.

So this module depends on the JDK and nothing else. That is what lets an FSL service and an Apache
library both consume it without either inheriting the other's world, and it is the same argument
`spire-http` was extracted under: one home for a guard, carried by nothing.

**Enforced.** `spire-arch`'s `RunWorkerRunsNoGitTest` fails the build if `spire-run-worker` takes
anything at all from `dev.codespire.workspace` — the allowlist is empty, and an entry there would be
a statement that whatever that module drags onto the classpath is acceptable in a process that must
hold no working copy.

**One behaviour worth knowing.** Every secret is scrubbed, whatever its length.

A floor once skipped anything under eight characters, on the reasoning that redacting a short string
turns every innocent occurrence of it into the marker and leaves an operator a failure detail they
cannot read. That reasoning about readability is right and the trade was wrong: it spent a security
property to buy a legibility one, at the instant the value IS a live credential. Gitea and Forgejo
issue six-character git-over-HTTP passwords, and one was reaching `factory_run.failure_detail`
verbatim.

`MIN_PLAUSIBLE_SECRET_LENGTH` survives only as the threshold for a warning that redacting a short
secret will also hide innocent text — one an operator can act on, where the old silence was not.
There is deliberately no refusal anywhere: "a credential must be eight characters" is a rule about
what an operator may configure, and it would block a working Gitea deployment.

## 9a. `spire-publisher` — the sidecar that gates and pushes

**Purpose.** A small Java program, packaged as its own image, that runs as a **sidecar beside the
agent** for the life of a run. It is the only thing in the pod holding a write credential.

**What it does, in a loop:**

1. Clone the branch from the forge into `/publish` — **its own, pristine copy**.
2. Watch `/handoff` (mounted **read-only**) for new bundle files.
3. `git fetch <bundle>` — objects and refs only.
4. `git diff --name-status -M <base> FETCH_HEAD` → the push gate.
5. Refuse and terminate, or push the branch.
6. Report each outcome as one JSON line on **stdout**, which the worker reads from the log stream.

**What it must never do**, and these are the security properties rather than style preferences:

- never mount or read `/workspace` — the agent's directory,
- never check out a working tree from agent data — fetch, diff, push, object-level only,
- never write to a shared volume,
- never read more than the configured bundle size cap.

**Why a separate image rather than logic in the agent image:** the agent image holds the model
credential and runs untrusted-influenced output; the publisher image holds the write token. Two
images, two credentials, no overlap.

**FSL-licensed**, like the other deployables — it is a running service, not a library.

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

**Does NOT own, since ADR-039:** any git operation, any workspace, any filesystem state. It creates
the run pod, streams two log channels (agent events, publisher outcomes), records charges, and emits
results. The clone, the gate and the push all happen inside the pod. That is what makes it
**stateless** — and therefore what makes a run recoverable by any replica rather than only by the one
that started it.
- Its own `runworker` schema — its own, not the review worker's `worker` schema — unreachable from
  the orchestrator's role at the database level, the same separation the existing worker already
  proves in the packaged end-to-end checks.

**Explicitly does not own.** Autonomy decisions, gates, budgets or entitlements. Those are read in
the orchestrator before dispatch. A worker that could decide policy is a worker whose compromise
grants policy.

**Consumes** `cs.run-commands` and `cs.run-control`; **produces** `cs.run-results` and
`cs.run-events`; dead-letters to `cs.dlq`. Its own topics, never the reviewer's — `ActionCommand`
declares `reviewId()` as mandatory and a run has a `runId`. Control rides a SEPARATE topic from
commands because the command channel is ordered and blocking for a run's whole duration, so a cancel
delivered there would be read only after the run it cancels had finished.

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
