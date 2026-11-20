# AI model catalog from a public file

**Status:** Design, revision 2 (after an independent review on 2026-11-20). **Date:** 2026-11-20.
**Prior art:** the monorepo design `monorepo/docs/superpowers/specs/2026-09-12-ai-model-catalog-as-code-design.md`
(YAML in git, merged by CI, published to a private bucket, reconciled into tables). This design keeps its idea and
drops most of its machinery; section 4 says why.

## 1. Why

Adding, changing or removing a chat model is manual work today, in two unrelated places:

- **Models and prices** are typed by an operator into Settings → LLM (`llm_model`, `llm_model_rate`, V9/V30/V74).
  Nothing seeds them. One new model is a name that must match the harness slug exactly, 4 or 5 rates in millicents
  per 1M tokens, and four API-dialect fields for the review path. Every install repeats this, and prices drift.
- **What a harness can run** is a label baked into the agent image (`dev.codespire.agent.models`). Codex derives it
  from `codex debug models`; Claude Code reads the hand-kept `deploy/agent/claude-code/models.json`.

The schema has no context window, no max output and no capability flags, and it cannot hold a 1-hour cache-write
price (docs/UNVERIFIED.md, the 3b entry).

**Goal:** one public, reviewed file describes the chat models, their capabilities and their prices. Every install
reads it. A model or price change is a merged PR, with no migration, no release and no operator typing.

## 2. Goals, non-goals, anti-goals

Goals:

1. One YAML file in this repository, `ai-catalog/v1/catalog.yaml`, is the published catalog. Merging to `master`
   publishes it; there is no publish job.
2. The app reads it from a **configurable URL**. Our file is the default, but no install is forced to use it: an
   operator may point the URL at their own copy (a fork, a tag, a commit, an internal server) or switch remote
   loading off.
3. Catalog models are **read-only in the app**. They change only through a catalog update.
4. Operators may still **add, edit and delete manual models**. A manual model with the same name as a catalog model
   **wins**; the UI marks both.
5. Prices cover every token type the ledger knows: input, cache read, cache write, output, reasoning. The file also
   records the 1-hour cache-write price.
6. A new Claude model is one catalog PR: Claude Code's model offer comes from the catalog, with no image rebuild.

Non-goals:

- Image, voice and embedding models. Chat only.
- Price history. The ledger snapshots the rate on every charge (ADR-023), so a price change never rewrites a past
  charge and no temporal price table is needed.
- Any automatic price lookup: no feed, no bot, no scraping of vendor pages. A person, or an AI agent preparing a
  PR, enters every price by hand, in the catalog file or as a manual model in the app. Who enters it does not matter.
- New providers or harnesses. The catalog describes models of the existing three vendors (`openai`, `anthropic`,
  `gemini`), used by the existing harnesses (`codex`, `claude_code`). A new vendor or harness still needs code.
- API keys. Keys, `llm_provider` rows and the per-repository model choice stay in the app.
- Making a switched-off model stop **API reviews**. Today `isDisabled` guards only factory paths
  (`FixRunDispatcher:293`, `RunFunding:65`, `RunResource:233`, `WorkPreparationSweep:274`); the API review path
  checks only `isPriceable` (`WorkerLlmCredentials:97`, `LlmProviderRegistry:269`). This slice keeps that: a
  `RETIRED` catalog model stops harness runs, exactly as a switched-off manual model does.

Anti-goals (a failure even if it works):

- A price nobody can trace. Every catalog price carries `sourceUrl` and `observedAt`: where the person read it and
  when, so a reviewer can check it. Nothing is recalled from memory (no-synthetic-data rule).
- A bad or unreachable catalog that stops reviews or runs. The app keeps the last good catalog.
- An operator's manual model silently replaced or deleted by a catalog update.
- A call or run charged `UNKNOWN` because the catalog changed or had not loaded yet.
- A remote file that can change what the app sends to a vendor beyond the fields listed in section 5.

## 3. Constraints found in the code

- Production readers of models go through four places (searched 2026-11-20): `LlmModelPricer.pricingFor`
  (`llm_model` by name, then `LlmModelRateRepository`), `LlmModelRegistry` (`list`, `isDisabled`, `profileForName`,
  CRUD), and `ReviewProjection` (a SQL subquery at line 1628 and `llmTypeFor` at 2341, both reading `llm_model.type`
  by name). No foreign key points at `llm_model.id` except `llm_model_rate`. `llm_provider.model`,
  `factory_run.model` and `llm_charge.model` are plain strings.
- `LlmModelRegistry.list()` feeds a server guard (`BuildDefaults.java:142`, "an enabled model with this name exists")
  and four UI readers under `preparationKeys.llmModels()` (`BuildStep.tsx:59`, `ReviewHarnessFields.tsx:27`,
  `WorkItemPreparation.tsx:39`, `SettingsLlmProviders.tsx:99`). `LlmModelView` (`api.ts:1068` and its Java record)
  requires `id` and `createdAt`.
- `HarnessCatalogues` (`get`, `admit` at 168-177, `offered`) is the harness model check, used by `RunFunding.fund:68`,
  `BuildDefaults.checkAgainstTheHarness:207`, `WorkPreparationSweep:280` and `RepositoryBuildResource.modelsOf:93-97`.
  An image with no label reports `NO_CATALOGUE`, and `admit` then refuses nothing.
- Pricing happens at **charge time**, after the money is spent (`RunCharges.java:119`, `ResultSaga.java:873`).
- `llm_model.name` is globally unique, so the catalog's `modelId` must be unique across providers too.
- The ledger token types are `INPUT`, `CACHED_INPUT`, `CACHE_WRITE`, `OUTPUT`, `REASONING`
  (`contract/review/TokenType.java`), each `RATED` or `NOT_BILLED`. There is one `CACHE_WRITE` type; Claude Code's
  `cache_creation_input_tokens` is not split by cache lifetime (`ClaudeCodeEvents.java:162`).
- `HarnessTokenReport` says which types a harness reports (Codex all five, Claude Code all but `REASONING`).
  `LlmModelPricer.unpricedTypes` refuses a run whose model lacks a rate or a `NOT_BILLED` mark for any of them.
- `extra_params` reach the vendor request through LangChain4j `customParameters` (`LangChain4jLlmProvider.java:209`)
  and have no key validation (`LlmModelRegistry.writeExtra:310` only checks the map serialises).
  `outputTokenParam: NONE` sends no output cap (`LangChain4jLlmProvider.java:201-205`).
- Claude Code CLI 2.1.319 has no command that lists its models (checked 2026-11-20 in
  `spire-agent-claude-code:latest`), and it runs any Anthropic API model by `--model <name>`.
- The test profile already switches network-driven refreshes off (`application.yml:547`,
  `harness-catalogue-interval: "off"`).

## 4. Approaches

1. **Same tables, a `source` column.** A loader diffs the catalog against `llm_model`/`llm_model_rate` and inserts,
   updates or disables rows; `UNIQUE(name)` becomes unique per source and every lookup prefers `MANUAL`. Close to
   today's code, but the loader is the hard part: the monorepo design spends most of its length on it.
2. **Separate catalog tables.** The loader replaces `catalog_model`/`catalog_model_rate`; manual tables do not change;
   a resolver merges. Clear ownership, but a second schema and still a loader.
3. **Catalog in memory, last good file in one row.** *(Chosen.)* The app stores the last good file in a single row,
   parses it into memory, and one resolver merges it with the manual tables, manual first. No diffing, no removal
   rules, no reconciliation: replacing the catalog is one row write. The cost is that SQL that joins `llm_model`
   directly does not see catalog models; there are two such places (section 3) and both move to the resolver.

Chosen by the operator on 2026-11-20 because it removes the hardest part of the monorepo design, and the ledger
already makes price history unnecessary.

Hosting: the operator chose a direct raw GitHub URL over GitHub Pages or a bucket. No publish step, no keys.

## 5. The catalog file

Path: `ai-catalog/v1/catalog.yaml`. The schema is the Java records and validator in the module `spire-llm-catalog`,
run strictly by CI and leniently by the app, so there is one definition, not a JSON Schema beside it. One file,
because three vendors' chat models are a short list and a direct URL serves one file.

**Versioning.** The path carries the major version. A change an older app cannot read goes to a new path
(`ai-catalog/v2/…`); `v1` keeps being maintained as long as a supported release reads it. Within `v1`, only
**additive** changes are allowed: the app ignores fields it does not know, CI's schema stays strict. So an install
that is not upgraded never freezes on a newer file.

```yaml
providers:
  - code: anthropic             # one of openai | anthropic | gemini (the app's vendor strings)
    displayName: Anthropic
    pricingUrl: https://…       # the vendor page prices are read from
models:
  - modelId: claude-sonnet-5-5  # = llm_model.name = the harness slug; unique across the file
    provider: anthropic
    displayName: Claude Sonnet 5.5
    aliases: []                 # other names the same model is reported under (section 7.1)
    lifecycle:
      status: ACTIVE            # ACTIVE | DEPRECATED | RETIRED
      deprecationDate: null
      retirementDate: null
      successor: null
    contextWindow: …            # tokens
    maxOutputTokens: …          # tokens
    capabilities:
      tools: true
      structuredOutput: true
      imageInput: true
      promptCache: true
    api:                        # the review path's dialect: a closed set, no free-form parameters
      outputTokenParam: MAX_TOKENS        # MAX_TOKENS | MAX_COMPLETION_TOKENS (NONE is manual-only)
      temperature: true
      reasoningEffort: null               # null | low | medium | high: the one level the API review path sends
    pricing:                    # millicents per 1M tokens
      input: …                  # required
      output: …                 # required
      cacheRead: …              # ledger CACHED_INPUT; optional
      cacheWrite: …             # ledger CACHE_WRITE, the 5-minute rate; optional
      cacheWrite1h: …           # recorded, not charged yet (section 7.5); optional
      reasoning: …              # ledger REASONING; optional
      notBilled: []             # token types the vendor states it does not bill; their fields are absent
      sourceUrl: https://…
      observedAt: 2026-11-20
```

`…` marks a value entered by hand in the first catalog PR. **This document holds no prices.**

A token type with neither a rate nor a `notBilled` mark is **unpriced**. That is honest, not an error: a model whose
harness reports that type is refused before a run with `model_pricing_incomplete`, as today. Example: Claude Code
reports no `REASONING`, so an Anthropic model needs no `reasoning` price. Where a vendor's page says reasoning tokens
are billed as output tokens, `reasoning` equals `output` and `sourceUrl` is that page.

The catalog has **no `extraParams`** and no `NONE` output parameter. Both stay available to manual models. A remote
file therefore cannot put arbitrary keys into a vendor request or remove the output cap of a paid call.

Rules CI enforces (the app repeats all but the last two before accepting a file, section 6.2):

- Every model's `provider` is a listed provider; `modelId` and every alias are unique across the whole file.
- `input` and `output` are present and positive. Every rate is a positive integer at most
  `LlmModelPricer.MAX_RATE_MILLICENTS_PER_MILLION`. A type in `notBilled` has no rate; `INPUT` and `OUTPUT` may not
  be in it. `sourceUrl` is HTTPS; `observedAt` is a date not in the future.
- The file is at most 1 MiB. YAML anchors, aliases, custom tags and duplicate keys are refused.
- **CI only: a model is never removed.** Compared with `master`, every `modelId` still exists; a model leaves by
  `lifecycle.status: RETIRED`. A retired model stays priceable, so a run that started before retirement is charged.
- **CI only: a changed price changes `observedAt`.**

All catalog models are `METERED`. A subscription run is still priced unmetered by the run (`priceUnmetered`), not by
the model.

`ai-catalog/**` gets a CODEOWNERS entry: a price is money on every install.

## 6. Loading it

### 6.1 Configuration

| Key | Default | Meaning |
|---|---|---|
| `spire.llm.catalog.url` | `https://raw.githubusercontent.com/artyomsv/code-spire/master/ai-catalog/v1/catalog.yaml` | Where to read the catalog. `none` turns remote loading off. Must be HTTPS. |
| `spire.llm.catalog.refresh-interval` | `6h` | How often the remote file is read. `off` reads only at startup. |

Env vars `SPIRE_LLM_CATALOG_URL` / `SPIRE_LLM_CATALOG_REFRESH_INTERVAL`, listed in `.env.example`,
`deploy/.env.example` and the Helm values. The URL has a default on purpose: the catalog is public, not a
credential, and an install should work with no setup. This is an exception to the "no defaults" rule for
environment-specific values, and is recorded as one. The `%test` profile sets `url: none`.

### 6.2 Startup, before anything prices

The first model question (a price, a profile, a harness offer) never sees an empty catalog. The resolver loads
synchronously on its first use, whichever consumer asks first, so no startup ordering with Kafka is needed:

1. Read `llm_catalog_state.document` synchronously at startup and parse it.
2. If there is none (a new database), parse the bundled copy (section 6.5). It is held in memory; it is written to
   the row only as described in 6.5.
3. Only then is the question answered. The HTTP fetch runs after startup, off the startup thread.

If the stored document no longer parses (an app downgrade across an additive field is harmless, since unknown fields
are ignored; a corrupt row is not), the bundled copy is used and an ERROR is logged.

### 6.3 Refresh

```
every refresh-interval, and on "Reload now" (each orchestrator instance)
  GET url  (If-None-Match: stored etag, only when the stored url equals the configured one;
            10 s timeout; body cap 1 MiB; redirects refused; HTTPS only)
    304                   → last_checked_at, last_success_at; done
    error / non-2xx       → last_checked_at, last_error = status or exception class only (never body text)
    200 → parse + validate (section 5)
      invalid             → last_error = the rule that failed; keep the current catalog
      valid, same sha256  → etag, last_checked_at, last_success_at; done
      valid, new          → one transaction: SELECT … FOR UPDATE on llm_catalog_state;
                            write document, sha256, etag, url, source='remote', loaded_at, last_success_at,
                            last_change (section 6.4); clear last_error;
                            pg_notify('llm_catalog_changed', sha256) on commit
```

**Every instance follows a change at once.** Each orchestrator instance `LISTEN`s on `llm_catalog_changed` (the
`WorkItemChangeListener` pattern: its own session, `LISTEN` committed before an initial read). On a notice whose sha
differs from its own, it re-reads the row and swaps its in-memory catalog. "Reload now" on one instance therefore
reaches all of them, and two instances never price with different files for longer than a notice takes. A dropped
listener session re-reads the row when it reconnects.

The in-memory catalog is one immutable object swapped atomically; a pricing call reads one snapshot from start to end.

### 6.4 Audit

Each accepted new file records `last_change` (JSONB): old sha, new sha, and per model the added, retired and
re-priced entries with old and new rates. The orchestrator logs one INFO line with the sha pair and counts. The
dashboard header shows the last change (section 8). An unexpected repricing is therefore visible on the install, not
only in git.

### 6.5 The bundled copy

The orchestrator build copies `ai-catalog/v1/catalog.yaml` onto its classpath.

- **New database:** the bundled copy is written to the row with `source='bundled'`, then the URL is tried. A remote
  file always replaces a bundled one.
- **`url: none`:** the bundled copy is the catalog, **in memory only**; the row is not written. So during a rolling
  upgrade an old pod restarting cannot overwrite a newer pod's catalog: each pod uses its own image's copy.
- **The URL changes:** the stored document stays in use until the new URL loads a valid file.

### 6.6 Schema change (one migration)

```sql
CREATE TABLE llm_catalog_state (
  id              SMALLINT PRIMARY KEY CHECK (id = 1),
  document        TEXT,          -- the exact validated YAML; public data, not encrypted
  sha256          VARCHAR(64),
  etag            VARCHAR(256),
  source          VARCHAR(16) CHECK (source IN ('bundled','remote')),
  url             TEXT,          -- the URL the document came from
  loaded_at       TIMESTAMPTZ,
  last_checked_at TIMESTAMPTZ,
  last_success_at TIMESTAMPTZ,   -- last 200 or 304 from the URL
  last_error      TEXT,
  last_change     JSONB
);
```

`llm_model` and `llm_model_rate` do not change. They now hold only manual models. A new attention row
`LLM_CATALOG_STALE` appears when remote loading is on and `last_success_at` is older than three refresh intervals, or
the newest check failed validation; it names the URL and `last_error`. (Time since the last success, not a failure
counter, so several instances cannot reach the threshold faster than one.)

## 7. Using it

### 7.1 One resolver

New `LlmModelCatalog` (orchestrator) answers every question by model name, **manual first, then catalog**:
type, pricing (mode + rates per token type), enabled, parameter profile, display name, source. The readers in
section 3 call it:

- `LlmModelPricer.pricingFor` → resolver. Pricing, `isPriceable` and `unpricedTypes` keep their logic.
- `LlmModelRegistry.isDisabled`, `profileForName` → resolver. CRUD stays on the manual tables only.
- `ReviewProjection`: the `llm_type` SQL subquery is removed; the type is filled in Java from the resolver after the
  query, like `llmTypeFor` (which also moves to the resolver).

**A database fault never falls through to the catalog.** If reading the manual tables fails, the answer is the same
as today: `Pricing.UNKNOWN` from the pricer and `CatalogueUnavailable` from `isDisabled`. Falling through would price
a shadowed model at catalog rates or start a run on a model the operator switched off. Each of the two fall-through
guards is mutation-checked.

**Shadowing is complete, not per field.** A manual model replaces the catalog entry in every answer, including a
manual model that is switched off or has incomplete rates. "Use catalog" (section 8) is the way back.

**Aliases.** The resolver answers for a catalog entry's `modelId` and every alias. The ledger keeps recording the name
the call or run reported, as today. A manual model whose name equals a catalog `modelId` **or any of its aliases**
shadows the **whole** entry: every name of that entry then resolves to the manual model's answers.

**Enabled.** A catalog model is enabled when `ACTIVE` or `DEPRECATED`, and disabled when `RETIRED`. Retired stays
priceable (section 5). Operators cannot switch a catalog model off; a manual model of the same name can shadow it.

### 7.2 Two lists, two endpoints

- `GET /api/llm-models` stays the **effective** list: one row per name, the winner only, with a new `source`
  (`MANUAL` | `CATALOG`) field. `BuildDefaults.java:142` and the three pickers keep working unchanged and never see a
  duplicate name.
- `GET /api/llm-catalog` is the **display** list for Settings: catalog state, every catalog entry with a `shadowed`
  flag, and the full catalog detail (capabilities, context window, prices, sources, lifecycle).
- `LlmModelView` gets `source`, and `id` and `createdAt` become nullable, in the Java record **and** in `api.ts`
  together (CLAUDE.md: the two sides are independent; a field on one arrives `undefined` on the other). Every UI use
  of `id` (`SettingsLlmProviders.tsx:280,293` key and delete, `factoryTestSetup.tsx:32`) is checked.

### 7.3 Manual models

- Add, edit and delete work as today.
- Saving a manual model whose name matches a catalog name or alias is allowed; the form shows "This overrides the
  catalog model with the same name."
- `llm_provider.model` may name a manual or a catalog model. The delete/rename guard (`ModelInUseException`) still
  applies to manual models, **except "Use catalog"**: deleting a manual model that shadows an enabled, fully priced
  catalog entry skips the guard, because the name still resolves after the delete.
- The vendor type set (`openai`, `anthropic`, `gemini`) moves into one shared constant used by `LlmModelResource`,
  `LlmProviderResource`, `HarnessCredentialResource` and the catalog validator.

### 7.4 Harnesses

The catalog has **no harness section**. A harness's offer comes from a per-harness **offer source** inside
`HarnessCatalogues` (`get`, `admit`, `offered`), so every caller in section 3 keeps one entry point:

- **Codex: the image label**, as today (`codex debug models`, with thinking levels). The resolver prices each model;
  one the resolver cannot price is refused at preparation with `model_pricing_incomplete`, as today.
- **Claude Code: the resolver.** The offer is every enabled resolver model of type `anthropic` (catalog entries not
  `RETIRED` and not shadowed, plus enabled manual `anthropic` models), with no thinking levels, as today. `admit`
  refuses any other name with `model_not_run_by_harness`, so a `gpt-*` name can no longer reach `claude -p`.
  `/build/options` returns this list, never `NO_CATALOGUE`, for Claude Code.
- `harness_catalogue` keeps the image pin (`pinned_image`) for both harnesses. A Claude Code image with no label is
  valid.
- `deploy/agent/claude-code/models.json` is deleted; `build-claude-code.sh` builds with no model label (header comment
  updated). `release.yml`'s "Model catalogue" step and the `docker.yml` equivalent stop requiring the label on the
  Claude Code image.
- `docs/factory/AGENT-IMAGE-CONTRACT.md` (line 209) and `spire-agent-image`'s `Clauses.java:101` (`DECLARED` includes
  `MODELS`): the `models` label becomes optional for a harness whose offer comes from the catalog.

Codex slugs that the catalog does not list, and a dated id a harness reports under another name, are charged `UNKNOWN`
unless the slug is a `modelId` or alias. The first catalog PR lists every slug in today's Codex label that has a
price.

### 7.5 What the pricer charges

| Ledger type | Catalog field |
|---|---|
| `INPUT` | `input` |
| `CACHED_INPUT` | `cacheRead` |
| `CACHE_WRITE` | `cacheWrite` |
| `OUTPUT` | `output` |
| `REASONING` | `reasoning` |

`cacheWrite1h` is in the file and on screen but not charged: the ledger has one `CACHE_WRITE` type and no harness
reports the cache lifetime. Charging it needs a new token type and a harness that reports it; that is a follow-up,
and the docs/UNVERIFIED.md entry stays open.

### 7.6 Attention

No new "not in catalog" row. The existing `MODEL_NOT_PRICEABLE`, `BUILD_MODEL_NOT_PRICEABLE` and
`REPOSITORY_LLM_MODEL_NOT_PRICEABLE` (`AttentionQueries.java:135-218`) already fire through the pricer, so they cover
a model the catalog stopped pricing once the pricer uses the resolver. Their text changes by source: for a catalog
model, "update the catalog or add a manual model"; for a manual model, "set rates in Settings → LLM → Models", as
today. They keep skipping seat-paid setups, as `RunFunding:65` and `BuildDefaults:136` do. Plus `LLM_CATALOG_STALE`
(section 6.6).

## 8. Dashboard

Settings → LLM → Models reads `/api/llm-catalog` and `/api/llm-models` and shows both sources:

```
Catalog: loaded 2026-11-20 14:02 from raw.githubusercontent.com/…/v1/catalog.yaml · 23 models · sha 3f9c2ab…
         last change: 2 re-priced, 1 added   [Reload now]
         [last check failed 14:08: HTTP 503 — using the catalog from 14:02]

Model               Provider   Source    Status
gpt-6-astra         openai     Manual    Active · overrides catalog   [Use catalog]
gpt-6-astra         openai     Catalog   Ignored · a manual model has this name
claude-sonnet-5-5   anthropic  Catalog   Active
claude-haiku-4-5    anthropic  Catalog   Deprecated · successor claude-haiku-5-5
my-local-model      openai     Manual    Active
```

- Catalog rows have a lucide icon (no emoji) and open a read-only detail view: capabilities, context window, max
  output, every price with its source link and observed date, lifecycle. No Edit or Delete.
- Manual rows keep Edit and Delete. A manual row that shadows a catalog entry has "Use catalog" (section 7.3).
- "Reload now" (`POST /api/llm-catalog/refresh`, admin) refreshes and, through the notice, every instance.
- Reads go through React Query keys declared in `api.ts`, per the live-data design; a catalog change invalidates
  `preparationKeys.llmModels()` and the catalog key.

## 9. CI

A workflow step on PRs touching `ai-catalog/**` validates `ai-catalog/v1/catalog.yaml` with the same validator class
the orchestrator uses (run through a small Gradle task, so the two cannot disagree), in strict mode, plus the two
CI-only rules in section 5 against the `master` version of the file.

## 10. Tests

- `%test` uses `url: none` and a test-only bundled catalog with `TEST-`-prefixed model names, so existing tests that
  assert "uncatalogued → UNKNOWN / `model_unknown`" keep their meaning. Tests never call GitHub.
- Every saga test fake a new path reaches overrides the new resolver methods (CLAUDE.md gotcha).
- Mutation-checked guards: manual-first, no fall-through on a database fault, Claude Code `admit` refusal, "Use
  catalog" guard skip, startup load before consumers, redirect refusal, unknown-field tolerance.

## 11. Rollout

1. Ship the code with `ai-catalog/v1/catalog.yaml` holding the models the harness images offer today and the models
   the dev stack uses, each price entered by hand with its source and date. A model with no price anyone can source
   stays out of the file; an operator may add it as a manual model.
2. Existing installs: their manual models keep working and, where names match, shadow the catalog. The operator
   uses "Use catalog" model by model. Nothing is deleted automatically.
3. docs: a new ADR (catalog from a public file; amends ADR-018's "operator-entered, never hardcoded"), an
   `ai-catalog/README.md` (how to add a model, where prices come from, the never-remove rule), the operator guide
   for `SPIRE_LLM_CATALOG_URL`, the AGENT-IMAGE-CONTRACT change, and docs/UNVERIFIED.md entries for what only a live
   run can prove (raw GitHub's `ETag` behaviour, a refresh across two instances).

## 12. Left to the builder

- Package and class names beyond `LlmModelCatalog`; whether the resolver caches the manual tables.
- The YAML parser (Jackson YAML is the expected choice) and how strict and lenient parsing are switched.
- Exact attention-row wording.
- How the Gradle task exposes the validator to CI.

## 13. Calls made in this design (not discussed with the operator)

- Default URL points at `master` (`v1` path), with `none` to switch off; refresh every 6 h; changes reach every
  instance through `pg_notify`.
- The image carries a bundled copy, written to the row only on a new database; with `url: none` it is memory-only.
- Catalog models cannot be switched off in the app; `RETIRED` disables them but keeps them priceable; a model is
  never removed from the file.
- `RETIRED` (like a switched-off manual model) stops harness runs, not API reviews.
- The catalog has no `extraParams` and no `NONE` output parameter; manual models keep both.
- `cacheWrite1h` is recorded but not charged in this slice.
- Optional price fields: a type with no rate is unpriced, and the existing completeness check refuses it where a
  harness reports it.
- A price change in a PR must update `observedAt`; `ai-catalog/**` has a code owner.
- No new "not in catalog" attention row; the three existing pricing rows cover it, plus `LLM_CATALOG_STALE`.
