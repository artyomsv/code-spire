# Architecture — event-driven, plugin-first core

> Status: design sketch. Companion to [EVENT-MODEL.md](EVENT-MODEL.md), which holds the
> concrete slices (events/commands/read models). This doc explains the *shape* and *why*.
>
> **Visual board:** open [diagrams/architecture.html](diagrams/architecture.html) in a browser — a
> swimlane-per-layer view of the deployable services, the Kafka/Redpanda bus, the shared-library SPI +
> adapters (single row), the data stores (their own lane), and the external systems, with roadmap pieces
> (RAG / vector index, repo-rules context, MinIO, OIDC, real webhooks) drawn dashed. The board scrolls
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
concurrent (per-aggregate serialization by stream id). Later, `RepositoryIndex` becomes a
second decider for the RAG index lifecycle.

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

Adding RAG later is *adding a saga + a plugin*, not editing this list's neighbours (see §5).

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
                              [Jira plugin]   [Confluence plugin] [RAG plugin]  [rules plugin]
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
| `ContextProvider` | on `ContextRequested`, emit a `ContextContributed` | Jira, Confluence, RAG, rules, memory |
| `LlmProvider` | handle `GenerateReview` → `ReviewGenerated` | Vertex, Anthropic, Azure OpenAI, Ollama (via LangChain4j) |
| `Capability` | a self-contained flow: declares its events, commands, prompts, config | review, describe, changelog, … |

Discovery: Quarkus CDI — `@All List<ContextProvider> providers;` gives the aggregator every
context plugin on the classpath. Drop a jar → new provider participates. Config selects *which*
LLM/SCM providers are active (no default; fail-fast if unset).

## 5. Adding a capability with zero core change — worked example (RAG)

To add whole-repo RAG context *later*:

1. Ship a `spire-context-rag` module with a `RagContextProvider implements ContextProvider`.
2. It **subscribes** to `ContextRequested`, queries the vector store, and **emits**
   `ContextContributed{source=RAG, snippets=[...]}`.
3. It **subscribes** to SCM `PushReceived` events and maintains the index (`RepositoryIndexDecider`
   → `RepositoryIndexed`), fully independently.

That's it. The aggregator already collects *all* `ContextContributed` events up to a completeness
threshold or timeout, so the new snippets flow into `ContextAssembled` → the prompt — **without
editing the review flow, the deciders, or any other plugin.** Contrast with PR-Agent, where the
same feature means forking every tool's `_prepare_prediction`.

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
  spire-diff/              # PORTED from PR-Agent: patch/token/compression + prompt templates. Pure lib.
  spire-scm-bitbucket/     # ScmIngress + DiffSource + CommentSink (Cloud & Data Center)
  spire-llm/               # LlmProvider adapters via LangChain4j (Vertex/Anthropic/Azure/Ollama)
  spire-context-jira/  spire-context-confluence/  spire-context-rag/ (P3)   # ContextProvider plugins
  # --- deployable services ---
  spire-gateway/           # webhook ingress (the one sync edge, returns 202) + OIDC edge for UI/API
  spire-orchestrator/      # ReviewLifecycle decider + sagas + OWNS the event store; drives the pipeline
  spire-review-worker/     # GenerateReview / PostComments; uses spire-diff, spire-llm, spire-scm-bitbucket
  spire-context-worker/    # ContextProviders + the completeness aggregator
  spire-indexer/ (P3)      # repo vector index; RAG ContextProvider
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

## 7. What we port from PR-Agent vs build clean

**Port faithfully into `spire-diff` (the hard-won IP, language-agnostic):**
- unified-diff hunk parsing + line-number reinjection (`git_patch_processing.py`),
- token-budget-aware multi-file diff compression (`pr_processing.py`),
- token counting / `clip_tokens` (use `jtokkit` in Java),
- YAML-repair for model output,
- the ~1,500 lines of tuned prompt templates → **Qute** templates (was Jinja2). Prompts are data.

**Build clean (the parts PR-Agent does poorly or not at all):**
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
