# Code Spire - Product Requirements

> The product layer: the problem, who it's for, what it must do, and how we'll know it works.
> The technical docs ([ARCHITECTURE](ARCHITECTURE.md), [CONTRACT](CONTRACT.md), [DATA-MODEL](DATA-MODEL.md),
> [SECURITY](SECURITY.md), ...) describe *how* these requirements are met; [DECISIONS](DECISIONS.md)
> records *why* the technical choices. Status: **v1 planning**.

## 1. Problem & motivation

AI pull-request review is valuable, but the market forces a bad trade:
- The polished tools (Greptile, CodeRabbit, Qodo Merge) are **closed and/or per-seat SaaS**, and
  **Greptile has no Bitbucket support at all**.
- The one mature open-source option (`qodo-ai/pr-agent`) is **single-shot, has no plugin system, no
  whole-repo context, and no learned memory**.
- Teams with **data-residency constraints** can't send source code to a vendor cloud, and teams that
  want **one reviewer across all PRs** shouldn't pay per developer.

**Code Spire** fills that gap: an **open-source, self-hosted, event-driven, plugin-first** AI reviewer
that reviews *every* PR from *any* author with *no per-seat cost*, runs entirely in the adopter's own
infrastructure, and lets the source-control platform, the LLM provider, and future capabilities be
swapped or added as plugins. (Full landscape in [RESEARCH.md](RESEARCH.md).)

## 2. Users

| Persona | Who | Interacts via | Cares about |
|---|---|---|---|
| **PR Author** (developer) | Anyone opening a PR in a connected repo | **PR comments only** (never the Code Spire UI) | Fast, relevant, low-noise feedback; asking follow-up questions in-thread |
| **Operator / Maintainer** | Team lead who runs the bot for a repo/org | The **dashboard + config/rules** (behind auth) | Control over what's reviewed and how; visibility into what the bot is doing; confidence code isn't leaking |
| **Adopter / Platform engineer** | Person who self-hosts Code Spire | Deploy manifests + config | Easy install; provider/LLM choice; data residency; no per-seat cost |

## 3. Goals & non-goals

**Goals**
- G1 - Automatically review **every** PR (on open and on update), **author-agnostic**, with **no per-seat cost**.
- G2 - Deliver review **in the PR itself** - inline comments, a summary, and **conversational** follow-ups.
- G3 - Run **fully self-hosted** with an option for **zero code egress**.
- G4 - Be **provider-agnostic**: pluggable SCM and pluggable LLM (bring-your-own key).
- G5 - Be **extensible**: add a capability (context source, review type, memory) as a plugin, minimal core change.
- G6 - Be **context-aware**: use the linked ticket/pages now; whole-repo understanding later.

**Non-goals**
- Not a per-developer IDE assistant (that's Copilot/Cursor) - Code Spire is a shared server-side bot.
- Not a static-analysis / CI gate replacement - it complements SonarQube et al., it doesn't replace them.
- Not (primarily) a hosted SaaS - the product is the self-hostable system.
- Not locked to one SCM or one LLM.
- v1 does **not** include whole-repo RAG or learned memory (later phases).

## 4. Functional requirements

Legend: **[v1]** target for first release; **[later]** a subsequent phase.

- **FR-1 - Automatic trigger [v1].** On PR opened and on new commits pushed, a review runs automatically
  for every PR in a connected workspace, regardless of author, through a **single bot identity**
  (workspace webhook, not per-user installs).
- **FR-2 - Inline findings [v1].** Post review comments on the exact changed lines they refer to.
- **FR-3 - Summary [v1].** Post one PR-level summary comment.
- **FR-4 - Conversation [v1].** When the author replies to a bot comment, answer **in that thread** with
  the thread's context.
- **FR-5 - External context [v1].** Incorporate the linked issue tracker ticket and any linked wiki pages
  (Jira / Confluence first) into the review.
- **FR-6 - Custom rules [v1].** Honor repo/org-level review rules/instructions supplied by the operator.
- **FR-7 - Provider selection [v1].** The SCM platform and the LLM provider are chosen at configuration
  time (operator brings the API key); there is **no hard-coded default** - a missing choice fails fast.
- **FR-8 - Operator dashboard [v1].** Behind authentication, an operator can watch review status, the
  live event timeline, findings, and errors/retries - **including dead-lettered reviews, with a replay
  action** (so "in the DLQ" never means "silently dropped", per NFR-7).
- **FR-9 - Whole-repo context [later].** Retrieve semantically related code from across the repository
  (not just the diff) to inform reviews.
- **FR-10 - Learned memory [later].** Adapt to a team's recurring preferences over time.
- **FR-11 - Per-author analytics [later].** Aggregate review activity/quality per author and per repo.
- **FR-12 - Manual re-review [v1].** A maintainer can trigger a fresh review on demand (e.g. a
  `/review` comment), bypassing the already-reviewed idempotency.
- **FR-13 - Cost/abuse controls [later].** Per-repo/workspace rate limits, a daily spend cap, and skips
  for draft/WIP and bot-authored PRs. (v1 ships without these - a documented gap; see SECURITY.md.)

## 5. Non-functional requirements

- **NFR-1 - Self-hostable.** Deployable into the adopter's own infrastructure from one repository, containerized.
- **NFR-2 - Zero code egress (option).** The operator can keep both source and inference inside their
  tenant (e.g. in-region LLM or an in-cluster model). Source is **not persisted** - diffs are re-fetched on demand.
- **NFR-3 - No per-seat cost.** No licensing gate on the number of developers or contributors.
- **NFR-4 - Provider-agnostic.** SCM (Bitbucket Cloud/DC, GitHub, GitLab) and LLM are adapters behind
  stable interfaces; adding one requires no core change. Verified neutral model: [SCM-MAPPING.md](SCM-MAPPING.md).
- **NFR-5 - Extensible (plugin-first).** New capabilities register as plugins on the event bus; the core
  does not change to gain them.
- **NFR-6 - Secure by default.** Encryption at rest (incl. event payloads that carry code); OIDC + RBAC
  on the UI; signed webhooks; secrets externalized with fail-fast; no source stored long-term; user email
  never logged or persisted. **Untrusted-content handling:** PR text, diffs, and retrieved context are
  treated as data-not-instructions (prompt-injection resistant), and model output is sanitized before
  posting. Details: [SECURITY.md](SECURITY.md).
- **NFR-7 - Reliable.** At-least-once processing with idempotent handlers; PR bursts absorbed by
  horizontal scale; a crash never silently drops a review.
- **NFR-8 - Observable.** Metrics, traces, and structured logs, plus the live dashboard, so an operator
  can always tell what the bot is doing and why.
- **NFR-9 - Author-agnostic by default.** Author identity is optional data captured for later analytics -
  never a gate on who gets reviewed.

## 6. Scope & phasing

Aligned to [ROADMAP.md](ROADMAP.md). **v1 = FR-1..FR-8 + FR-12 + all NFRs, on Bitbucket Cloud.**

| Phase | Delivers | Requirements |
|---|---|---|
| P0 | Event backbone + skeleton (incl. the first dashboard: live event timeline) | infrastructure for all; FR-8 (initial) |
| P1 | Real Bitbucket-Cloud review, one bot | FR-1, FR-2, FR-3, FR-7, FR-8 (full); NFR-1..8 |
| P2 | Context providers + conversation | FR-4, FR-5, FR-6, FR-12 |
| P3 | Whole-repo RAG | FR-9 |
| P4 | Memory + analytics | FR-10, FR-11 |
| Ongoing | More SCM/LLM adapters; cost/abuse controls | NFR-4; FR-13 |

**Out of scope for v1:** RAG, learned memory, per-author analytics, non-Bitbucket SCMs (adapters come
after the Bitbucket-Cloud path proves the model), a hosted SaaS offering.

## 7. Assumptions & constraints

- **First SCM is Bitbucket Cloud** (`api.bitbucket.org/2.0`, App Password, signed webhooks).
- **Adopters supply their own LLM provider + key**; Code Spire ships no keys and no default provider.
- **Open source, Apache-2.0, clean-room** - no code is reused from any private source ([DECISIONS](DECISIONS.md) ADR-006, ADR-009).
- Built in **private time by a solo maintainer** - scope and pace reflect that; simplicity for adopters is valued.
- The primary review UX is **the PR itself**; the web UI is an operator/observability surface, not an end-user product.

## 8. Success criteria

**v1 is successful when**, in an adopter's own infrastructure:
1. Opening or updating a PR in a Bitbucket Cloud repo triggers an automatic review from **one bot
   account**, with **inline findings + a summary**, using a **config-selected LLM** (the adopter's key).
2. The author can **reply to a bot comment and get an in-thread answer**.
3. The review reflects the **linked ticket / rules** context.
4. It runs with **no per-seat cost**, **no source persisted**, and the operator can **watch it live** on
   the dashboard.
5. A developer can **add a new context provider as a plugin without modifying the core**.

**Longer-term health signals:** reviews are useful and low-noise (authors act on them, don't mute the
bot); review latency is acceptable; adopters successfully self-host on their own SCM/LLM; contributors
extend it via plugins rather than forks.

## 9. Traceability

Product (this doc) -> decisions ([DECISIONS.md](DECISIONS.md)) -> design
([ARCHITECTURE](ARCHITECTURE.md), [EVENT-MODEL](EVENT-MODEL.md), [CONTRACT](CONTRACT.md),
[DATA-MODEL](DATA-MODEL.md), [SCM-MAPPING](SCM-MAPPING.md), [SECURITY](SECURITY.md), [TECH-STACK](TECH-STACK.md))
-> build ([ROADMAP.md](ROADMAP.md)).
