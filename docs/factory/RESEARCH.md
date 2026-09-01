# Software Factory — Prior Art

What this design is built on, what was taken from each source, and what was deliberately rejected.
Research conducted 2026-09-01. Nothing here was copied; the sources were read as prior art, in the
same posture as `../RESEARCH.md` §4 records for PR-Agent.

---

## 1. Warren — the closest running system

[jayminwest/warren](https://github.com/jayminwest/warren) · [live public instance](https://app.warren.run)
· MIT · v0.19.0 · running its own development on GKE.

**Its one-line thesis:** *"Coding agents are tools. Warren turns them into infrastructure."* Warren is
not an agent. It is a control plane that turns an agent invocation into a **managed workload**:

```
project registry → dispatch → sandboxed run → event stream → steer/pause → reap → push branch
```

**Measured operation, from its author's own published stats:** over 1,000 agent runs in seven weeks,
$1,348 total, **912 pull requests at ≈$1.48 per pull request**, a 91% success rate, and **86% of runs
with no human-typed prompt** — driven by plan decomposition and scheduled patrol agents. 13 projects
developed through it.

### What this design takes

| Taken | Where it lands here |
|---|---|
| The run kernel shape, and that its **guaranteed output is a pushed branch** | [PRD.md](./PRD.md) FR-F4 |
| **`finalize` separate from `destroy`** — salvage before teardown | [ARCHITECTURE.md](./ARCHITECTURE.md) §3.2 |
| A **failure-cause discriminator as a column**, not log spelunking | [AUTONOMY.md](./AUTONOMY.md) §8 |
| Capability flags read by the domain, never provider names | ADR-028, NFR-F7 |
| The tracker stays the source of truth; only run bookkeeping is stored | ADR-028, FR-F16 |
| Per-project image override, for repositories with their own toolchain | [EXECUTION-LAYER.md](./EXECUTION-LAYER.md) §4.3 |
| Telemetry: cost per merged PR, autonomy rate, issue→merge lead time, where runs die | [AUTONOMY.md](./AUTONOMY.md) §8 |
| Human takeover pauses automation — do not race a person | FR-F22 |
| Dispatch intent journalled before the request; ambiguity fails closed | FR-F10 |

Its published failure breakdown across 44 failed runs — **provider error 21, dropped commit 8**,
sandbox lost 5, evicted 3, finalize not posted 2 — is the direct evidence for both the retry ladder
and salvage-before-destroy.

### What is rejected, and why

- **Its extension "controller" pattern** (a separate service driving the kernel over HTTP). Warren
  chose it because its kernel is deliberately minimal. Code Spire's is not — it already has auth, an
  event store, a cost ledger and a UI. Adopting the controller shape here means rebuilding all four
  to gain coupling looseness nobody has asked for. See ADR-028.
- **Campaign controllers and multi-repository campaigns.** Correct direction, no payer yet.
- **The corpus-flywheel and learned router.** Its 17KB design record wants precisely the
  `trajectory × outcome × verdict` join — and lists it as *unscheduled*. Code Spire already stores the
  verdict half (`review_finding`, ADR-027). We collect; we do not route on it.

### Where this design is ahead

Warren's own records name three open gaps that are already closed here: no per-token cost ledger with
snapshotted rates, no spend caps that refuse before the call, and no operator identity model (its
public instance is read-only by decision). ADR-023 and ADR-025 solved the first two before the
factory existed.

## 2. Machinist — the minimal version

[owainlewis/machinist](https://github.com/owainlewis/machinist) · MIT · Go · early access.

*"The open source AI software factory for agentic coders."* Smaller and sharper than Warren, and its
four stated principles map almost one-to-one onto decisions here:

- **One controlled entrypoint** — workers expose *named commands*, never arbitrary shell text.
- **Bring your own harness** — any executable that accepts a prompt on standard input.
- **Keep authority local** — repositories, credentials and executor configuration stay on the worker.
- **Keep the human gate** — *"Machinist hands back a pull request. It does not decide what ships."*

Its accompanying walkthrough contributes the **label-driven pipeline**: a GitHub issue labelled
`factory:ready-for-spec` is picked up, refined by a triage workflow into a specification, then
labelled `factory:ready-to-implement` for the build workflow. That is the direct ancestor of
[AUTONOMY.md](./AUTONOMY.md), with one addition — machinist's labels are a *trigger*; here they also
select an autonomy profile, which is why the ceiling and the labeller allowlist (ADR-032) had to be
invented.

It also states the honest limitation this design inherits: *"Scripts are intentionally opaque… A
killed script restarts from the beginning unless the script owns checkpointing."*

## 3. The meta-harness component model

*Meta-Harness | Designing Multi-Agent AI Systems*, The Carbon Layer
([video](https://www.youtube.com/watch?v=HRUBDPdvaHU)).

The cleanest decomposition found anywhere in the research. Its central claim is the one this design
adopts wholesale:

> **Coordination lives outside the model.** A supervisor agent can make judgment calls, but it should
> not be responsible for remembering that the security reviewer timed out, that the revision limit is
> one, and that the final candidate must never be merged automatically. Those rules are easier to
> inspect and test when they live outside the model.

Its eight components and where each lands:

| Component | Here |
|---|---|
| Agent package registry | harness registry + autonomy profiles |
| Harness adapter layer, with **capability descriptors** | `HarnessAdapter` / `HarnessCapabilities` |
| Role and policy binder — **authority narrows, never widens** | ADR-032, ADR-035 |
| Workflow engine — versioned blueprints, expressed as code not YAML | the eight phases |
| Worker lifecycle manager — bounds that do not belong in prompts | `RunSaga`, caps |
| Execution environment manager | `RunRuntime` |
| State and artefact store, with **lineage** | two event tiers + `factory_run` |
| Event and control plane | `cs.run-events` + steer/cancel |

Its sharpest single line, and the reason caps are not prompt instructions: *"Asking the model to stop
after 10 minutes is not a timeout implementation."*

## 4. The Ralph loop and back-pressure

Geoffrey Huntley's technique, widely reported
([LinearB](https://linearb.io/blog/ralph-loop-agentic-engineering-geoffrey-huntley),
[VentureBeat](https://venturebeat.com/technology/how-ralph-wiggum-went-from-the-simpsons-to-the-biggest-name-in-ai-right-now)),
and described independently in a Sber GigaChain conference talk
([video](https://www.youtube.com/watch?v=aBcW01Qbuws)).

Two ideas are load-bearing here:

1. **Restart rather than compact.** A model is at its best in roughly the first third of its context
   window; past that, quality degrades and summarisation degrades it further. Wrapping a harness in a
   loop that restarts it with fresh context, prompted to move in small steps, keeps every iteration
   inside the good zone. This is the argument for **vertical slices** and for one run per plan step
   rather than one run per work item.
2. **Back-pressure.** An unbounded agent invents ever more work. The counterweight is external and
   deterministic: tests, linters, gates, pre-commit hooks, property and snapshot tests. That is
   `verify` (FR-F20), and it is why an unverifiable step must report **unverified** rather than pass.

The same talk contributes **mode collapse** — repeated generations converge on the same few answers —
and its mitigation, wiping context between generations. Recorded, not built.

## 5. Harness minimalism

[pi](https://github.com/earendil-works/pi) · MIT · and its
[walkthrough](https://www.youtube.com/watch?v=0sI0MbCt4f4).

pi's thesis: a coding agent needs exactly **four tools — read, write, edit, bash** — and a system
prompt under a thousand tokens. No MCP by default, no sub-agents, no plan mode, no permission
prompts. Extensibility comes from files in the workspace: skills, extensions and packages.

The Sber talk reaches the same conclusion from a survey of open-source harnesses: read, search, edit
and bash are present in **all** of them; the rest is domain-specific. It also measures something this
design relies on — **harness quality alone moves benchmark results by 20–30 percentage points on the
same model** — which is the argument for driving a mature harness rather than writing a tool loop
(ADR-029).

pi's runtime modes are what make it the second arm: `-p` for one-shot, `--mode json` for NDJSON
events, and `--mode rpc` for a bidirectional JSONL session — the only mechanism among the candidates
that can carry **steer**.

## 6. Operating a fleet

*How I manage 250+ AI Agents* ([video](https://www.youtube.com/watch?v=BLMkrw1W6No)).

A first-hand account of running agents as a workforce. Four transferable ideas:

- **Skills are SOPs.** A workflow written once as an instruction manual, then run on a schedule.
- **A ledger as shared memory.** Each scheduled run first reads what previous runs did, so work is
  not repeated. The direct ancestor of `work_item` as bookkeeping rather than a mirror.
- **Risk-scored pull requests.** A separate automated pass scores each pull request on risk and
  decides which may merge autonomously and which require explicit sign-off — from a second account
  reachable only on a phone, so agents cannot approve their own work. That out-of-band approval is
  what `land: approve` means here.
- **Behaviour validation contracts.** A plain-text description of what a system is supposed to do,
  assigned to a monitoring agent that checks it on a cadence. Recorded as a future direction, not
  built.

Also the honest constraint: *"the limiting factor becomes you as a person — how many decisions you can
make per day."* Which is the same conclusion as the next source, from the other end.

## 7. The bottleneck argument

Dex Horthy (HumanLayer), *Ex-NASA dev reveals his Agentic Engineering Workflow*
([video](https://www.youtube.com/watch?v=xgkjtF89-44)).

The most useful strategic framing found. Building is now minutes; **reviewing is still hours**, so
code review is the bottleneck and adding coding agents does not help — it deepens the queue in front
of it. The reference is Goldratt's *The Goal*: there are inefficiencies that are not bottlenecks, and
optimising them delivers nothing.

Two mechanisms taken:

1. **The design ladder** — product intent, then system architecture, then *program design* (call
   stacks, file placement, method signatures), then implementation. Decisions made before code are
   cheap and made with the model at full capability; the same decisions after two thousand lines are
   expensive and already biased. This is `spec` and `plan`.
2. **Vertical slices** — models build horizontally and leave nothing testable until the end. This is
   why plan steps are sized to end at something runnable.

Also his warning against the "lite software factory" where nobody reads the code: it works until an
agent hits a bug it cannot solve, and then the team must re-enter a codebase they stopped
understanding months ago. Which is why `review` is a phase and not an option.

## 8. Harness interoperability

Two Apache-2.0 projects converged on the same problem while this design was being written:

- **[HarnessRouter](https://github.com/HarnessRouter/harnessrouter)** — one HTTP surface over Codex,
  Claude Code, Hermes, pi, opencode, Qwen and Cline, implementing the **Unified Harness Protocol**
  ([unifiedharnessprotocol.org](https://unifiedharnessprotocol.org)): a versioned specification, an
  OpenAPI schema and a conformance suite.
- **[Omnigent](https://github.com/omnigent-ai/omnigent)** — a meta-harness that orchestrates Claude
  Code, Codex, Cursor and pi behind one runner with policy and sandboxing.

**Not adopted as the internal contract.** UHP is an HTTP hop and a hosted-product-shaped API, where
`spire-run-worker` already owns the process directly. But `HarnessAdapter` is shaped so a
`UhpHarnessAdapter` is one more arm, for an operator who already runs one.

HarnessRouter also supplies the licensing pattern this design copies verbatim: *"The agent CLIs are
**not** redistributed here; they are installed on first run under their own licenses."*

## 9. The commercial landscape

Devin (most autonomous background agent, its own sandbox), Cursor Background Agents (isolated VM per
run, work on an `agent/` branch), Claude Code Remote, Codex Cloud, Jules, and Factory Droids
(compliance-oriented). Surveyed via
[TECHSY](https://techsy.io/en/blog/background-coding-agents-compared),
[MarkTechPost](https://www.marktechpost.com/2026/06/10/ai-coding-agents-development-platforms-2026/)
and [Blaxel](https://blaxel.ai/blog/best-ai-agents).

All are **hosted**, and all take custody of the repository, the credentials and the run history. The
differentiator for this design is unchanged from ADR-001: self-hosted, provider-neutral, one bot for
a whole workspace, no per-seat licensing — plus one thing none of them have, a **reviewer that
already knows the codebase reviewing the factory's own output**.

## 10. Sandbox technology

Surveyed via [amux](https://amux.io/guides/ai-agent-sandboxing/),
[Northflank](https://northflank.com/blog/daytona-vs-e2b-ai-code-execution-sandboxes) and
[Developers Digest](https://www.developersdigest.tech/blog/ai-agent-code-sandbox-comparison-2026).

Firecracker microVMs give a dedicated kernel per sandbox (E2B, Vercel, Fly.io, ~150ms cold start);
gVisor intercepts syscalls in user space (Modal); Daytona uses containers for a ~90ms cold start but
closed its source in June 2026, so it can no longer be self-hosted.

**None is adopted as a dependency.** A self-hosted product cannot require a hosted sandbox vendor,
and cold-start latency is irrelevant for a workload measured in tens of minutes. The relevant finding
is the isolation ladder itself, which informs the `RunRuntime` capability flags and the honest
statement in [EXECUTION-LAYER.md](./EXECUTION-LAYER.md) §5 that a Docker socket is root-equivalent
on the host.

## 11. Sources

Repositories: [warren](https://github.com/jayminwest/warren) ·
[machinist](https://github.com/owainlewis/machinist) · [pi](https://github.com/earendil-works/pi) ·
[codex](https://github.com/openai/codex) · [opencode](https://github.com/anomalyco/opencode) ·
[OpenHands](https://github.com/OpenHands/OpenHands) · [goose](https://github.com/aaif-goose/goose) ·
[waku-agent](https://github.com/ShenSeanChen/waku-agent) ·
[harnessrouter](https://github.com/HarnessRouter/harnessrouter) ·
[omnigent](https://github.com/omnigent-ai/omnigent)

Talks: [I ran a software factory for a month](https://www.youtube.com/watch?v=a9kqbAgfzQo) ·
[Meta-Harness](https://www.youtube.com/watch?v=HRUBDPdvaHU) ·
[Harness: новый подход к созданию AI-агентов](https://www.youtube.com/watch?v=aBcW01Qbuws) ·
[Pi Minimal Coding Agent Harness](https://www.youtube.com/watch?v=0sI0MbCt4f4) ·
[I Built an Agentic Software Factory](https://www.youtube.com/watch?v=AbpyqAfxZ8c) ·
[Ex-NASA dev's Agentic Engineering Workflow](https://www.youtube.com/watch?v=xgkjtF89-44) ·
[How I manage 250+ AI Agents](https://www.youtube.com/watch?v=BLMkrw1W6No)

Vendor terms are quoted with retrieval dates in [EXECUTION-LAYER.md](./EXECUTION-LAYER.md) §2.
