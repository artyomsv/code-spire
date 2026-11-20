# Prices to check before this branch is pushed

Every figure in `v1/catalog.yaml` was read on 2026-11-20 from the vendor page named in its row, and
converted to millicents per 1M tokens ($1.00 = 100000). Check each row against the page, then delete
this file in the commit after the check.

## Read from

- Anthropic prices: https://platform.claude.com/docs/en/about-claude/pricing ("Model pricing" table).
- Anthropic limits and capabilities: https://platform.claude.com/docs/en/about-claude/models/overview
  ("1M tokens" context, "128K tokens" max output, "All current models support text and image input, text
  output, multilingual capabilities, vision, and tool use"); structured outputs from the `supportedModels`
  list of https://platform.claude.com/docs/en/build-with-claude/structured-outputs.
- Anthropic thinking billing: https://platform.claude.com/docs/en/build-with-claude/thinking-steering-and-cost,
  "Tokens Claude uses while thinking (billed as output tokens)", so `reasoning` = `output`.
- OpenAI prices: https://developers.openai.com/api/docs/pricing (Standard, short context ≤272K input).
- OpenAI limits and capabilities: https://developers.openai.com/api/docs/models/<id> (1,050,000 context,
  128,000 max output, "text, image" input, function calling and structured outputs listed).
- OpenAI reasoning billing: https://developers.openai.com/api/docs/guides/reasoning, "are billed as output
  tokens", so `reasoning` = `output`. The row's `sourceUrl` stays the pricing page.

## Decisions to confirm

1. **Long-context tiers are not modelled.** OpenAI prices >272K input at 2x input and cache, 1.5x output;
   Claude Haiku 5.5 prices prompts >100,000 tokens higher ($0.50 in, $2.50 out, $0.05 cache read, $0.625 5m
   write, $1 1h write). The ledger has one rate per type, so those calls are under-charged. Decided on
   2026-11-20: keep the base rates; the gap is recorded in docs/UNVERIFIED.md and ai-catalog/README.md.
2. **OpenAI cache writes.** The page lists "Short context 30m write" and "12h write". `cacheWrite` holds the
   30m price; the 12h price has no field (`cacheWrite1h` is a 1-hour price). gpt-5.5 and gpt-5.4 show "-",
   so CACHE_WRITE is unpriced for them and a Codex build on them is refused `model_pricing_incomplete` until
   a manual model or a later catalog change prices it.
3. **GPT-5.6 Sol is promotional:** "GPT-5.6 Sol's promotional pricing is available until November 30, 2026."
   Its row needs a new price and `observedAt` after that date.
4. **Request settings (`api`)** are set from the vendors' API reference pages; see "Request settings" below.
5. **Left out:** `gpt-daybreak-blue-latest`, `gpt-daybreak-red-latest` and `codex-auto-review` (in the Codex
   image's list, not on the pricing page). No Gemini model.
6. **Aliases** come from the model pages: `gpt-5.6` ("The `gpt-5.6` alias routes requests to GPT-5.6 Sol."),
   `gpt-5.5-2026-04-23` and `gpt-5.4-2026-03-05` (each page's default snapshot).
7. Anthropic's "1M tokens" is written as 1000000 and "128K tokens" as 128000.

## Names, lifecycle and capabilities

| Model(s) | Field | Value | Stated on the page | Page |
|---|---|---|---|---|
| claude-fable-5-2, claude-opus-5-5, claude-sonnet-5-5, claude-haiku-5-5 | displayName | Claude Fable 5.2, Claude Opus 5.5, Claude Sonnet 5.5, Claude Haiku 5.5 | the "Latest models" table's header row | https://platform.claude.com/docs/en/about-claude/models/overview |
| the four Claude models | lifecycle.status | ACTIVE | listed under "## Latest models"; retirement "Not sooner than" a 2027 date for each | https://platform.claude.com/docs/en/about-claude/models/overview |
| the four Claude models | capabilities.promptCache | true | each has "Cache hits and refreshes" and cache-write prices in the "Model pricing" table | https://platform.claude.com/docs/en/about-claude/pricing |
| the four Claude models | capabilities.tools, imageInput | true | "All current models support text and image input, text output, multilingual capabilities, vision, and tool use." | https://platform.claude.com/docs/en/about-claude/models/overview |
| the four Claude models | capabilities.structuredOutput | true | listed in the page's `supportedModels` | https://platform.claude.com/docs/en/build-with-claude/structured-outputs |
| gpt-6-astra … gpt-5.4 | displayName | GPT-6 Astra, GPT-6 Sol, GPT-6 Luna, GPT-5.6 Sol, GPT-5.6 Terra, GPT-5.6 Luna, GPT-5.5, GPT-5.4 | each model page's title | https://developers.openai.com/api/docs/models/<id> |
| the eight OpenAI models | capabilities.promptCache | true | "prompt_caching" under "Supported features", and a "Cached input" price | https://developers.openai.com/api/docs/models/<id> |
| the eight OpenAI models | capabilities.tools, structuredOutput, imageInput | true | function calling and structured outputs under "Supported features"; "Input modalities: text, image" | https://developers.openai.com/api/docs/models/<id> |
| the eight OpenAI models | lifecycle.status | ACTIVE | **Not stated.** No page says current, latest, legacy or deprecated. The schema requires a status, so ACTIVE (not retired) is written on the evidence that each is on the current pricing page with no deprecation note. gpt-6-sol's page says "See GPT-6.1 Sol for the newer Sol model." Confirm or change. | https://developers.openai.com/api/docs/pricing |

**claude-fable-5-2 cache read, re-checked.** The row reads "| Claude Fable 5.2 | $8 / MTok | $10 / MTok | $16 / MTok |
$0.20 / MTok<sup>1</sup> | $40 / MTok |" (columns: base input, 5m cache writes, 1h cache writes, cache hits and
refreshes, output), and footnote 1 reads "Cache hits and refreshes on Claude Fable 5.2, Claude Mythos 5.2, Claude Fable
5.1, and Claude Mythos 5.1 are priced at 0.025x the base input price." 0.025 × $8 = $0.20, so the figure equals Opus
5.5's ($0.05 × $4) by the page's own multipliers.

## Request settings

What the review worker sends (`spire-llm`, `LangChain4jLlmProvider`): for `openai` it calls Chat Completions and
puts the output cap on `max_tokens` (MAX_TOKENS) or `max_completion_tokens` (MAX_COMPLETION_TOKENS), sends
`temperature` only when `temperature: true` (and retries once without it if the vendor rejects it), and sends
`reasoning_effort` when it is not null. For `anthropic` it calls the Messages API natively: it always sends
`max_tokens`, sends `temperature` only when `temperature: true`, and ignores `reasoningEffort`.

| Model(s) | Field | Value | Stated on the page | Page |
|---|---|---|---|---|
| all OpenAI | outputTokenParam | MAX_COMPLETION_TOKENS | `max_tokens`: "This value is now deprecated in favor of `max_completion_tokens`, and is not compatible with o-series models." | https://developers.openai.com/api/docs/api-reference/chat/create |
| all OpenAI | temperature | true | `temperature: optional number or null (default: 1)`, "What sampling temperature to use, between 0 and 2." No restriction names these models. | https://developers.openai.com/api/docs/api-reference/chat/create |
| all OpenAI | reasoningEffort | null | "Not all reasoning models support every value." Supported values differ per model (gpt-6-astra has no `none`; gpt-5.4 defaults to `none`), so nothing is sent and each model's default applies. | https://developers.openai.com/api/docs/models/<id>, https://developers.openai.com/api/docs/guides/reasoning |
| all Anthropic | outputTokenParam | MAX_TOKENS | `max_tokens`: "The maximum number of tokens to generate before stopping." (the native client always sends it) | https://platform.claude.com/docs/en/api/messages/create |
| claude-opus-5-5 | temperature | false | "Omit `temperature`, `top_p`, and `top_k`. Use prompting to guide the model's behavior." | https://platform.claude.com/docs/en/models/opus-5-5/migration-guide |
| claude-fable-5-2 | temperature | false | "Omit `temperature`, `top_p`, and `top_k`, as on Claude Fable 5.1." | https://platform.claude.com/docs/en/models/fable-5-2/migration-guide |
| claude-sonnet-5-5 | temperature | false | "Remove `temperature`, `top_p`, and `top_k`." | https://platform.claude.com/docs/en/models/sonnet-5-5/migration-guide |
| claude-haiku-5-5 | temperature | false | "On Claude Haiku 5.5, omit `temperature`, `top_p`, and `top_k`" | https://platform.claude.com/docs/en/models/haiku-5-5/migration-guide |
| all Anthropic | reasoningEffort | null | The Messages API's control is `output_config.effort` ("Valid values are `low`, `medium`, `high`, `xhigh`, or `max`."); the review worker does not send it, so a value here would do nothing. | https://platform.claude.com/docs/en/api/messages/create |

Mismatches found, not fixed here: the review worker cannot send Anthropic's `output_config.effort`, and the
catalog's `reasoningEffort` accepts only low, medium and high although both vendors document more levels.

## Every price

| Model | Field | As quoted | Millicents / 1M | Page |
|---|---|---|---|---|
| claude-fable-5-2 | input | $8 | 800000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-fable-5-2 | output | $40 | 4000000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-fable-5-2 | cacheRead | $0.20 | 20000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-fable-5-2 | cacheWrite | $10 | 1000000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-fable-5-2 | cacheWrite1h | $16 | 1600000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-fable-5-2 | reasoning | $40 | 4000000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-opus-5-5 | input | $4 | 400000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-opus-5-5 | output | $20 | 2000000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-opus-5-5 | cacheRead | $0.20 | 20000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-opus-5-5 | cacheWrite | $5 | 500000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-opus-5-5 | cacheWrite1h | $8 | 800000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-opus-5-5 | reasoning | $20 | 2000000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-sonnet-5-5 | input | $2 | 200000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-sonnet-5-5 | output | $10 | 1000000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-sonnet-5-5 | cacheRead | $0.10 | 10000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-sonnet-5-5 | cacheWrite | $2.50 | 250000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-sonnet-5-5 | cacheWrite1h | $4 | 400000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-sonnet-5-5 | reasoning | $10 | 1000000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-haiku-5-5 | input | $0.10 | 10000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-haiku-5-5 | output | $0.50 | 50000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-haiku-5-5 | cacheRead | $0.01 | 1000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-haiku-5-5 | cacheWrite | $0.125 | 12500 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-haiku-5-5 | cacheWrite1h | $0.20 | 20000 | https://platform.claude.com/docs/en/about-claude/pricing |
| claude-haiku-5-5 | reasoning | $0.50 | 50000 | https://platform.claude.com/docs/en/about-claude/pricing |
| gpt-6-astra | input | $10.00 | 1000000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-astra | output | $50.00 | 5000000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-astra | cacheRead | $1.00 | 100000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-astra | cacheWrite | $12.50 | 1250000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-astra | reasoning | $50.00 | 5000000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-sol | input | $2.00 | 200000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-sol | output | $10.00 | 1000000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-sol | cacheRead | $0.20 | 20000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-sol | cacheWrite | $2.50 | 250000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-sol | reasoning | $10.00 | 1000000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-luna | input | $0.10 | 10000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-luna | output | $0.50 | 50000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-luna | cacheRead | $0.01 | 1000 | https://developers.openai.com/api/docs/pricing |
| gpt-6-luna | cacheWrite | $0.125 | 12500 | https://developers.openai.com/api/docs/pricing |
| gpt-6-luna | reasoning | $0.50 | 50000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-sol | input | $4.00 | 400000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-sol | output | $20.00 | 2000000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-sol | cacheRead | $0.40 | 40000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-sol | cacheWrite | $5.00 | 500000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-sol | reasoning | $20.00 | 2000000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-terra | input | $2.00 | 200000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-terra | output | $12.00 | 1200000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-terra | cacheRead | $0.20 | 20000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-terra | cacheWrite | $2.50 | 250000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-terra | reasoning | $12.00 | 1200000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-luna | input | $0.20 | 20000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-luna | output | $1.20 | 120000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-luna | cacheRead | $0.02 | 2000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-luna | cacheWrite | $0.25 | 25000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.6-luna | reasoning | $1.20 | 120000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.5 | input | $5.00 | 500000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.5 | output | $30.00 | 3000000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.5 | cacheRead | $0.50 | 50000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.5 | reasoning | $30.00 | 3000000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.4 | input | $2.50 | 250000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.4 | output | $15.00 | 1500000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.4 | cacheRead | $0.25 | 25000 | https://developers.openai.com/api/docs/pricing |
| gpt-5.4 | reasoning | $15.00 | 1500000 | https://developers.openai.com/api/docs/pricing |
