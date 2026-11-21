# An LLM provider is a connection; the model is chosen where it is used

**Status:** Design, revision 2 (after an independent review on 2026-11-20). **Date:** 2026-11-20.
**Branch:** `feat/ai-catalog` (follows `2026-11-20-ai-catalog-design.md`, same PR).

## 1. Why

Registering a provider today requires a model, and the model must already be priced
(`LlmProviderResource.java:165,177`, `LlmProviderRegistry.java:73,108`, `V8__llm_provider.sql:14` `NOT NULL`). With
models coming from the catalog, the natural order is: connect a vendor account first, then pick a model from that
vendor's models. The operator chose (2026-11-20) to take the model **off the provider entirely**: a provider is only a
connection, and the model is chosen where it is used.

## 2. Decisions

- `llm_provider` keeps `name, type, base_url, api_key, temperature, max_tokens, enabled, is_default`. The `model`
  column is dropped.
- **Global default:** the default provider (`is_default`, unchanged) plus a **default model**, stored as the app setting
  `llm.default-model` (`app_setting`, V7).
- **Per repository:** `repository.review_llm_provider_id` (V105, unchanged) plus a new `repository.review_llm_model`.
  Both null (use the global default) or both set. The V109 rule (provider and harness are exclusive) extends to the
  model column.
- The key check needs no model (`LlmKeyValidator` sends `GET {baseUrl}/models`), so registering and checking a provider
  works with no model.
- The wire record `LlmCredential` (`spire-contract`) does **not** change: `WorkerLlmCredentials` fills its `model` from
  the choice instead of from the provider. The worker and the charge ledger are untouched.
- **Not in this change:** the catalog's providers do not gain an `apiBaseUrl`. A base URL is where the API key is sent,
  and the catalog is fetched remotely; the in-app defaults (`SettingsLlmProviders.tsx:55-59`) stay.

## 3. The vendor rule

A chosen model's `type` (from the resolver: catalog `provider`, or the manual model's `type`) should equal its
provider's `type`.

- **Enforced on every new choice** (default-model save, repository save): 400 with the two types named.
- **Not a runtime refusal.** An existing deployment can run an `openai`-type provider against a compatible gateway with
  a model of another type, and the migration copies such pairs. At runtime a mismatch is an attention warning
  (`LLM_MODEL_NOT_FOR_PROVIDER`, naming the default or the repository), and the call proceeds as today.
- **Drift is closed at its sources:**
  - A provider's `type` cannot change after create (`LlmProviderRegistry.update`: 400 "create a new provider for
    another vendor").
  - A manual model's `type` cannot change while the model is in use (the in-use guard, section 6, also covers a type
    change).
  - "Use catalog" (`CatalogModelViews.stillResolvesAfterDelete`) requires the catalog entry to have the manual model's
    type; otherwise the in-use guard applies.
  - A catalog refresh that changes an entry's vendor is caught by the attention warning.

## 4. Setting the default atomically

The default provider and the default model are written together, so no review sees a provider of one vendor with a
model of another.

- `PUT /api/llm-providers/{id}/default` takes `{ "model": string }` and, in one transaction, sets `is_default` and
  `llm.default-model`. It refuses (400) a model that is unknown, of another vendor, or not priceable.
- `PUT /api/settings/llm-default-model` `{ "model": string | null }` changes only the model, for the current default
  provider; same checks; `null` clears it. With no default provider it is 409.
- "A default provider exists" means `is_default AND enabled` everywhere (guard, 409, attention), as `resolveDefault`
  and `LLM_DEFAULT_MISSING` already define it.
- Creating a provider never sets a default model by itself. The first provider still becomes the default provider
  (today's rule), and the operator then picks its model. Creating a provider with `isDefault: true` while a default
  provider exists requires `defaultModel` in the same request (400 otherwise) and goes through the same atomic write. Until then `LLM_DEFAULT_MODEL_MISSING` (blocking) says so; reviews are refused
  `NO_DEFAULT_MODEL`, exactly as reviews are refused `NO_DEFAULT_PROVIDER` before any provider exists.
- Deleting or un-defaulting the default provider clears `llm.default-model` in the same transaction.
- `AppSettingRepository` gains `delete(key)` and a throwing read `require(key)` (or `getOrThrow`) used on the review
  path; the existing `get` swallows `SQLException` and would turn a database hiccup into a permanent refusal.
- Both writes call `attention.refresh()`.

## 5. Where the model is decided

`WorkerLlmCredentials.resolveForReview` and the public `resolveDefault` (used by `ResultSaga:271`,
`ConversationSaga:158`):

- The repository names a provider → its `review_llm_model`, read in the same query as `review_llm_provider_id`.
- It names none → the default provider and `llm.default-model` (throwing read).
- New refusal in `DefaultLlm.Refusal`: `NO_DEFAULT_MODEL`, with `detail()` and `note()` text. `MODEL_NOT_PRICEABLE` and
  `REPOSITORY_PROVIDER_OFF` stay. A refusal never falls back to another provider or model.
- A chosen model that is switched off or `RETIRED` still runs, as today for API reviews (`isPriceable` ignores
  `enabled`); attention warns `LLM_MODEL_SWITCHED_OFF` naming the default or repository, so the operator picks another.

## 6. Model-in-use guard

`LlmModelRegistry.countProvidersUsing` (`:278`) becomes "uses of a model name":

- `llm.default-model`, counted **only while a default provider exists**,
- `repository.review_llm_model`,
- each compared against every name the model answers to (manual name; for a catalog entry, `modelId` and aliases).

It guards delete, rename and (new) a type change of a manual model. Messages name the uses ("the default review model",
"repository X") instead of "LLM provider(s)" (`LlmModelRegistry.java:168,264`). The "Use catalog" skip stays for
delete only, with the same-type condition (section 3). The guard is not serialised against a concurrent setting or
repository write; the runtime path (priced / attention) is the backstop, and this is stated in the code.

`requirePriceableModel` moves from the provider save to the two model saves.

## 7. Migrations (V118, V119)

Two scripts, so the code can move between them and every intermediate commit still runs:

**V118** (additive):
1. `ALTER TABLE repository ADD COLUMN review_llm_model VARCHAR(255)`.
2. `UPDATE repository r SET review_llm_model = p.model FROM llm_provider p WHERE r.review_llm_provider_id = p.id`.
3. `INSERT INTO app_setting (key, value, updated_at) SELECT 'llm.default-model', model, now() FROM llm_provider WHERE
   is_default ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()`.

**V119** (after the code reads the new sources):
4. Drop and re-create the CHECK `repository_review_harness_whole` (V109:14): **keep V109's two arms verbatim** (the
   effort column may be null; the `btrim … <> ''` and `pay_with IN (…)` conditions stay) and only add
   `review_llm_model IS NULL` to the harness arm, and to the other arm: `review_llm_provider_id` and `review_llm_model`
   both `IS NULL`, or both `IS NOT NULL` with `btrim(review_llm_model) <> ''`.
5. `ALTER TABLE llm_provider DROP COLUMN model`.

Upgrade notes (docs/HISTORY.md, Upgrading): stop every orchestrator instance before the upgrade (an old instance
selects and inserts `model`); there is no rollback without a database backup.

## 8. REST and dashboard

- `LlmProviderInput`, `LlmProviderView`, `LlmProviderConfig`: no `model`. `api.ts` the same.
- `RepositoryInput` / `RepositoryView`: `reviewLlmModel` beside `reviewLlmProviderId`. Every back-compatible
  constructor and `RepositoryRegistry.normalize` (`:155-158`) go through a wither that enumerates the components once
  (CLAUDE.md gotcha). `normalize` trims and blanks-to-null the model; `requireReviewModel` enforces section 2 and 3.
  A PUT naming a provider with no `reviewLlmModel` is 400, never a silent clear.
- `GET /api/settings/llm-default-model` → `{ "provider": id | null, "model": string | null }`, its own query key in
  `api.ts`, written with `writeLive`/`invalidateLive` after either PUT (live-data convention).
- Settings → LLM → Providers: no model field or column; the checkbox becomes "Use as the default provider". A
  **Default review model** select lists the enabled models (catalog and manual) of the default provider's vendor from
  `/api/llm-models`; with no default provider it is disabled and says "Choose a default provider first". Making
  another provider the default opens the same select and sends the model with the default change (section 4).
- Repository → Settings → "Review with": choosing a provider shows a model select of that vendor's models and resets
  the model when the provider changes; Save requires both. "Default" shows the default provider and model. A stored
  model that is switched off or unlisted stays visible and selected, labelled, as the provider select does for a
  switched-off provider. The dirty check covers the model.

## 9. Tests

- Migration, with the staged-Flyway pattern of `RepositorySchemaMigrationTest`: a default provider with a model and a
  repository naming another provider end with the setting and the column filled, the CHECK accepting both shapes and
  refusing a provider without a model, and `llm_provider.model` gone. `RepositoryReviewHarnessSchemaTest` updated.
- `WorkerLlmCredentials`: default provider + model; repository provider + model; `NO_DEFAULT_MODEL`; a setting read
  fault throws (redelivery), not a refusal; a vendor mismatch still runs.
- Default change: `PUT …/default` with a cross-vendor model writes both or neither; without a model is 400; delete of
  the default provider clears the setting.
- In-use guard: default model and repository uses (also through an alias); a type change of an in-use manual model is
  refused; "Use catalog" of a different-type entry is refused; the setting is not counted with no default provider.
- Provider `type` change refused.
- Attention: `LLM_DEFAULT_MODEL_MISSING`, `LLM_MODEL_NOT_FOR_PROVIDER`, `LLM_MODEL_SWITCHED_OFF`; the two existing
  model rows (`MODEL_NOT_PRICEABLE`, `REPOSITORY_LLM_MODEL_NOT_PRICEABLE`) read the new sources;
  `repositoryLlmProviderRows` no longer joins `p.model`.
- `LlmProviderModelGuardTest` and `LlmProviderRegistryPricingGuardTest` are rewritten against the two model saves.
- Every other test building `LlmProviderInput` with a model, raw `llm_provider` SQL with `model`, REST bodies with a
  model, UI tests, and the e2e driver (`SpireDriver.registerLlmProvider` sets the default with the model;
  `resetRegistries` clears the default before deleting the model) are updated.

## 10. Docs

`docs/SMOKE-TEST.md` (K-4..K-6 `:1151-1190`, Mode C step 2 `:311`), `docs/DATA-MODEL.md:138-147`, `docs/DECISIONS.md`
(ADR-018 section, `:2583`, and the catalog ADR-051 on this branch), `docs/HISTORY.md` Upgrading, `docs/UNVERIFIED.md`.

## 11. Not in scope

- More than one model per provider for different jobs (review vs reply): both use the same choice, as today.
- Harness repositories: unchanged (`review_harness_model` already exists).
- `apiBaseUrl` in the catalog (section 2).

## 12. Calls made in this revision (not discussed with the operator)

- The vendor rule is enforced on new choices and is a warning, not a refusal, at runtime (keeps gateway setups that
  work today working).
- A provider's type is fixed after create.
- A switched-off or retired chosen model still runs, with a warning, as API reviews do today.
- The default provider and model change together in one request.
- `apiBaseUrl` is dropped from this change.
