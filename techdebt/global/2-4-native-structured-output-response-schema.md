# Use native structured-output / response schemas instead of prompt-text JSON + lenient parsing

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Large |
| Location | `spire-llm/src/main/java/dev/codespire/llm/LangChain4jLlmProvider.java`, `FindingsParser.java`, `VerdictsParser.java`; `spire-contract/.../port/LlmProvider.java`; `spire-orchestrator/.../llm/LlmModelRegistry.java` (catalog capability) |
| Found during | Prompt-management feature — operator asked why some prompts request JSON and some don't |
| Date | 2026-07-23 |

## Issue

The review and reconcile prompts ask the model for JSON **as free text in the prompt**
(`REVIEW_CONTRACT` / `RECONCILE_CONTRACT` in `PromptCatalog`) and then parse the response with
**lenient hand-rolled parsers** (`FindingsParser`, `VerdictsParser`). No provider-native
structured-output feature is used anywhere: `LangChain4jLlmProvider.callModel` builds a
`ChatRequest` that sets only `maxTokens` + `temperature` — there is no `response_format`,
`json_schema`, tool-forcing (`tool_choice`), or Gemini `responseSchema`. `FindingsParser` even
carries a "could not be parsed into structured findings" fallback branch, i.e. malformed output is
expected and tolerated.

(The plain-text follow-up prompt is correct and out of scope — its output *is* the reply posted
verbatim into the PR thread; there is nothing to structure. This debt is only about the two
structured prompts.)

The current approach is a deliberate lowest-common-denominator choice driven by the plugin-first,
provider-neutral design (ADR-001): Code Spire targets OpenAI, Anthropic, Gemini, and arbitrary
OpenAI-compatible endpoints (local models via Ollama / vLLM / llama.cpp), which support native
structured output unevenly. Text-JSON + lenient parsing works everywhere. But it is the weakest
link in output reliability and leaves real provider capabilities (and cost levers) on the table.

## Risks

- **Silently dropped or mangled findings/verdicts.** A model that wraps JSON in prose, trails a
  comma, or emits a near-miss shape can yield zero or partial findings with no hard error — a review
  that under-reports without signalling it. Native schema output makes malformed responses
  structurally impossible.
- **Verdict parse failures** in reconcile degrade re-review correctness (a mis-parsed verdict can
  leave a resolved finding open or vice-versa).
- **Cost.** Providers with schema/prompt caching bill cached tokens at a large discount. Gemini
  context caching (and OpenAI/Anthropic prompt caching) can cache the fixed schema + system preamble
  so that on repeat calls only the fresh user input and the response output are charged at full rate
  — a material saving on a bot that reviews every PR. The current all-text-every-call path cannot
  exploit this.
- **Prompt-management interaction.** Today the output contract lives as editable-adjacent *text* in
  the locked system suffix. Moving it to a code-owned schema object makes it something an operator
  literally cannot break — strictly safer than the current "locked text the model may ignore".

## Suggested Solutions

1. **Capability-gated native structured output (recommended).** Mirror the ADR-018 param-profile
   pattern: add a `supports_structured_output` capability to the `llm_model` catalog, and an optional
   response-schema argument on the `LlmProvider.complete` SPI. When the resolved model advertises
   support, pass the provider-native schema (OpenAI `response_format: json_schema`, Anthropic
   tool-forcing, Gemini `responseSchema`) and skip lenient parsing; otherwise fall back to today's
   text-JSON + `FindingsParser`/`VerdictsParser`. Keep the fallback well-tested — it stays the path
   for uncatalogued/local models.
2. **Provider prompt/context caching** as a follow-on once schemas are code-owned: cache the schema +
   system preamble per provider so repeat reviews bill only fresh input+output (esp. Gemini context
   caching). Needs a cache-key/TTL strategy and per-provider wiring.
3. Requires an ADR (touches the `LlmProvider` SPI contract + the model catalog + three provider
   adapters + the locked-contract shape from the prompt-management design) — brainstorm before
   building.
