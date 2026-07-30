# No way to inspect the actual prompt sent to the model

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Small |
| Location | `spire-review-worker` — `ReviewWorker.runReviewCall`/`reconcile` (builds the `Prompt`, then calls `client.provider().complete(...)`); `spire-llm` — `LangChain4jLlmProvider.callModel` (sends it) |
| Found during | Task 12 (issue-context-providers plan) — writing the live-verification runbook for the GitHub/GitLab Issues context providers |
| Date | 2026-07-30 |

## Issue

The rendered `Prompt` (system + user text, including the fenced `{{context}}` slot
`ReviewPromptBuilder` fills in) is built in `ReviewWorker` and handed straight to
`WorkerLlmProvider.LlmClient.provider().complete(prompt, params)`. It is never logged, never
persisted, and nothing in the dashboard shows it. `review_llm_call` (V16) stores only `model`,
`tokens_in`, `tokens_out`, `cost_millicents` — not the prompt itself. Settings → Prompts
(`PromptDetail.tsx`) shows the editable **template**, not a rendered instance with real diff/context
data.

This surfaced while writing the Task 12 runbook: the original plan called for opening "the review's
LLM call record" to confirm a context item's title reached the model. That screen does not exist.
The permanent replacement is a worker-level seam test
(`ReviewWorkerTest.assembledContextReachesThePromptSentToTheModel`) that captures the `Prompt` handed
to a fake `LlmProvider` and asserts the context item's text is inside it — which is a real
regression guard, but it is not a substitute for an operator being able to inspect what a *specific*
real review actually sent.

## Risks

- An operator debugging a bad or surprising review (wrong finding, ignored context, garbled
  instructions) cannot see the exact text the model received — only the diff/context inputs and the
  model's output, with the assembly step in between opaque. Every "why did it say that" investigation
  has to reconstruct the prompt by re-running `ReviewPromptBuilder.build` by hand.
- The gap is invisible until someone needs it, which is exactly when it's most costly (a live
  incident or a confused operator, not a quiet afternoon).
- Prompt-template customization (ADR — operator-controlled prompts) makes this worse over time: a
  misconfigured custom template can produce a broken *rendered* prompt that still looks fine in the
  template editor, and there is no way to see the actual render that misbehaved.

## Suggested Solutions

1. **Cheap option, off by default for a reason:** LangChain4j's `OpenAiChatModel` /
   `AnthropicChatModel` / `GoogleAiGeminiChatModel` builders all support `.logRequests(true)`
   /`.logResponses(true)`, which would print the full raw HTTP request/response (at DEBUG) with no
   further code changes. Deliberately not wired in by default: the raw request carries the full diff
   and any retrieved context, which may quote source (SECURITY.md's untrusted-context handling) —
   turning it on unconditionally would put that content in plaintext logs, which is a bigger surface
   than the encrypted event/blob stores this project otherwise holds it in. Gate it behind an
   explicit opt-in config flag the operator sets only for the duration of a debugging session
   (documented as a security tradeoff, not a default).
2. **More structured:** persist a short-lived, encrypted "last rendered prompt" per review (same
   Tink/AAD pattern as `worker.context_blob`), shown on the review detail page behind an explicit
   "show what was sent" action — mirrors how findings/context are already handled, and keeps the
   content out of plaintext logs entirely. Needs a retention/TTL decision (it duplicates the diff +
   context another way, which ADR-011 deliberately avoids for diffs).
3. Either way, the new seam test should stay — it is the only thing in CI that would catch a future
   regression where the prompt is built but never actually reaches `complete(...)` (e.g. a refactor
   that reorders `loadContext` after the call, or drops the `context` argument silently).
