# Research

Market landscape and the PR-Agent code evaluation that informed the build decision.
Findings date-stamped mid-2026; this market moves fast — re-verify before relying on a row.

## 1. The gap

- **Greptile** (the inspiration): whole-repo indexed context + learned team memory, but **GitHub/
  GitLab only — no Bitbucket**, closed source, self-hosting only on the Enterprise tier.
- **Mature OSS** = `qodo-ai/pr-agent` (Apache-2.0): multi-SCM incl. Bitbucket, BYO-LLM via LiteLLM —
  but single-shot, no plugin system, no whole-repo context, no learned memory (see §3).
- **Good SaaS** (CodeRabbit, Qodo Merge, Bito, Korbit, Sourcery, Ellipsis, Baz, Cursor BugBot) are
  closed and per-seat/per-contributor, and most are GitHub-first.

**Fillable gap:** a plugin-first, self-hosted, whole-repo-aware reviewer that works Bitbucket-first
and lets you add capabilities without touching the core. That is Code Spire.

## 2. Bitbucket-capable alternatives (why none fit the goal)

Legend: ✅ yes · ⚠️ partial · ❌ no

| Tool | BB Cloud | BB Data Center | Self-host | BYO-LLM | One-bot (not per-seat) | Whole-repo RAG | Plugin-first |
|---|---|---|---|---|---|---|---|
| **PR-Agent (OSS)** | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |
| Qodo Merge (commercial) | ✅ | ✅ | ✅ | ✅ | ⚠️ per-contributor billing | ⚠️ | ❌ |
| CodeRabbit | ✅ | ⚠️ | ❌ | ❌ | ❌ per-seat | ⚠️ | ❌ |
| Atlassian Rovo Dev | ✅ | ❌ | ❌ | ❌ | ❌ per-user | ❌ | ❌ |
| Greptile | ❌ | ❌ | ⚠️ enterprise | ❌ | n/a | ✅ | ❌ |
| SonarQube + AI CodeFix | ✅ | ✅ | ✅ | ⚠️ | ✅ | ❌ (static analysis) | ❌ |
| ai-review (OSS) | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ |

Closest starting points: **PR-Agent** (its diff/token techniques) and **ai-review** (BYO-LLM patterns).

## 3. PR-Agent code evaluation (qodo-ai/pr-agent, ~22k LOC, v0.38)

Five parallel deep-dives into the actual source.

| Axis | Score | Finding |
|---|---|---|
| SCM abstraction | 3/5 | `GitProvider` ABC with ~21 abstract + ~30 default methods — a God-object. GitHub concepts (reactions, check-runs, suggestion blocks) baked into the core. **`reply_to_comment` and PR-author are unimplemented on both Bitbucket providers** — the two features we need most. Provider selection is a hardcoded dict (not open/closed). |
| Plugin extensibility | 2/5 | Capabilities are a hardcoded `command2class` dict; adding one touches ~9 core places. Engine is **single-shot, no tool-use loop**. The one "extension" (SKILL.md) injects *static prompt text only*. A RAG/vector plugin has **no hook point** — you'd fork every tool. |
| LLM layer | 3.5/5 | Embedded LiteLLM (multi-provider + custom `api_base` + fallback) — so a separate gateway is largely redundant. Prompts externalized as Jinja/TOML (portable as *data*). The **diff-compression + token-budgeting pipeline is the real, hard-won IP**. String-in/string-out interface — no tool-calling/structured output. |
| RAG / memory | 0/5 | Reviews use **only the PR diff ±10 lines + fetched tickets** — stateless, cold every time. The single vector feature (`/similar_issue`, Pinecone/LanceDB/Qdrant) indexes **issues, not code**, GitHub-only, and is not wired into reviews. Whole-repo indexing, code embeddings, retrieval-into-review, incremental re-index, learned memory — **all absent**. |
| Config / quality | 3/5 | Stateless (good for a bot). But **729× `get_settings()` global** coupling; heavy multi-SCM/cloud-SDK deps; 87 test files (strong on the algo core). Forking ripples widely due to ambient config. |

**Conclusion:** the valuable, hard-to-rebuild asset is small (~2k LOC of diff/token algorithms +
~1,500 lines of prompts) and **portable**. Everything Code Spire actually differentiates on (plugin
architecture, whole-repo RAG, memory, Bitbucket thread-reply/author) is net-new regardless. Hence:
learn from its diff/token techniques, build the rest clean. See [DECISIONS.md](DECISIONS.md) ADR-002.

> This section is **design-time** — an assessment of what *might* be reused, written before any code
> existed. What was actually built shares almost nothing with it; §4 is the verified comparison
> against the shipped code. In particular the "~1,500 lines of prompts" were never converted.

## 4. Independent-implementation comparison vs PR-Agent (verified 2026-07-26)

§3 was a *design-time* evaluation, written before Code Spire existed. This section is the opposite:
the shipped code compared against PR-Agent v0.38.0's actual source, done during the ADR-021 licensing
pass to establish on the record what the two codebases share. Upstream files read:
`pr_agent/algo/git_patch_processing.py`, `pr_agent/algo/utils.py`,
`pr_agent/settings/pr_reviewer_prompts.toml`.

### 4.1 What genuinely overlaps

Two things, both narrow, both disclosed in [`NOTICE`](../NOTICE):

1. **The hunk-rendering markers.** Both emit `__new hunk__` / `__old hunk__` and a `## File: '<path>'`
   header (`DiffRenderer` adds the change type; upstream does not). These are short functional
   identifiers in a prompt format — a convention agreed with the model, not creative expression.
2. **The `0.9` safety factor** on token clipping. One numeric constant, and it is applied to a
   different quantity on each side: upstream discounts a *real tokenizer count*, `TokenBudget`
   discounts a *character-ratio estimate*.

Nothing else. No upstream source was translated.

### 4.2 The diff layer — a different data model, for a different job

| | PR-Agent v0.38.0 | Code Spire |
|---|---|---|
| Representation | patch **text in, text out** — `extend_patch`, `handle_patch_deletions`, `decouple_and_convert_to_hunks_with_lines_numbers` all take and return `str` | typed records: `FilePatch` → `Hunk` → `DiffLine` |
| Line numbers | `start1/size1/start2/size2` tuples pulled from the hunk header while rewriting text | **both numbers carried per line** on `DiffLine(type, oldLine, newLine, content)` |
| Purpose | produce prompt text | produce prompt text **and** the coordinates `InlineAnchor` needs to post a comment on the right line on four different SCM APIs |

The per-line dual numbering is the whole reason the model exists here — inline anchoring across
Bitbucket/GitHub/GitLab/DC (SCM-MAPPING.md). Upstream never materialises a line-level model because
it does not need one.

### 4.3 Token budgeting and output parsing

| | PR-Agent v0.38.0 | Code Spire |
|---|---|---|
| Token counting | real tokenizer — `TokenEncoder.get_token_encoder()`, `encoder.encode(text)` | **no tokenizer**: `length / 3.2` heuristic in `TokenBudget` |
| Clip behaviour | `add_three_dots`, `delete_last_line` flags | backs off to a line boundary so a dangling fragment cannot produce a mis-cited anchor; counts its own marker against the budget |
| Model output format | **YAML** | **JSON** |
| Repair strategy | `try_fix_yaml` — 11 sequential textual repairs (pipe handling, indent fixes, encoding fallbacks) | `FindingsParser` — Jackson lenient features (trailing commas, comments, single quotes) + outermost-object extraction, then honest degradation to a summary |

The shared premise — models emit *almost*-valid output, so parse defensively — is an observation
about LLMs, not a design anyone owns. The formats and the mechanisms differ entirely.

### 4.4 Prompts — no meaningful relation

| | PR-Agent v0.38.0 | Code Spire |
|---|---|---|
| Medium | Jinja2 templates in TOML | Java text blocks in `PromptCatalog` (183 lines) |
| Output schema | `review.estimated_effort_to_review_[1-5]`, `score`, `key_issues_to_review`, `security_concerns`, `ticket_compliance_check`, `todo_sections`, `can_be_split`, `relevant_tests` | `summary` + `findings[path, line, endLine, severity, message, suggestion]` |
| Rating scheme | effort **1–5** plus a quality **score 0–100** | severity enum `BLOCKER/MAJOR/MINOR/INFO/NIT` |
| Injection defence | **none** — no fencing markers | `BEGIN_UNTRUSTED_DATA` / `END_UNTRUSTED_DATA` plus a locked, non-editable clause instructing the model to ignore instruction-like text inside |
| Prompt kinds | review (other tools are separate commands) | `REVIEW`, **`RECONCILE`**, **`FOLLOWUP`** |

`RECONCILE` and `FOLLOWUP` have **no upstream counterpart at all**. PR-Agent is single-shot and
stateless (§3 scored its memory 0/5), so it has no notion of judging a prior finding against a later
commit (`resolved` / `still-open` / `acknowledged` / `superseded` / `unchanged`, ADR-019) or of
holding a thread conversation. Two of the three shipped prompts address a problem upstream does not
have.

The ~1,500 lines of upstream prompt templates §3 proposed converting were never converted. The
catalog is an eighth that size and structurally unrelated.

### 4.5 Architecture — no correspondence

| | PR-Agent v0.38.0 | Code Spire |
|---|---|---|
| Stack | Python | Java 25 / Quarkus 3.36 |
| Process model | one synchronous in-process invocation | three deployables over the Kafka protocol (gateway / orchestrator / worker) |
| State | stateless, cold every run | Postgres event store with optimistic concurrency + read models |
| Structure | `command2class` dispatch dict | `Decider` / `View` / `Saga` triad, event choreography (ADR-004, ADR-010) |
| Config | 729× `get_settings()` global | injected; credentials brokered per-command, encrypted (ADR-015) |
| Extension | adding a capability touches ~9 core places | subscribe to events, zero core edits (ADR-020 enforces it at build time) |
| Delivery | none | idempotency claims, DLQ, stale-run guard, bounded retry (ADR-013, ADR-016) |

Event sourcing, sagas, the aggregate, reconciliation across review rounds, the conversation loop and
the plugin SPI have no upstream analogue — they are answers to problems a single-shot script does not
encounter.

## 5. LLM routing note

GitHub Copilot has **no official general-purpose API** — programmatic access needs a reverse-engineered
OAuth proxy (what CLI tools like opencode use), which is unsupported and ToS-risky. Not suitable as a
backend inference engine. Use direct provider APIs (Vertex / Anthropic / Azure OpenAI) or in-cluster
models (Ollama) via LangChain4j, selected at configuration time.
