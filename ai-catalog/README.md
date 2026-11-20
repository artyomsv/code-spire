# The model catalog

`v1/catalog.yaml` lists the chat models the app can price and run: who makes each one, what it
accepts, and what it costs. Every install reads it from a URL (below) and keeps the last good copy,
so a model or a price change is a merged pull request: no migration, no release, no operator typing.

Operators can still add manual models in Settings → LLM → Models. A manual model with the same name
as a catalog model, or as one of its aliases, wins on that install.

## The format

```yaml
providers:
  - code: anthropic             # openai | anthropic | gemini
    displayName: Anthropic
    pricingUrl: https://…       # the vendor page prices are read from
models:
  - modelId: example-model      # the exact name a harness or the review path reports; unique in the file
    provider: anthropic         # a listed provider
    displayName: Example Model
    aliases: []                 # other names the same model is reported under; unique in the file
    lifecycle:
      status: ACTIVE            # ACTIVE | DEPRECATED | RETIRED
      deprecationDate: null
      retirementDate: null
      successor: null
    contextWindow: null         # tokens, from the vendor's model page, or null
    maxOutputTokens: null       # tokens, from the vendor's model page, or null
    capabilities:
      tools: true
      structuredOutput: true
      imageInput: true
      promptCache: true
    api:                        # how the review path calls it; a closed set. Optional: null when no vendor
                                # page states it, and the review path's default dialect applies
      outputTokenParam: MAX_TOKENS        # MAX_TOKENS | MAX_COMPLETION_TOKENS
      temperature: true
      reasoningEffort: null               # null | low | medium | high
    pricing:                    # millicents per 1M tokens ($1.00 = 100000)
      input: …                  # required
      output: …                 # required
      cacheRead: …              # charged as CACHED_INPUT; optional
      cacheWrite: …             # charged as CACHE_WRITE (the 5-minute rate); optional
      cacheWrite1h: …           # recorded, not charged yet; optional
      reasoning: …              # charged as REASONING; optional
      notBilled: []             # INPUT/OUTPUT never; the others when the vendor says it does not bill them
      sourceUrl: https://…      # the page the prices were read from
      observedAt: 2026-01-01    # the day they were read
```

A token type with neither a rate nor a `notBilled` mark is unpriced. That is allowed: a harness run
that would report that type is refused before it starts, with `model_pricing_incomplete`.

The catalog has no free-form request parameters and no way to remove the output cap. Manual models
keep both.

## Rules

CI (`./gradlew :spire-llm-catalog:checkCatalog`) refuses a change that breaks any of these:

- The file parses strictly: no unknown field, no YAML anchors, aliases, tags or duplicate keys, at
  most 1 MiB.
- Every model's `provider` is listed; `modelId` and every alias are unique across the file.
- `input` and `output` are present. Every rate is a positive whole number of millicents, at most
  1,000,000,000. A `notBilled` type carries no rate.
- `sourceUrl` is HTTPS and `observedAt` is not in the future.
- **A model is never removed.** It leaves by `lifecycle.status: RETIRED`. A retired model stays
  priced, so a run that started before is still charged, but no new run uses it.
- **A changed price changes `observedAt`.**

## Known limits

- **One rate per token type.** Vendors that price long prompts higher are entered at their base (short-context)
  rate: a call above OpenAI's 272K-input threshold, or above Claude Haiku 5.5's 100,000-token threshold, is
  charged at the base rate and so under-charged, until the catalog can price tiers.
- **`cacheWrite1h` is recorded, not charged.** The ledger has one cache-write type, charged at `cacheWrite`.
- **`api` is optional.** When it is null the review path's default applies (`max_tokens`, `temperature` sent).
  Each value written comes from the vendor's API reference, listed with the prices it was reviewed with.

## Where prices come from

A person, or an agent preparing the pull request, reads each price from the vendor's own pricing page
and types it in, with that page as `sourceUrl` and the day as `observedAt`. No price is recalled from
memory, scraped or fed in automatically. A model whose price no vendor page states stays out of the
file; an operator may add it as a manual model.

A price is money on every install, so `ai-catalog/` has a code owner.

## Pointing an install at another copy

The app reads `SPIRE_LLM_CATALOG_URL` (default: this file on `master`, through
`raw.githubusercontent.com`) every `SPIRE_LLM_CATALOG_REFRESH_INTERVAL` (default `6h`; `off` reads
only at startup). Point it at a fork, a tag, a commit or an internal server; the URL must be HTTPS.
`none` switches remote loading off, and the copy built into the orchestrator image is used.

A file that fails validation, or a URL that cannot be read, never replaces the catalog in use; the
dashboard shows the last error beside it.

## Versions

The path carries the format's major version. Within `v1` only additive changes are allowed: the app
ignores fields it does not know, so an install that is not upgraded keeps reading a newer file. A
change an older app cannot read goes to a new path (`v2/…`), and `v1` is kept as long as a supported
release reads it.
