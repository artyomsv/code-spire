# Software Factory — Run Topology

How a run is actually executed: what runs where, what data crosses which boundary, and what
guarantees each boundary provides. Decided 2026-09-01 after a measured spike against a real Codex
CLI; the evidence is in §1 and the decision is [ADR-039](../DECISIONS.md).

This document supersedes the bind-mounted-workspace sketch in the first draft of
[ARCHITECTURE.md](./ARCHITECTURE.md), which was wrong for the reasons in §2.

---

## 1. What the spike measured (2026-09-01)

Every line below is an observed result, not a documented claim. Environment: Docker 29.6.2, WSL2
kernel 6.18.33.2, `codex-cli 0.152.0`, image `node:22-bookworm-slim` + `ca-certificates git`.

### 1.1 Codex runs and works inside a container — confirmed

| Check | Result |
|---|---|
| Codex installs and runs in a container | ✅ `codex-cli 0.152.0` |
| Runs as **non-root** | ✅ `uid=1001(agent)` |
| TLS to the model API from inside | ✅ `HTTP 401` from `api.openai.com` — transport fine, key rejected |
| Reaches auth over its own transport | ✅ `401 Unauthorized … url: wss://…` — a real connection, rejected on the credential only |
| Answers a prompt (subscription auth) | ✅ `HELLO WORLD FROM A CONTAINER`, 3,871 tokens |
| Performs an agentic task end to end | ✅ see below |

The agentic run, verified by inspecting the repository rather than trusting the agent's own report:

```
prompt   "fix the spelling Helo to Hello and add type hints, then commit"

greet.py def greet(name: str) -> str:
             return "Hello " + name
commits  00440b1 fix greeting typo
changed  M  greet.py
author   spire-bot <bot@spire>
exit     0                                    (5,847 tokens)
```

**The commit author is the identity the workspace was configured with**, not one the agent chose.
Authorship is a property of the environment we build, which is what ADR-038 needs to be true.

### 1.2 Codex's own sandbox does NOT work in a container — confirmed

Codex ships **`bwrap` (bubblewrap)** in its vendor directory
(`…/codex-linux-x64/vendor/x86_64-unknown-linux-musl/codex-resources/bwrap`). Its Linux sandbox is
bubblewrap-based.

| Container configuration | `bwrap --unshare-all` |
|---|---|
| **default Docker** | ❌ `No permissions to create a new namespace` |
| `--security-opt seccomp=unconfined` | ✅ works |
| `seccomp=unconfined` + `--cap-add SYS_ADMIN` | ✅ works (SYS_ADMIN not required) |

An earlier probe measured **Landlock** (`landlock_create_ruleset` → ABI 7, available under default
seccomp) and concluded the inner sandbox would work. **That probe tested the wrong primitive.**
Landlock's availability is irrelevant because Codex does not use it as its boundary here.

**And Codex does not fail fast when its sandbox cannot work.** All three configurations started
normally and reached the network. The sandbox applies to *model-generated shell commands*, so a
broken inner sandbox surfaces mid-run — or not at all — rather than at launch.

### 1.3 Three CLI facts that contradict the documentation

- **`--ask-for-approval` does not exist** in 0.152.0. The real flags are `-s/--sandbox`
  (`read-only|workspace-write|danger-full-access`), `--approve-for-me`,
  `--dangerously-bypass-approvals-and-sandbox`, `-C/--cd`, `--add-dir`, `--json`,
  `--skip-git-repo-check`, `-o/--output-last-message`, `--output-schema`, `--ephemeral`,
  `--ignore-user-config`, `--oss`, `--local-provider`.
- **The NDJSON shape** is `{"type":"item.completed","item":{…}}` and `{"type":"error","message":…}` —
  not the `agent_reasoning` / `exec_command_begin` names taken from documentation.
- **`ca-certificates` is mandatory** in the agent image. Without it every TLS call fails
  `invalid peer certificate: UnknownIssuer`, and Codex retries silently rather than saying why.

### 1.4 The image needs the repository's toolchain

The agent's own words on the verified run: *"runtime testing wasn't possible because neither `python`
nor `python3` is installed."* An agent that cannot run the repository's tests has no back-pressure,
which is the counterweight the whole design rests on. This confirms the two-dimensional image
problem in [EXECUTION-LAYER §4.3](./EXECUTION-LAYER.md).

### 1.5 The decision that follows

**Keep Docker's default seccomp profile. Run Codex with `--sandbox danger-full-access`. The container
is the boundary.**

Enabling Codex's own sandbox costs the container's seccomp profile — weakening the **outer** boundary
to gain an **inner** one, which is a bad trade when the container already confines writes by mounting
only the workspace.

Leaving `--sandbox workspace-write` set where `bwrap` cannot run is the **worst** option, because
Codex does not fail at startup: the operator would believe in two boundaries, have one, and find out
only when a command ran.

`danger-full-access` means *Codex adds no boundary of its own* — not *there is no boundary*. The
container must therefore be genuinely restrictive: non-root, only the workspace writable, no Docker
socket, dropped capabilities.

---

## 2. Why the bind-mounted workspace was wrong

The first draft had the **worker** clone into its own filesystem and bind-mount it into the agent
container. That is wrong, and worse than it first appears.

| Consequence | Severity |
|---|---|
| The worker becomes **stateful** | design smell |
| Only the replica that started a run can finish it | **recovery bug** |
| A node reschedule mid-run loses the workspace entirely | **data loss** |
| Kubernetes needs RWX shared storage | cost, latency, a new failure domain |

The orphan watchdog specified in [ARCHITECTURE §7](./ARCHITECTURE.md) **cannot salvage a run whose
workspace lived on a dead node**. The design contained a recovery mechanism that its own storage
model defeated.

---

## 3. The topology

**The run environment clones itself, works, and is destroyed. The branch on the forge is the durable
state.**

```
┌─────────────────────────── ONE POD (one run) ────────────────────────────┐
│                                                                          │
│  [init]  fetch          git clone <branch> → /workspace   [READ token]   │
│                                                                          │
│  [main] agent                          [sidecar] publish                 │
│  ├ /workspace  rw                      ├ /publish   rw  (its own clone)  │
│  ├ /handoff    rw                      ├ /handoff   ro                   │
│  └ model credential                    └ WRITE token (machine account)   │
│         │                                       ▲                        │
│         └────────── bundle files ───────────────┘                        │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
        │ stdout: NDJSON events          │ stdout: {"pushed":…,"blocked":…}
        ▼                                ▼
                        spire-run-worker (stateless)
                                    │
                                    ▼
                              cs.run-events / cs.run-results
```

Note what each container **cannot** reach. The agent holds no write token. The publisher cannot see
`/workspace` — it is not mounted. They meet in exactly one place, `/handoff`, and only as files.

**The publisher is a sidecar, not a later step.** Continuous checkpointing (§5) requires it to run
*alongside* the agent. Kubernetes ≥ 1.29 expresses this natively: an initContainer with
`restartPolicy: Always` starts first, runs beside the main container, and terminates when the main
container exits. The agent is the **main** container; the publisher is the **sidecar** — the inverse
of how it reads on the page.

---

## 4. The handoff protocol

### 4.1 Agent side, after every commit

```bash
git bundle create /handoff/tmp  <base>..HEAD
mv /handoff/tmp /handoff/<n>.bundle
```

Two deliberate choices:

- **The `mv` is atomic**, so the publisher never observes a half-written bundle.
- **Always bundled from `base`**, never incrementally. It stays kilobytes, it is idempotent — the
  publisher may read it twice with no consequence — and it removes an entire class of
  "which range did I already ship" bookkeeping.

### 4.2 Publisher side

Working **only** inside its own clean clone, which it made from the forge at start:

```bash
git fetch /handoff/<n>.bundle                  # objects and refs only
git diff --name-status -M <base> FETCH_HEAD    # the gate's input
# gate → refuse, or:
git push origin FETCH_HEAD:refs/heads/<branch>
```

### 4.3 Why this is safe, one line each

| Property | Why it holds |
|---|---|
| Publisher cannot read `/workspace` | not mounted — no hooks, no `.git/config`, nothing of the agent's |
| A bundle carries **objects and refs only** | there is nothing in it to execute; a bundle has no config and no hooks |
| Publisher **never checks out a working tree** | it fetches, diffs trees, pushes — agent data never becomes files on disk |
| `/handoff` is **read-only** to the publisher | it writes nothing to any shared volume |
| Agent holds **no write token** | it physically cannot push, gate or no gate |

The escape this closes is real and well-trodden: git **executes scripts it finds inside a repository**
— `.git/hooks/*`, and config keys such as `core.fsmonitor`, `diff.external`, `filter.*.clean` and
`uploadpack.packObjectsHook`. Anything running `git` with a token inside the agent's folder can be
made to run the agent's code. Defending that by enumerating dangerous keys is a blocklist, and git
has been adding entries to that list for a decade.

> **The rule, stated once:** never run git-with-a-token inside a directory the agent could write to.
> It is not about which pod. It is about which **directory**.

---

## 5. Continuous checkpointing

**The run does not accumulate hours of unprotected work.**

A single handoff at the end means a pod lost at hour three loses everything — which is precisely the
second most common failure in the field data this design learned from (*dropped commit*, 8 of 44).

So the agent commits as it goes and every commit is gated and pushed within seconds:

```
agent commits  →  bundle appears  →  publisher gates  →  push  →  the forge holds it
```

Two layers make the commits happen, because relying on a model to remember is not a mechanism:

1. **The prompt** instructs the agent to commit after each logical step.
2. **An autosave loop in the agent container** commits anything dirty every N minutes. This is safe
   precisely because it runs in the container that holds **no token**.

### What this buys

- **Crash loss is minutes, not hours.** A new pod clones the branch and continues.
- **The gate becomes fail-fast.** The first commit touching a protected path trips it at minute four
  instead of minute 180 — you stop early and stop cheap.
- **Progress is observable.** The branch moves while the run is alive; no commits for a long stretch
  is a signal. A review can begin before the run ends.

### When the gate trips mid-run

**Terminate the run.** The branch keeps the safe prefix, and the operator sees exactly which commit
tripped it. The alternative — keep running but stop pushing — silently diverges the pod from the
branch, and everything after that point is lost anyway.

### Resuming

A resumed run clones the **branch**, not the base commit, and its prompt says so. What it needs
beyond the code is the *why*, which the code does not carry — that is the step summary (FR-F31).

---

## 6. The gate

### 6.1 How a CI change is identified

One git command and a glob match. Real output, from a repository where an agent modified one workflow,
added another, renamed a third **into** a protected directory, and did some ordinary work:

```
M     .github/workflows/ci.yml
R100  scripts/deploy.yml    .github/workflows/x.yml
A     .gitlab-ci.yml
M     src/Foo.java
```

Three details decide whether this works:

- **`-M` (rename detection).** Without it that third line is an unrelated add plus delete, and a file
  walking into a protected directory is easy to miss. Only the *transition* between two commits shows
  it — which is why this is a diff problem and not a "look at the files" problem.
- **Deletions count.** Removing a CI file changes what CI does as much as editing one.
- **Case-insensitive for the CI floor.** `.GitHub/workflows/` is a different path to git and the same
  file to Windows and macOS, and the forge will run it.

### 6.2 Why the guard cannot be a git hook

| Problem | Detail |
|---|---|
| The agent can delete it | `.git/hooks/pre-push` is a file in the directory the agent writes to |
| One flag skips it | `git push --no-verify` |
| Hooks are not cloned | someone must install it — into the untrusted directory |
| Wrong side of the boundary | a client-side hook asks the pusher to check itself |

A hook may be a convenience. It can never be the control.

### 6.3 The legitimate server-side version

Forge-enforced push rules are the strongest possible place for this check, because nothing in the pod
can touch them: **GitHub push rulesets** (block pushes by file path), **GitLab push rules** (Premium),
and real `pre-receive` hooks on self-hosted GitLab, Bitbucket and Gitea.

They cannot be the primary control — they are the customer's repository settings, they differ per
forge, and some are paywalled — but they are the right **second layer** and the operator
documentation should recommend them.

### 6.4 Why the location of the push is NOT the control

Stated plainly because an earlier draft of this design confused the two:

- **What protects the repository is the gate.** Check the changed paths, refuse if any is protected.
  This works wherever the push happens.
- **The location matters for exactly one thing:** whether the agent can tamper with the gate or reach
  the token. And the relevant unit is the **directory**, not the pod.

Moving the push from one container to another solves nothing by itself.

---

## 7. What leaves the pod, and how

**Nothing is extracted.** Every output leaves as a log stream, which Docker and Kubernetes both
provide natively — no `exec`, no file copy, no shared storage between pod and worker.

| # | From → To | What | Mechanism |
|---|---|---|---|
| 1 | worker → pod | RunSpec: image, argv, branch, base commit, credentials | pod/container spec |
| 2 | forge → `/workspace` | the repository | `git clone` (read token) |
| 3 | agent → `/handoff` | bundles | files on a shared volume |
| 4 | publisher → forge | the push | `git push` (write token) |
| 5 | agent → worker | NDJSON run events | container stdout |
| 6 | publisher → worker | `{"pushed":…,"changed":[…],"blocked":[…]}` | container stdout |

This is what keeps the worker **stateless**: it holds no run state and no filesystem, so any replica
can observe or salvage any run.

---

## 8. Credentials

Two tokens, different scopes, different containers — **the design. Not what ships.**

> **Today one machine-account secret fills both slots.** `Credentials.scm` packs the
> same secret as the clone credential and the push credential, so the init container holds a token
> that can write. The isolation that DOES hold is the one the agent is on the other side of: the
> agent gets no git credential at all, JGit persists none under the workspace, and the remote is
> removed after the clone — so nothing the model can influence ever sees either. What is not yet
> true is the second line of defence, a clone token that could not push even if it leaked.
>
> Closing it needs a forge-specific read scope (a GitHub fine-grained PAT with `contents:read`,
> a GitLab `read_repository` token) packed as a second envelope. Tracked in
> `docs/UNVERIFIED.md` §E; `ROADMAP.md` already records
> `separatePushCredential` as false everywhere.

| Credential | Container | Scope | Notes |
|---|---|---|---|
| clone token | `fetch` init | read-only **by design; today the write token** | the AGENT never holds anything that can write; the init container currently does |
| model credential | `agent` | model API only | subscription `auth.json` or API key (ADR-031) |
| push token | `publish` | write, **machine account** (ADR-038) | never the review bot's |

The residual risk, stated rather than hidden: **a write credential lives in the run pod.** For a
self-hosted deployment on a cluster the operator controls, that is an accepted trade for a topology
with no shared storage and no stateful worker. What it buys back is that the credential is
unreachable *through git* — the realistic path — because the publisher never touches agent-controlled
directories.

---

## 9. Hard requirements this creates

1. **Cap the bundle size.** An agent can write an object bomb; an unbounded read is a
   denial-of-service on the publisher.
2. **The publisher never checks out a working tree.** Fetch, diff, push — object-level only.
3. **`/handoff` is read-only to the publisher**, and `/publish` is not mounted into the agent.
4. **`ca-certificates` in every agent image**, or every TLS call fails silently-ish.
5. **Kubernetes ≥ 1.29** for native sidecar termination. Below that, the publisher needs an explicit
   sentinel file to know the agent finished.
6. **The agent image carries the repository's toolchain**, or `verify` cannot run and back-pressure
   does not exist.
7. **Give the worker's container runtime a disk the fleet can afford to lose**, because on the
   Docker arm the unit's shared volumes are not bounded and cannot portably be.

   `RunUnitSpec.diskBytes` is enforced — memory, CPU, process count and now disk are all declared,
   and "unlimited is not a limit" refuses a spec without one. What differs is how much of it an arm
   can spend. A size-bounded **tmpfs** is the only enforcement that travels (a write past `size=`
   gets `ENOSPC`, identically on Docker Desktop for Windows and macOS, native Linux and rootless
   Docker), and on **Kubernetes** it covers the whole unit: `emptyDir` is a *pod* volume, so it
   survives an init container exiting, and `medium: Memory` with `sizeLimit` bounds the shared
   workspace as well as `/tmp`.

   On **Docker** it covers `/tmp` only. Two things were measured rather than assumed:
   `--storage-opt size=` needs xfs with `pquota` and so fails at container creation on Docker
   Desktop (overlay2 on ext4); and a tmpfs-backed local volume is dropped when the last container
   using it stops, so a tmpfs `/workspace` would wipe the clone between `init` exiting and the agent
   starting — §3's ordering makes those sequential. A broken run in place of an unbounded one.

   So until a keeper container or an overlapped init lands
   (`techdebt/spire-runtime-docker/2-3-…`), one run can still fill the daemon's disk and take every
   concurrent run with it. Run that daemon on a dedicated disk, or on an xfs `pquota` root so
   `--storage-opt size=` becomes available. **This is a deployment property this project cannot
   enforce in code**, which is what this section is for.

---

## 10. Token usage is telemetry — except where it now gates spend

> **Corrected 2026-09-02 (M1 Task 4).** This section was written when nothing read a run's usage,
> and its title said so. A run now writes to `llm_charge`, and `SpendWindow` reads that ledger with
> no subject filter — so the number below IS an input to a refusal decision, for runs and for
> reviews alike. Everything the section says about *why* usage is worth having still holds; what
> changed is that it is no longer only telemetry, and the trust question that raises is stated at
> the end of this section rather than left implied.

An earlier draft called incremental usage reporting an open question that blocked M1, on the grounds
that a killed run's spend would go unrecorded. **That framing is wrong for the mode this deployment
actually runs in.**

Codex is used on a **subscription**. There is no per-token bill, so a run that dies before reporting
its usage has not lost anyone money — the money was a flat fee already paid. `pricing_mode` is
`UNMETERED` (ADR-031), and ADR-023's rule still applies for a smaller reason: a missing count is
recorded as **UNKNOWN**, never as zero, because zero would be a claim nobody measured.

### What usage is still worth having

| Purpose | Why it matters under a subscription |
|---|---|
| **Substitute headline metric** | "Cost per merged pull request" is meaningless when cost is a flat fee. **Tokens — or runs — per merged pull request** is the analogue, and it is the number that tells an operator whether the factory is getting better or just busier. |
| **Waste detection** | A run that burned 200k tokens and produced nothing is a strong signal even when it "cost nothing". |
| **Cross-arm comparison** | The moment a metered arm exists (pi on an API key), comparable numbers are needed to choose between them. |
| **Future API-key mode** | ADR-031 requires every arm to also work on an API key. In that mode this becomes accounting again — for that mode only. |

**What it is NOT needed for:** credential rotation. The pool rotates on the vendor's own
rate-limit/quota response, not on a token count, so the operationally important mechanism does not
depend on this at all.

### The cheap mechanism, which fits the design already

**Usage rides the checkpoint stream, exactly like commits.** The agent container writes the
last-known usage beside each bundle in `/handoff`, so a killed pod still leaves whatever was known at
the last checkpoint. For a harness that reports per turn, that captures nearly everything; for one
that reports only at the end, it captures nothing — and records UNKNOWN, honestly.

Costs nothing to build, and it means the answer to "does Codex report incrementally" changes how much
data we get rather than whether the mechanism exists.

### Measured — 2026-09-01, codex-cli 0.146.0

**Usage is reported on `turn.completed`, one report per turn.** That much is measured. So a run
killed mid-turn loses only the turn in flight, and the `/handoff` mechanism above captures the rest.

**Whether each report is CUMULATIVE or an INCREMENT is not measured — it is inferred**, and the
distinction is flagged here rather than buried because the two readings differ by a factor of
roughly the turn count. Both captured runs contained exactly one turn, and a single-turn
observation cannot tell the two apart: it takes counts growing across two turns of one real run.
`CodexAdapter.usage()` takes the **last** report, which is correct under the cumulative reading and
under-reports a multi-turn run under the other.

Rather than leave the assumption unguarded, the adapter **falsifies it where it can**: cumulative
totals are non-decreasing, so a report smaller than one before it disproves the reading, and the run
is then recorded as an unreconciled `TOTAL` instead of silently taking whichever number the
assumption selects. That converts a wrong guess into a visible degradation. It does not convert it
into a right answer — a multi-turn run is still the measurement needed.

The real event vocabulary, captured from two live runs rather than read from documentation:

| Envelope `type` | Nested `item.type` | Carries |
|---|---|---|
| `thread.started` | — | `thread_id` |
| `turn.started` | — | nothing |
| `item.started` | `command_execution` | `command`, `aggregated_output`, `exit_code` (null while running), `status` |
| `item.completed` | `command_execution` | same, with a real `exit_code` |
| `item.completed` | `agent_message` | `text` |
| `turn.completed` | — | `usage{input_tokens, cached_input_tokens, cache_write_input_tokens, output_tokens, reasoning_output_tokens}` |
| `error` | — | `message` (documented envelope; not observed in these runs) |

**Three things this overturned in the M0 plan**, each of which would have shipped as a silent defect:

- **The planned usage envelope does not exist.** Task 2 specified `{"type":"token_count",…}`.
  `parse` written to that would have extracted usage from *no run at all* — the ledger recording
  UNKNOWN forever while the feature looked installed.
- **`command_execution` arrives twice**, started and completed. Mapping both to one event kind
  doubles every shell command on an operator's timeline.
- **Cached tokens are a subset, and the numbers are large.** One measured turn reported
  `input_tokens: 14064` of which `cached_input_tokens: 9984`. Recording both raw gives 24048 for a
  call that used 14064 — a 71% overstatement, worst on the runs that were cheapest.
  `TokenUsageMapper.openAi` in spire-llm already subtracts for this reason; `CodexAdapter` follows it.

**Three limits left open, deliberately.** The cumulative-versus-incremental question above is the
first. Second, Codex reports **no total**, so the independent cross-check `TokenUsageMapper`
performs against `totalTokenCount()` is unavailable here — a mis-partition cannot be caught by
arithmetic, only by a contradiction between the vendor's own fields. Third,
`cache_write_input_tokens` is treated as *additional* to input rather than a subset of it, matching
what the name describes and how Anthropic reports the same concept; every run observed so far
reported zero, so measurement has not ruled out the alternative — and the contradiction gate cannot
catch a wrong choice here, because `cacheWrite` is compared against nothing. A run with a non-zero
cache write would settle it.

**What would settle two of the three: one deliberate multi-turn run**, captured with `--json`, its
`turn.completed` lines compared against each other. That is a run to make on purpose, not a thing to
wait for. Until it exists, neither the cumulative reading nor the cache-write reading may be cited
as measured — and this section is the record of which is which.

**Version note.** The plan states its flag set was verified against codex-cli **0.152.0**; the
binary available for this measurement was **0.146.0**. Every flag the adapter uses was re-checked
against `codex exec --help` on 0.146.0 and is present. `--ask-for-approval` is absent on both.

---


### Where this number comes from, and why that matters now

**The agent reports its own usage.** The harness adapter parses it from the agent container's
stdout, and the agent runs shell at full access inside that container by design — so the count is
self-reported by the least trusted component in the system. That was harmless while it was
telemetry. It is not harmless now that it moves a cap.

Two directions, both reachable by a prompt-injected or simply buggy agent writing a plausible usage
line to its own stdout:

- **Over-report.** On a metered model a fabricated multi-billion-token count prices high enough for
  `SpendGate` to answer `CAP_REACHED` for every paid call until the rolling window drains — which
  takes out the reviewer as well as the factory. One run, denying service to the whole deployment.
- **Under-report.** A smaller later figure trips the harness's own shrink detection, degrading the
  run to an `UNKNOWN` line whose cost is NULL and therefore skipped by `SUM`. The run's real spend
  leaves the money axis; only the call count still sees it.

**`SPIRE_RUN_MAX_REPORTED_TOKENS` bounds the first of those.** A run reporting more than the
ceiling has its usage recorded as `UNKNOWN` rather than priced, so a fabricated number cannot buy
a deployment-wide refusal. Unset means unlimited, matching every other cap in ADR-025 — this is a
hardening control an operator opts into with a number only they can know, not a correctness
control with a default the code could invent.

The under-report direction is **not** closed, and cannot be from inside: nothing in the run unit
can distinguish an honest small report from a dishonest one. Only reconciliation against the
provider's own billing or usage API can, and that is tracked as debt rather than claimed here. The
call-count axis is the partial mitigation that already exists — it counts a run whatever the run
says it spent, which is exactly why ADR-025 insisted on having both axes.

## 11. Appendix — the verified agent image

This is the image the §1 results were produced with. It is the starting point for
`spire-agent-codex`, not a sketch.

```dockerfile
FROM node:22-bookworm-slim

# ca-certificates is NOT optional. Without it every TLS call fails
# "invalid peer certificate: UnknownIssuer", and Codex retries silently rather than saying why.
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
        ca-certificates git openssh-client \
    && rm -rf /var/lib/apt/lists/*

RUN npm i -g @openai/codex && npm cache clean --force

RUN useradd -m -u 1001 -s /bin/bash agent
USER 1001:1001
ENV HOME=/home/agent SPIRE_WORKSPACE=/workspace
WORKDIR /workspace
```

The shipped image is `deploy/agent/codex/Dockerfile`, which is this appendix plus the entrypoint.
**`deploy/agent/spire-agent-entrypoint.sh` is the image contract in executable form**: it runs the
harness argv it is given with the prompt on stdin (from `$SPIRE_PROMPT`, written to a file outside
the working tree and unset before the harness starts, so an autosave can never commit it), commits
anything dirty and bundles `$SPIRE_BASE_COMMIT..HEAD` onto `/handoff` every `$SPIRE_AUTOSAVE_SECONDS`
(§5) and once more when the harness exits (§4.1), and writes `DONE` last. Bundles are written to a
dotted temp name and renamed, so a half-written file never matches `*.bundle`. The mount points are
created in the image owned by the agent user, because a fresh named volume inherits the ownership of
the directory it is mounted over — without that the volume is root's and the agent cannot write its
own workspace. The **repository's own toolchain** (§1.4) remains the operator's image to build `FROM`
this one.

Subscription auth is supplied by copying the operator's `auth.json` into `$HOME/.codex/` at start —
mounted read-only from the registry and copied, because Codex refreshes the token and needs the file
writable.

## 12. What this changes elsewhere

| Document | Change |
|---|---|
| [ARCHITECTURE.md](./ARCHITECTURE.md) §7 | the bind-mount sketch is superseded by this document |
| [MODULES.md](./MODULES.md) | `spire-workspace` runs in the **publisher image**, not the worker; new `spire-publisher` |
| [EXECUTION-LAYER.md](./EXECUTION-LAYER.md) §5.1 | the Landlock probe is replaced by the measured bubblewrap result |
| [ROADMAP.md](./ROADMAP.md) M0 | Tasks 3, 6 and 8 of the M0 plan are reworked around this topology |
| `SECURITY.md` | the git-config execution class, and the two-token split |
