# Architecture — event-driven, plugin-first core

> Status: design sketch. Companion to [EVENT-MODEL.md](EVENT-MODEL.md), which holds the
> concrete slices (events/commands/read models). This doc explains the *shape* and *why*.
>
> **Visual board:** open [diagrams/architecture.html](diagrams/architecture.html) in a browser — a
> swimlane-per-layer view of the deployable services, the Kafka/Redpanda bus, the shared-library SPI +
> adapters (single row), the data stores (their own lane), and the external systems, with roadmap pieces
> (the repository knowledge base, MinIO, OIDC, real webhooks) drawn dashed. Repo-rules context has since
> shipped. The board scrolls
> sideways.

## 1. The core idea

A pull-request review is a **pipeline of state changes**, each triggered by a fact that
happened. Model those facts as **events** on a log; let **policies** (sagas) react to events by
issuing the next **command**; let **deciders** turn commands into new events; let **views**
project events into read models. Everything between components is an asynchronous message.

That gives us two properties for free:

- **No synchronous processing.** No component calls another and waits. A stage finishes by
  *emitting an event*; the next stage *reacts* to it. The only synchronous edges are at the
  system boundary (an inbound webhook must return HTTP 200; an outbound API call must be made) —
  and those are isolated inside adapter plugins, never in the domain flow.
- **Plugin-first by construction.** A plugin is just a component that **subscribes to some
  events and emits others**. Adding a capability = deploying a new subscriber. The core does not
  know it exists. This is the structural answer to "add a capability with minimal core change."

## 2. Building blocks (Event Modeling → fmodel → Quarkus)

We use the [Event Modeling](https://eventmodeling.org/) vocabulary, formalize it with the
[Fraktalio fmodel](https://modeler.fraktalio.com/) `Decider / View / Saga` triad, and implement
it on Quarkus reactive messaging.

| Event Modeling block | Formalism (fmodel) | Code Spire implementation | Quarkus mechanism |
|---|---|---|---|
| **Command** (blue) | input to a Decider | intent record on a command channel | `@Incoming` command channel |
| **Event** (orange) | output of a Decider | immutable fact on the event log | `@Outgoing` → event store → fan-out |
| **Aggregate** (write) | **Decider** `decide(cmd,state)->events`, `evolve(state,evt)->state` | per-PR review state machine, event-sourced | bean; state rebuilt by replay |
| **Read Model** (green) | **View** `evolve(state,evt)->state` | projections for status / thread / rules | `@Incoming` event → upsert store |
| **Automation** (the "TODO list") | **Saga** `react(evt)->commands` | the reactive policies that move the pipeline | `@Incoming` event → `@Outgoing` command |
| **External input / translation** | boundary adapter | SCM webhook → event; command → SCM API call | ingress endpoint + adapter plugin |
| **UI / wireframe** | — | live dashboard, PR comments | WebSockets Next push |

### Decider — the write model

One decider owns the lifecycle of a **single PR review**: `ReviewLifecycle`.

```
decide(command, state) -> events        // pure: what should happen
evolve(state, event)   -> state         // pure: fold events into current state
```

It is **event-sourced**: to handle a command for PR `X`, we replay `X`'s events into `state`,
then `decide`. No shared mutable state, trivially testable (pure functions), naturally
concurrent (per-aggregate serialization by stream id). A second decider for a repository-index
lifecycle was once planned here and is **not** being built — ADR-026 keeps the knowledge base
review-time and repo-keyed state rather than an event-sourced aggregate.

### View — the read models

Views are pure folds of the event stream into query-optimized shapes, pushed live over
WebSockets:

- `ReviewStatusView` — per-PR progress (requested → diff-fetched → context-assembled →
  generated → posted → completed), for the dashboard.
- `ReviewThreadView` — the conversation state per inline thread, so follow-up replies have context.
- `RulesView` / `RepositoryProfileView` — the rules + (later) learned memory to apply to a repo.
- `MetricsView` (later) — per-author / per-repo analytics, projected from the same events.

### Saga — the automations (the glue that makes it flow)

Each saga is a tiny policy: **on event E, emit command C.** They are the choreography. They hold
no business logic beyond routing; all decisions live in deciders.

```
on PullRequestEventReceived   -> RequestReview
on ReviewRequested            -> FetchDiff
on DiffFetched                -> GatherContext
on ContextAssembled           -> GenerateReview
on ReviewGenerated            -> PostComments
on AuthorReplied              -> AnswerFollowUp
on PullRequestClosed          -> CancelReview        (see EVENT-MODEL S9)
```

Adding the repository knowledge base is *adding a plugin*, not editing this list's neighbours (see §5).

## 3. The flow, end to end (all asynchronous)

```
 Bitbucket ─(webhook HTTP)─► [SCM ingress adapter] ──emit──► PullRequestEventReceived
                                    (returns 202 immediately)         │
                                                                      ▼  (saga)
                                                              RequestReview ─► ReviewLifecycle ─► ReviewRequested
                                                                                                      │ (saga)
                                     ┌────────────────────────────────────────────────────────────────┘
                                     ▼
                                FetchDiff ─► [SCM DiffSource plugin] ─► DiffFetched
                                     │ (saga)
                                     ▼
                                GatherContext ──fan-out──► ContextRequested
                                     ┌───────────────┬───────────────┬───────────────┐
                                     ▼               ▼               ▼               ▼
                              [Jira plugin]   [Confluence plugin] [code plugin] [rules plugin]
                                     │ emit          │ emit          │ emit          │ emit
                                     └──ContextContributed (× N)──────────────────────┘
                                                     ▼  (aggregator view + completeness/timeout policy)
                                              ContextAssembled
                                                     │ (saga)
                                                     ▼
                                              GenerateReview ─► [LLM provider plugin] ─► ReviewGenerated
                                                     │ (saga)                                (fallback = saga)
                                                     ▼
                                              PostComments ─► [SCM CommentSink plugin] ─► CommentsPosted ─► ReviewCompleted
                                                     │
                                    (views update throughout) ──► WebSockets ──► live dashboard
```

Conversational loop (same machinery):

```
 Bitbucket comment ─(webhook)─► AuthorReplied ─(saga)─► AnswerFollowUp ─► [LLM plugin] ─► FollowUpPosted
```

**The single synchronous boundary** is the inbound webhook endpoint: it verifies the HMAC
signature, translates the payload into one event, hands it to the channel, and returns `202
Accepted` — it never runs the review inline. Everything after is messages.

## 4. Ports (SPI) — what a plugin implements

The core defines small, segregated ports (fixing PR-Agent's 50-method God-object). A plugin
implements one or more; it is a CDI bean discovered at boot. No registry edits, no core imports.

| Port | Contract | Example plugins |
|---|---|---|
| `ScmIngress` | translate an inbound webhook → domain event(s) | Bitbucket Cloud, Bitbucket DC, GitHub |
| `DiffSource` | fetch PR + produce canonical `FilePatch` | (same SCM adapters) |
| `CommentSink` | post inline + summary, **reply in thread**, read **PR author** | (same SCM adapters) — first-class, unlike PR-Agent |
| `ContextProvider` | on `ContextRequested`, emit a `ContextContributed` | Jira, Confluence, issues, rules, code (P3), memory |
| `LlmProvider` | handle `GenerateReview` → `ReviewGenerated` | Vertex, Anthropic, Azure OpenAI, Ollama (via LangChain4j) |
| `Capability` | a self-contained flow: declares its events, commands, prompts, config | review, describe, changelog, … |

Discovery: Quarkus CDI — `@All List<ContextProvider> providers;` gives the aggregator every
context plugin on the classpath. Drop a jar → new provider participates. Config selects *which*
LLM/SCM providers are active (no default; fail-fast if unset).

## 5. Adding a capability with zero core change — worked example (the code knowledge base)

To add repository-wide code context (P3, ADR-026):

1. Ship a `spire-context-code` module with a `CodeContextProvider implements ContextProvider`.
2. It **subscribes** to `ContextRequested`, resolves the symbols a diff touches against the changed
   file's own import graph, and **emits** `ContextContributed{source=CODE, items=[CODE_SNIPPET…]}`.

The aggregator already collects *all* `ContextContributed` events up to a completeness threshold or
timeout, so the new snippets flow into `ContextAssembled` → the prompt — **without editing the review
flow, the deciders, or any other plugin.** Contrast with PR-Agent, where the same feature means
forking every tool's `_prepare_prediction`.

**Two corrections to how this example used to read, both from ADR-026.** A third step once stood
here: *subscribe to SCM `PushReceived` and maintain an index via a `RepositoryIndexDecider`.* It is
**removed, not deferred.** A push carries no `reviewId`, so it would introduce the first
non-`reviewId` message class against the keying discipline CONTRACT §9 calls the important
invariant — and it would be *less* correct, because the index's only reader is a review and a
review-time refresh keys it to the exact commit under review, which a push-fed index cannot
guarantee. `PushReceived` stays declared and unemitted.

And "zero core change" holds for **contribution**, not for **acquisition**. Contributing a new kind of
context really is free: the SPI, the fan-out, the timeout, the `CODE_SNIPPET` kind and the prompt slot
all exist. *Acquiring* it is not — this one needs diff-derived candidates on the wire and its own
prompt variable. The claim is worth keeping precisely because it is true of the expensive half; it was
overstated as covering both.

Memory works the same way: a `MemoryView` projects `ReviewCompleted` / `AuthorReplied` into a
learned-preferences store; a `MemoryContextProvider` reads it back as just another
`ContextContributed`.

## 6. Module layout (Quarkus multi-module → microservices, ADR-008)

One repo, Gradle multi-module → **shared libs + independently-deployable services** (`spire-*`).
Matches TECH-STACK §1/§3.

```
code-spire/
  # --- shared libraries ---
  spire-contract/          # events, commands, Decider/View/Saga, ALL SPI ports. Pure, no infra.
  spire-diff/              # patch parsing / token budgeting / prompt rendering. Pure lib.
  spire-scm-bitbucket/     # ScmIngress + DiffSource + CommentSink (Cloud & Data Center)
  spire-llm/               # LlmProvider adapters via LangChain4j (Vertex/Anthropic/Azure/Ollama)
  spire-context-jira/  spire-context-confluence/  spire-context-github/  spire-context-gitlab/
  spire-context-code/ (P3)                                  # ContextProvider plugins
  # --- deployable services ---
  spire-gateway/           # webhook ingress (the one sync edge, returns 202) + OIDC edge for UI/API
  spire-orchestrator/      # ReviewLifecycle decider + sagas + OWNS the event store; drives the pipeline
  spire-review-worker/     # GenerateReview / PostComments; uses spire-diff, spire-llm, spire-scm-bitbucket
  spire-context-worker/    # ContextProviders + the completeness aggregator
  spire-ui/                # dashboard BFF + owns the read-model projections; WebSockets push
```

- `spire-contract` has **no infrastructure dependency** — deciders/views/sagas are pure and
  unit-tested without Quarkus. Config is **injected**, never an ambient global (the deliberate
  opposite of PR-Agent's 729× `get_settings()`).
- Event store: append-only Postgres owned by `spire-orchestrator`. The backbone is the **Kafka
  protocol from v1** (Redpanda/Kafka); the SmallRye **in-memory connector is kept only for dev/test**.
  Domain code is connector-agnostic — it only speaks `@Incoming`/`@Outgoing` channels.
- **Build sequencing (ADR-008):** Phase 0 runs these modules **in one process** over the in-memory
  connector to prove the pipeline; Phase 1+ split them into the `spire-*` services over Redpanda.
  Same ports throughout → the split is wiring, not a rewrite.

## 7. Where PR-Agent informed the design vs where we built clean

PR-Agent was read as prior art during the design pass (`RESEARCH.md` §3) and **no upstream code was
used**. What it contributed was knowing which problems are real and which techniques hold up:

- unified-diff hunk parsing needs *both* old and new line numbers tracked through the hunk header,
- multi-file diffs must be compressed against a token budget, not truncated arbitrarily,
- token estimation wants a safety factor rather than an exact count,
- model output is reliably *almost* valid, so the parser must be defensive rather than strict.

Each of those is implemented independently in `spire-diff` / `spire-llm` against Code Spire's own
model, and credited in `NOTICE`. The prompts are Code Spire's own (`PromptCatalog`) — an earlier
draft of this section planned to convert upstream's Jinja templates and that was not done.

The shipped code was compared line-for-line against PR-Agent v0.38.0 on 2026-07-26:
[RESEARCH.md §4](RESEARCH.md) records exactly what the two share (the `__new hunk__` markers and one
numeric constant) and where they diverge (typed diff model vs string rewriting, heuristic vs
tokenizer, JSON vs YAML, and two prompt kinds with no upstream counterpart).

**Built clean (the parts PR-Agent does poorly or not at all):**
- the event-driven core (deciders/views/sagas) — PR-Agent is single-shot, synchronous,
- the plugin SPI + CDI discovery — PR-Agent has a hardcoded dict,
- segregated SCM ports with **thread-reply + PR-author first-class** — unimplemented on
  PR-Agent's Bitbucket providers,
- the context-provider pipeline / aggregator — PR-Agent hardcodes diff-only context,
- injected config — PR-Agent's global singleton.

## 8. WebSockets — where they fit

Quarkus WebSockets Next carries the **read side and the live experience**, never the domain flow:

- push `ReviewStatusView` updates to a live **event-model dashboard** (watch the timeline of a
  review advance in real time — a natural fit for an event-modeled system),
- stream LLM tokens / progress for a review as it generates,
- (optionally) a channel for an operator/chat UI.

The write side stays on the message bus; WebSockets is a projection transport.

## 9. Resolved (these were open; now decided)

- Event store → Postgres append-only (ADR-007). Domain formalism → hand-rolled `Decider/View/Saga`
  (no fmodel, ADR). Aggregator policy → received ⊇ expected OR 20s timeout (CONTRACT §8). Delivery →
  at-least-once, dedup on `eventId`, `(reviewId, commit)` for `RequestReview` (CONTRACT §1/§6).
- Operational/distributed-systems guards (idempotent posting, self-comment loop, stale-run pre-check,
  cancellation, timer ownership, retry budgets, truncated-diff behavior, schema-compat CI) →
  **ADR-013**. LLM threat model + cost caps → SECURITY.md.
