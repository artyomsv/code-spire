# LLM Provider Without Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A provider is only a connection; the review model is a global default (with the default provider) or a
per-repository choice (with the repository's provider).

**Architecture:** V118 adds the new homes for the model (`app_setting` `llm.default-model`,
`repository.review_llm_model`) and copies today's data. The code then reads and writes only the new homes. V119, last,
drops `llm_provider.model` and tightens the CHECK. `WorkerLlmCredentials` keeps filling the unchanged wire record
`LlmCredential`.

**Tech Stack:** Java 25, Quarkus 3.38.3, Flyway, plain JDBC, React + React Query + vitest.

**Spec:** `docs/superpowers/specs/2026-11-20-llm-provider-without-model-design.md` (revision 2). Cited as "spec §n".
Builds on the catalog work already on this branch (`llm/catalog/LlmModelCatalog`, `llm/CatalogModelViews.java`, V117).

## Global Constraints

- **Never run builds, tests, linters, type checks on this host.** Write the test first, then the code; "verify" =
  re-read the test against the code. **CI runs once, when the operator pushes the finished branch**; nothing is pushed
  per task. Each task should still leave the code compiling and consistent, which the task order below allows.
- Commit locally after each task on `feat/ai-catalog`. **Never push.** Imperative, ≤72-char first line, body for
  non-trivial changes, no AI/model/vendor attribution, no `Co-Authored-By`, no closing keywords. Pair each new guard
  with the single test that fails without it, in the commit body.
- Java 4-space, explicit types, records, methods ≤30 lines; TS 2-space, `interface`. lucide-react icons only. No new
  user-visible "Code Spire". Keep each file's existing line endings.
- Setting key exactly `llm.default-model` (constant `LlmDefaultModel.KEY`).
- **"A default provider exists" = `is_default AND enabled`**, everywhere.
- **Test isolation for the global setting:** every test class that deletes `llm_provider` rows, or asserts the setting
  is absent, also runs `DELETE FROM app_setting WHERE key = 'llm.default-model'` in `@BeforeEach`; a test that writes
  it deletes it in `@AfterEach`. Known provider-wiping classes: `AttentionQueriesTest:60`, `WorkerLlmCredentialsTest:69`
  and every other `grep -rln "DELETE FROM llm_provider" spire-orchestrator/src/test`.
- **Hand-built beans and fakes:** for every class that gains a dependency, grep tests for `new <Class>(` and anonymous
  subclasses and wire or override them in the same task (CLAUDE.md saga-fake gotcha).
- **Mutation checks must discriminate:** a vendor-check test uses a model that is known, enabled and priceable but of
  another type, and asserts the reason text, so no other guard can keep it green.

## Review Focus

1. **Changing the default provider to another vendor** (also through create with `isDefault`): provider and model are
   written together, or neither. → Task 3.
2. **A database fault reading the setting** on the review path: throws (redelivery), never `NO_DEFAULT_MODEL`. → Task 2.
3. **Deleting the default provider**: the setting is cleared, so its model can be deleted. → Tasks 3, 4.
4. **An existing gateway setup with a mismatched vendor**: still runs, with a warning. → Tasks 2, 5.
5. **A harness repository with a null effort** survives V119's CHECK. → Task 8.

---

### Task 1: V118 — the new homes, filled

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V118__llm_model_new_homes.sql` (spec §7 steps 1-3)
- Create: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmModelNewHomesMigrationTest.java`
  (staged-Flyway pattern of `RepositorySchemaMigrationTest`: migrate to V117, seed, migrate to V118)

- [ ] **Step 1: Write the failing tests** — `theDefaultProvidersModelBecomesTheSetting` (default `TEST-p1` /
  `TEST-m1`), `aRepositoryKeepsItsProvidersModel` (`TEST-p2` / `TEST-m2`), `noDefaultProviderWritesNoSetting`,
  `anExistingSettingIsOverwritten`.
- [ ] **Step 2: Write the migration.**
- [ ] **Step 3: Commit** — `Add the new homes for the review model`

---

### Task 2: The review path reads the new homes

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmDefaultModel.java` — this task only:
  `static final String KEY = "llm.default-model"`; `Optional<String> current()` (throwing read)
- Modify: `settings/AppSettingRepository.java` — add `Optional<String> require(String key)` (throws
  `SettingReadFailed extends RuntimeException` on `SQLException`), `void delete(String key)`, and
  `void delete(Connection c, String key) throws SQLException` (for callers inside their own transaction)
- Modify: `llm/WorkerLlmCredentials.java` — `resolveForReview` and public `resolveDefault` take the model from
  `RepositoryRegistry.reviewLlmChoice` / `LlmDefaultModel.current()`; `priced(cfg, model, workspace)`,
  `pack(cfg, model, workspace)`. `SettingReadFailed` propagates. A vendor mismatch is not checked here.
- Modify: `llm/DefaultLlm.java` — `Refusal.NO_DEFAULT_MODEL`; `detail()` / `note()`: "No default review model is
  selected. Choose one in Settings → LLM."
- Modify: `repository/RepositoryRegistry.java` — `record ReviewLlmChoice(UUID providerId, String model)`,
  `Optional<ReviewLlmChoice> reviewLlmChoice(UUID repositoryId)` (one query, both columns);
  `reviewLlmProviderId` delegates to it
- Test: `WorkerLlmCredentialsTest` (fixtures: write the setting / repository column with raw SQL), new
  `WorkerLlmCredentialsSettingFaultTest` (hand-built `WorkerLlmCredentials` with every collaborator wired by hand and an
  `AppSettingRepository` whose `DataSource` throws), new `AppSettingRepositoryRequireTest` (a throwing `DataSource`:
  `require` throws, `get` returns empty — proves the two differ)

- [ ] **Step 1: Write the failing tests** — `theDefaultProviderUsesTheDefaultModel`,
  `aRepositoryProviderUsesTheRepositoryModel`, `noDefaultModelIsRefusedNoDefaultModel`,
  `aSettingReadFaultThrowsAndIsNotARefusal` (**mutation-check:** the throwing read), `aVendorMismatchStillRuns`
  (anthropic provider + an openai catalog model), `aSwitchedOffChosenModelStillRuns`, `anUnpriceableModelIsStillRefused`.
- [ ] **Step 2: Implement.** Nothing reads `llm_provider.model` on the review path after this task.
- [ ] **Step 3: Commit** — `Read the review model from the setting or the repository`

---

### Task 3: Providers lose the model; the default changes atomically

**Files:**
- Modify: `llm/LlmProviderInput.java` (drop `model`; add `String defaultModel`, used only with `isDefault` on create),
  `llm/LlmProviderView.java`, `llm/LlmProviderConfig.java` (drop `model`)
- Modify: `llm/LlmProviderRegistry.java`:
  - SQL without `model`; `requirePriceableModel` removed.
  - `update` refuses a `type` change: `IllegalArgumentException("Provider type cannot change; create a new provider for
    another vendor.")`.
  - `void setDefault(Connection c, UUID id) throws SQLException` (package-private, no own transaction) replaces the
    public `setDefault(UUID)`.
  - `delete` of the default provider, and `create` that clears an old default, run
    `appSettings.delete(c, LlmDefaultModel.KEY)` on the **same connection** inside their transaction — except create
    with `isDefault` + `defaultModel`, which writes the new model instead (via `LlmDefaultModel.write(c, model)`).
  - The registry depends on `AppSettingRepository`, **not** on `LlmDefaultModel` (no cycle).
- Modify: `llm/LlmDefaultModel.java` — add: `void set(String model)` (`NoDefaultProvider` when no default provider;
  `ChoiceRefused` on a bad choice; `null` clears), `void setDefaultProvider(UUID providerId, String model)` (one
  transaction: `registry.setDefault(c, id)` + `write(c, model)`), `static void write(Connection c, String model)`,
  `String check(String providerType, String model)` → null or `unknown_model` / `model_not_for_provider` /
  `model_not_priceable` (resolver type vs provider type; `LlmModelRegistry.isPriceable`). Exceptions:
  `static final class ChoiceRefused extends RuntimeException` (message: the reason and the two types) and
  `static final class NoDefaultProvider extends RuntimeException`. Every write calls `attention.refresh()`.
- Modify: `llm/LlmProviderResource.java` — `validate` drops the model checks; `PUT /{id}/default` body
  `{ "model": string }` → 400 when missing; create with `isDefault` while a default exists requires `defaultModel`
  (400); maps `ChoiceRefused` → 400, `NoDefaultProvider` → 409, the type-change `IllegalArgumentException` → 400.
- Create: `settings/LlmDefaultModelResource.java` — `GET /api/settings/llm-default-model` →
  `{ "provider": string | null, "model": string | null }` with the same role as `GET /api/repositories` (the
  repository "Default" label reads it); `PUT` `{ "model": string | null }` `@RolesAllowed("spire-admin")`.
- Modify: `OperatorAuthTest` (`CONFIGURATION_READS` ~43-57: add the GET).
- Tests: new `LlmDefaultModelTest`, `LlmDefaultModelResourceTest`; rewrite `LlmProviderModelGuardTest` and
  `LlmProviderRegistryPricingGuardTest` against `LlmDefaultModel.set` / `setDefaultProvider`; update
  `LlmProviderResourceTest`, `LlmKeyCheckTest`, `CapRefusalTest`, `WorkerLlmCredentialsTest:55` (`setDefault`), and
  every test building `LlmProviderInput` with a model: `LlmProviderCheckTest`, `LlmModelResourceTest`,
  `LlmModelRegistryRenameGuardTest`, `CatalogReadersTest`, `AttentionRepositoryLlmProviderTest`, `RunResourceTest`,
  `RepositoryResourceTest`, `RepositoryReviewHarnessSchemaTest`, and raw `llm_provider … model` SQL
  (`AttentionQueriesTest` and `grep -rn "llm_provider" spire-orchestrator/src/test | grep -i model`)

- [ ] **Step 1: Write the failing tests**
- `settingTheDefaultProviderWritesProviderAndModelTogether`.
- `aCrossVendorDefaultChangeWithAMatchingModelSucceeds`; `aMismatchedModelChangesNothing` (known, priceable, other
  type; both rows unchanged; reason `model_not_for_provider`). **Mutation-check:** the vendor check.
- `defaultWithoutAModelIs400`.
- `createWithIsDefaultWhileADefaultExistsNeedsAModel` (400 without; with → both written).
  **Mutation-check:** the create requirement.
- `deletingTheDefaultProviderClearsTheSetting`. **Mutation-check:** the clear.
- `settingTheModelWithNoDefaultProviderIs409`; `aDisabledDefaultProviderCountsAsNone`.
- `putNullClearsTheModel`; `anUnknownModelIs400`; `anUnpriceableModelIs400`.
- `theFirstProviderBecomesDefaultWithNoModel`.
- `providerTypeCannotChange`.
- [ ] **Step 2: Implement.** After this task no main code reads or writes `llm_provider.model` except
  `LlmModelRegistry` and `AttentionQueries` (Tasks 4, 5); the column still exists, so they keep working.
- [ ] **Step 3: Commit** — `Change the default provider and model in one request`

---

### Task 4: Model-in-use guard counts the new uses

**Files:**
- Modify: `llm/LlmModelRegistry.java` — `countProvidersUsing` (~278) → `List<String> usesOf(Connection c, String name)`:
  "the default review model" (setting equals the name **and** a default provider exists), "repository {name}" (per
  `review_llm_model`). Delete, rename and a manual model's `type` change refuse with `ModelInUseException` naming the
  uses (messages at ~168, ~264). Add a comment where the guard is checked: it is not serialised against a concurrent
  setting or repository write; the runtime path and attention are the backstop (spec §6).
- Modify: `llm/CatalogModelViews.java` — `stillResolvesAfterDelete` (~41-47) also requires the catalog entry's
  `provider` to equal the manual model's `type`.
- Tests: the delete/rename guard tests (fixtures become setting / repository uses), plus the cases below.

- [ ] **Step 1: Write the failing tests** — `aModelUsedAsTheDefaultCannotBeDeleted`,
  `…canBeDeletedWhenNoDefaultProviderExists`, `aModelUsedByARepositoryCannotBeRenamed`,
  `anInUseManualModelCannotChangeType` (**mutation-check:** the type-change guard),
  `useCatalogOfADifferentVendorEntryIsRefused` (**mutation-check:** the same-type condition),
  `theMessageNamesTheUses`.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Commit** — `Guard models used as the default or by a repository`

---

### Task 5: Attention rows

**Files:**
- Modify: `attention/AttentionQueries.java` — `defaultProviderModel()` (~234) reads the setting;
  `repositoryLlmProviderRows` (~207-230) reads `r.review_llm_model`, no `p.model`. New rows:
  `LLM_DEFAULT_MODEL_MISSING` (`Severity.BLOCKING`: a default provider exists, setting absent);
  `LLM_MODEL_NOT_FOR_PROVIDER` (`Severity.WARNING`: default or repository pair of different types, naming which);
  `LLM_MODEL_SWITCHED_OFF` (`Severity.WARNING`: the chosen model resolves disabled). Each new row names the model and
  where it is chosen.
- Tests: `AttentionQueriesTest`, `AttentionRepositoryLlmProviderTest`.

- [ ] **Step 1: Write the failing tests** — positive and negative for each new row; `MODEL_NOT_PRICEABLE` and
  `REPOSITORY_LLM_MODEL_NOT_PRICEABLE` fire from the new sources.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Commit** — `Warn when the review model is missing or mismatched`

---

### Task 6: Repository review model

**Files:**
- Modify: `repository/RepositoryInput.java`, `repository/RepositoryView.java` — add `String reviewLlmModel` after
  `reviewLlmProviderId`; every shorter constructor passes `null` for it explicitly; add a wither
  `withReviewLlm(UUID providerId, String model)` that enumerates every component once, used by `normalize` and every
  view/input rebuild site.
- Modify: `repository/RepositoryRegistry.java` — `normalize` (~155-158) uses the wither, trims and blanks-to-null the
  model; new `requireReviewModel`: provider and model together (else 400), none with a harness, and
  `LlmDefaultModel.check(providerType, model)` → 400 with the reason; UPDATE (~100), INSERT (~124), `view()` (~271) carry
  the column; map a CHECK violation (23514) to 400, not 500.
- Tests: `RepositoryResourceTest`, `RepositoryRegistry…Test`.

- [ ] **Step 1: Write the failing tests** — `aProviderWithoutAModelIs400` (the old-UI case; **mutation-check:** the
  pairing check), `aModelOfAnotherVendorIs400` (known, priceable, other type; reason asserted), `aProviderAndModelSaveAndRead`,
  `clearingBothFallsBackToTheDefault`, `aHarnessWithAModelIs400`, `normalizeKeepsTheModel` (a non-null model survives
  `normalize`; fails if the wither drops the component).
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Commit** — `Choose the review model per repository`

---

### Task 7: Dashboard

**Files:**
- Modify: `spire-ui/src/api.ts` (`LlmProviderView` / `LlmProviderInput` lose `model`, input gains optional
  `defaultModel`; `setDefaultLlmProvider(id, model)`; `interface LlmDefaultModelView { provider: string | null;
  model: string | null }`, `llmDefaultModelKey`, `fetchLlmDefaultModel`, `putLlmDefaultModel`)
- Modify: `spire-ui/src/components/SettingsLlmProviders.tsx` (no model field or column; checkbox "Use as the default
  provider", which then also asks for the model; "Make default" asks for the model and sends both)
- Create: `spire-ui/src/components/SettingsLlmDefaultModel.tsx` (select of enabled models of the default provider's
  vendor from `/api/llm-models`; disabled with "Choose a default provider first")
- Modify: `spire-ui/src/components/repositories/repositoriesApi.ts`, `RepositorySettings.tsx` (draft, dirty check,
  `choose()` resets the model on provider change), `RepositoryReviewProvider.tsx` (model select; default label shows
  provider and model; a stored disabled or unlisted model stays visible and labelled), `RepositoryForm.tsx`,
  `factory/factoryFixtures.ts`
- Fixtures/tests: `SettingsLlmProviders.form.test.tsx`, new `SettingsLlmDefaultModel.test.tsx`,
  `RepositoryRegistryPage.test.tsx`, `RepositoryDetail.test.tsx`, `RegisterPrDialog.form.test.tsx`,
  `WorkPolicies.test.tsx`, `SettingsProviders.form.test.tsx`, and every other fixture found by
  `grep -rln "reviewLlmProviderId\|model:" spire-ui/src` that builds these types

- [ ] **Step 1: Write the failing tests** — `the provider form has no model field`, `the default model select lists
  only the default provider's vendor`, `…is disabled without a default provider`, `making another provider default asks
  for its model and sends both`, `review with: choosing a provider shows its vendor's models and resets the model`,
  `review with: save requires a model`, `a stored switched-off model stays selected and labelled`.
- [ ] **Step 2: Implement** (reads via `useQuery` keys in `api.ts`; writes via `writeLive` / `invalidateLive`; tests
  via `renderWithQuery`).
- [ ] **Step 3: Commit** — `Pick the review model after the provider`

---

### Task 8: V119 — drop the old column, tighten the CHECK

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V119__llm_provider_drop_model.sql` (spec §7 steps 4-5;
  V109's arms verbatim plus the model conditions)
- Create: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmProviderDropModelMigrationTest.java`
  (staged: migrate to V118, seed, migrate to V119)
- Modify: `RepositoryReviewHarnessSchemaTest` (new CHECK cases)

- [ ] **Step 1: Write the failing tests** — `aHarnessRowWithANullEffortSurvives` (seeded before V119), `theProviderModelColumnIsGone`,
  `theCheckRefusesAProviderWithoutAModel`, `…aModelWithoutAProvider`, `…aBlankModel`, `…aHarnessWithAModel`,
  `acceptsBothShapes`.
- [ ] **Step 2: Write the migration.** Confirm with grep that no main code mentions `llm_provider` with `model` any more.
- [ ] **Step 3: Commit** — `Drop the model from the LLM provider table`

---

### Task 9: e2e driver and docs

**Files:**
- Modify: `spire-e2e/.../spire/SpireDriver.java` (`registerLlmProvider(name, baseUrl)`, then `setDefaultLlmModel(model)`
  after the e2e model is registered; `resetRegistries` clears the default model before deleting models),
  `Environment.java`, `SpireDriverTest.java`, `LlmMockContractTest.java`
- Modify: `docs/SMOKE-TEST.md` (K-4..K-6 ~1151-1190; Mode C step 2 ~311), `docs/DATA-MODEL.md` (~138-147),
  `docs/DECISIONS.md` (ADR-018 at ~2879: amendment; ADR-051: a note), `docs/HISTORY.md` (Upgrading: stop every
  orchestrator instance; no rollback without a backup; the default model is copied from the default provider; a
  repository's model from its provider), `docs/UNVERIFIED.md` (the migrations on a real database; the gateway mismatch
  warning)

- [ ] **Step 1:** update the driver and its tests (nightly e2e tier; never run here).
- [ ] **Step 2:** update the docs.
- [ ] **Step 3: Commit** — `Register e2e providers without a model and document the change`

## Self-review notes

- Order: V118 adds; Tasks 2-7 move every reader and writer; V119 removes. Each commit leaves the code consistent with the
  schema it runs on.
- Spec §3 runtime warning → Task 2 `aVendorMismatchStillRuns`, Task 5. Spec §4 atomic default, create rule, delete
  clear, throwing read, `is_default AND enabled` → Tasks 2-3. Spec §5 switched-off model runs → Task 2. Spec §6 guard,
  comment, same-type "Use catalog" → Task 4. Spec §7 two migrations → Tasks 1, 8. Spec §8 wither and old-UI 400 →
  Task 6. Spec §10 docs → Task 9.
