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

Closest starting points: **PR-Agent** (port its IP) and **ai-review** (BYO-LLM patterns).

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
port the IP, build the rest clean. See [DECISIONS.md](DECISIONS.md) ADR-002.

## 4. LLM routing note

GitHub Copilot has **no official general-purpose API** — programmatic access needs a reverse-engineered
OAuth proxy (what CLI tools like opencode use), which is unsupported and ToS-risky. Not suitable as a
backend inference engine. Use direct provider APIs (Vertex / Anthropic / Azure OpenAI) or in-cluster
models (Ollama) via LangChain4j, selected at configuration time.
