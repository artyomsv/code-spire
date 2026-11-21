# AI Model Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chat models, capabilities and prices come from one public YAML file read from a configurable URL; manual
models in the app still work and win by name; Claude Code's model offer comes from the catalog.

**Architecture:** A new framework-free module `spire-llm-catalog` parses and validates the file. The orchestrator keeps
the last good file in one row (`llm_catalog_state`), holds it in memory as an immutable snapshot, and answers every
model question through one resolver, `LlmModelCatalog`, manual first. A scheduled fetch replaces the file; `pg_notify`
tells every instance.

**Tech Stack:** Java 25, Quarkus 3.38.3, Flyway, Jackson (`jackson-dataformat-yaml` 2.22.4), JDK `HttpClient`,
Postgres `LISTEN/NOTIFY`, React + React Query + vitest.

**Spec:** `docs/superpowers/specs/2026-11-20-ai-catalog-design.md` (revision 2). Read it first; this plan cites its
sections as "spec §n".

## Global Constraints

- **Never run builds, tests, linters, `gradlew`, `npm test`, `npx tsc` on this host** (user rule
  `no-local-builds`). Write the test first, write the code, and leave verification to CI (`ci.yml`: `testFast`,
  `testServices`, UI `tsc` + `vitest` + build). Every "verify" step below means: re-read the test and the code
  against each other, then move on. Report "not verified: CI has not run".
- Commit after each task on branch `feat/ai-catalog`, locally. **Do not push.** Commit messages: imperative, ≤72
  chars first line, a body for non-trivial changes, no AI/model/vendor attribution, no `Co-Authored-By`, no closing
  keywords.
- Java: 4-space indent, explicit types (no `var` in new code), records for data, methods ≤30 lines, classes ≤300
  lines, no `Optional` parameters, catch specific exceptions. TS/YAML/JSON: 2-space indent; `interface` for object
  shapes.
- Money is millicents per 1M tokens (`long`). Max rate `LlmModelPricingValidator.MAX_RATE_MILLICENTS_PER_MILLION`
  (`1_000_000_000L`, line 35). Define it in `spire-llm-catalog` as `CatalogLimits.MAX_RATE_MILLICENTS_PER_MILLION` and
  keep the validator's constant as an alias (`= CatalogLimits.MAX_RATE_MILLICENTS_PER_MILLION`), because existing
  tests reference it.
- **Hand-built beans and fakes.** Any production class that gains an injected field in this plan
  (`LlmModelPricer`, `LlmModelRegistry`, `ReviewProjection`, `AttentionQueries`, `HarnessCatalogues`) must have every
  hand construction and anonymous subclass in tests found with
  `grep -rnE "new (LlmModelPricer|LlmModelRegistry|ReviewProjection|AttentionQueries|HarnessCatalogues)\(" spire-orchestrator/src/test`
  and wired or overridden in the same task (CLAUDE.md "saga test fake" gotcha). Known sites: `LlmModelPricerFailureTest:26`,
  `LlmModelNotBilledRatesTest:174`, `LlmModelResourceInfraFaultTest:34`, `WorkRunChargeProof:39`,
  `ReviewProjectionConversationFindingTest:482`, `CapAttentionTest:123,146`, `HarnessSagaFakes:181`,
  `ResultSagaPricingTest:475,543`, `ResultSagaRetryTest:533`, `ResultSagaVerdictFixture:189`, `RunFundingFixtures`,
  `ClaudeBuildsAreRefusedTest`.
- **Shared catalog state in tests.** `llm_catalog_state` is one row and the snapshot is application-wide. Unit tests of
  load, fallback and fault build `LlmModelCatalog` **by hand** with fake collaborators. A `@QuarkusTest` that writes
  the row resets it to the migration's state (all nulls) in `@AfterEach` and calls `catalog.replace` with the test
  bundled snapshot. `%test` sets `spire.llm.catalog.listener: off` so the listener does not start except in its own
  test, which starts it by hand.
- Vendor strings are exactly `openai`, `anthropic`, `gemini`.
- **No prices from memory.** Test data uses names prefixed `TEST-` and obviously synthetic rates (`1`, `2`, `3`).
  Real prices appear only in Task 10, each read from a vendor page in that session.
- No emoji in UI; icons from `lucide-react`. Do not add the product name "Code Spire" to any new user-visible text.
- Config default URL: `https://raw.githubusercontent.com/artyomsv/code-spire/master/ai-catalog/v1/catalog.yaml`;
  `refresh-interval` default `6h`; `%test` profile `url: none`.
- Every guard listed in a task's "Mutation-check" line: after the code is written, the implementer states which
  single test would fail if that line were removed. (Running the mutation is CI-side; record the pairing in the
  commit body.)

## Review Focus

1. **A catalog file with an unknown field** (newer `v1` additive change): the app must accept it and ignore the
   field; CI's strict mode must refuse it. → Task 1 tests both modes.
2. **A database fault while reading manual models:** the answer must be UNKNOWN / `CatalogueUnavailable`, never the
   catalog entry. → Task 4.
3. **A manual model whose name equals a catalog alias:** the whole catalog entry is shadowed, every name. → Task 4.
4. **Claude Code asked to run a non-Anthropic name** (`gpt-…`): refused `model_not_run_by_harness`. → Task 7.
5. **First price call right after a restart, before any fetch:** priced from the stored or bundled catalog, never
   UNKNOWN for a catalog model. → Task 4 (lazy load) and Task 6.

---

### Task 1: Module `spire-llm-catalog` — parse and validate

**Files:**
- Create: `spire-llm-catalog/build.gradle.kts`, `spire-llm-catalog/LICENSE` (the Apache-2.0 text of
  `spire-llm/LICENSE`, but **line 1 exactly** `Code Spire — spire-llm-catalog`; `ModuleLicensingIsDeclaredTest`)
- Modify: root `Dockerfile` — `COPY spire-llm-catalog/build.gradle.kts spire-llm-catalog/` after the `spire-llm`
  line (~38), alphabetical (`ImageBuildSeesEveryModuleTest`)
- Create in `spire-llm-catalog/src/main/java/dev/codespire/llmcatalog/`: `CatalogDocument.java`,
  `CatalogProvider.java`, `CatalogModel.java`, `CatalogLifecycle.java`, `CatalogCapabilities.java`,
  `CatalogApiDialect.java`, `CatalogPricing.java`, `CatalogParser.java`, `CatalogValidator.java`,
  `CatalogLimits.java`, `Vendors.java`
- Modify: `settings.gradle.kts` (add `include("spire-llm-catalog")` after `spire-llm`), `build.gradle.kts`
  (`fastTestModules` add `"spire-llm-catalog"` after `"spire-llm"`), `LICENSING.md` (a table row: cell 1
  `spire-llm-catalog`, cell 2 `Apache-2.0`, matching the existing rows' format)
- Test: `spire-llm-catalog/src/test/java/dev/codespire/llmcatalog/CatalogParserTest.java`,
  `CatalogValidatorTest.java`

**Interfaces:**
- Produces:
  - `record CatalogDocument(List<CatalogProvider> providers, List<CatalogModel> models)`
  - `record CatalogProvider(String code, String displayName, String pricingUrl)`
  - `record CatalogModel(String modelId, String provider, String displayName, List<String> aliases,
    CatalogLifecycle lifecycle, Long contextWindow, Long maxOutputTokens, CatalogCapabilities capabilities,
    CatalogApiDialect api, CatalogPricing pricing)` with `boolean isRetired()` and `List<String> names()`
    (modelId first, then aliases)
  - `record CatalogLifecycle(Status status, LocalDate deprecationDate, LocalDate retirementDate, String successor)`,
    `enum Status { ACTIVE, DEPRECATED, RETIRED }`
  - `record CatalogCapabilities(boolean tools, boolean structuredOutput, boolean imageInput, boolean promptCache)`
  - `record CatalogApiDialect(OutputTokenParam outputTokenParam, boolean temperature, String reasoningEffort)`,
    `enum OutputTokenParam { MAX_TOKENS, MAX_COMPLETION_TOKENS }`
  - `record CatalogPricing(Long input, Long output, Long cacheRead, Long cacheWrite, Long cacheWrite1h,
    Long reasoning, List<String> notBilled, String sourceUrl, LocalDate observedAt)`
  - `final class CatalogParser { static CatalogDocument parse(String yaml, Mode mode) throws CatalogException;
    enum Mode { STRICT, LENIENT } }` — STRICT refuses unknown fields, LENIENT ignores them
  - `final class CatalogValidator { static List<String> problems(CatalogDocument doc, LocalDate today); }` — empty
    list = valid; each problem names the model id and the rule
  - `final class CatalogException extends Exception` (message is the rule that failed; never echoes file text)
  - `final class Vendors { static final Set<String> TYPES = Set.of("openai", "anthropic", "gemini"); }`
  - `final class CatalogLimits { MAX_BYTES = 1_048_576; MAX_RATE_MILLICENTS_PER_MILLION = 1_000_000_000L; }`

- [ ] **Step 1: Write the failing tests**

`CatalogParserTest`:
- `strictRefusesAnUnknownField` — a model with `futureField: 1` → `CatalogException` in STRICT.
- `lenientIgnoresAnUnknownField` — same text in LENIENT parses; the model is present.
- `refusesYamlAnchorsAndAliases` — `&a`/`*a` in the file → `CatalogException` in both modes.
- `refusesDuplicateKeys` — `modelId` twice in one model → `CatalogException`.
- `refusesAFileOverOneMebibyte` — 1_048_577 bytes → `CatalogException` whose message names the limit.
- `refusesCustomTags` — `!!java.lang.Object` → `CatalogException`.

`CatalogValidatorTest` (use `TEST-` names and rates 1/2/3, `today = 2026-11-20`):
- `aValidDocumentHasNoProblems`
- `inputAndOutputAreRequired` — missing `output` → one problem naming `TEST-a` and `output`.
- `aRateMustBePositiveAndAtMostTheCap` — `0` and `1_000_000_001` each → a problem.
- `aNotBilledTypeCarriesNoRate` — `notBilled: [CACHE_WRITE]` with `cacheWrite: 1` → a problem.
- `inputAndOutputCannotBeNotBilled`
- `unknownNotBilledTypeIsAProblem` — `notBilled: [FOO]`.
- `providerMustBeListedAndAKnownVendor` — `provider: mistral` → a problem.
- `namesAreUniqueAcrossModelIdsAndAliases` — alias of one model equals another's `modelId` → a problem.
- `sourceUrlMustBeHttps` and `observedAtMayNotBeInTheFuture` (`2026-11-21`).
- `reasoningEffortIsNullOrLowMediumHigh` — `xhigh` → a problem.

- [ ] **Step 2: Verify (CI-side)** — the tests reference types that do not exist yet; they fail to compile.

- [ ] **Step 3: Implement the module**

`build.gradle.kts`: `java-library`, toolchain 25, `implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")`,
`implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.4")`,
`implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.4")`, JUnit as in `spire-llm`.
Parser, in this order:
1. Size check on the UTF-8 bytes.
2. **Pre-scan with SnakeYAML's event API** (`new Yaml().parse(reader)`; SnakeYAML is already a transitive dependency of
   `jackson-dataformat-yaml`): refuse any `AliasEvent`, any event whose anchor is non-null, and any scalar/collection
   event with an explicit tag. Jackson's `YAMLParser` never runs SnakeYAML's composer, so `LoaderOptions` settings do
   **not** enforce these.
3. Bind with Jackson: `STRICT_DUPLICATE_DETECTION` on; `FAIL_ON_UNKNOWN_PROPERTIES` = (mode == STRICT).
Wrap every exception in `CatalogException` with a message that names the rule, not the content.

- [ ] **Step 4: Verify (CI-side)** — re-read each test against the code.

- [ ] **Step 5: Commit** — `Add the LLM catalog parser and validator module`

---

### Task 2: The catalog file, its CI checks and its code owner

**Files:**
- Create: `ai-catalog/v1/catalog.yaml` (providers only, `models: []` — Task 10 fills it), `ai-catalog/README.md`
- Create: `spire-llm-catalog/src/main/java/dev/codespire/llmcatalog/CatalogChangeRules.java`,
  `spire-llm-catalog/src/main/java/dev/codespire/llmcatalog/CheckCatalogMain.java`
- Modify: `spire-llm-catalog/build.gradle.kts` (task `checkCatalog`), `.github/workflows/ci.yml` (step in the
  `testFast` job), `.github/CODEOWNERS` (create if absent: `/ai-catalog/ @artyomsv`)
- Test: `spire-llm-catalog/src/test/java/dev/codespire/llmcatalog/RepositoryCatalogTest.java`,
  `CatalogChangeRulesTest.java`

**Interfaces:**
- Consumes: Task 1 parser/validator.
- Produces: `final class CatalogChangeRules { static List<String> problems(CatalogDocument base, CatalogDocument head); }`
  — "a `modelId` in base is missing in head" and "a model whose any rate field or `notBilled` changed kept the same
  `observedAt`".

- [ ] **Step 1: Write the failing tests**
- `RepositoryCatalogTest.theRepositoryCatalogIsStrictlyValid` — reads `../ai-catalog/v1/catalog.yaml` (path relative
  to the module dir), `parse(STRICT)`, `problems(doc, LocalDate.now())` is empty.
- `CatalogChangeRulesTest.removingAModelIsAProblem`, `retiringAModelIsFine`,
  `aPriceChangeWithTheSameObservedAtIsAProblem`, `aPriceChangeWithANewObservedAtIsFine`, `anEmptyBaseIsFine`.

- [ ] **Step 2: Verify (CI-side)** — fails to compile until `CatalogChangeRules` exists.

- [ ] **Step 3: Implement**
- `checkCatalog` Gradle `JavaExec` task runs `CheckCatalogMain` with args `<head> [<base>]`, with
  `workingDir = rootProject.projectDir` so relative paths resolve from the repository root; exit 1 with every problem
  printed when STRICT parse, validation or change rules fail; a missing base file means "no base".
- `ci.yml` step in the job named `fast` (`ci.yml:29`), before its `testFast` run: `git fetch --no-tags --depth=1 origin master`, then
  `git show origin/master:ai-catalog/v1/catalog.yaml > "$RUNNER_TEMP/base-catalog.yaml" || rm -f "$RUNNER_TEMP/base-catalog.yaml"`,
  then `./gradlew :spire-llm-catalog:checkCatalog --args="ai-catalog/v1/catalog.yaml $RUNNER_TEMP/base-catalog.yaml"`.
- `README.md`: the file format (spec §5), the never-remove and change-`observedAt` rules, that prices are entered by
  hand with `sourceUrl`/`observedAt`, how an operator points `SPIRE_LLM_CATALOG_URL` at their own copy, and the `v1`
  additive-only rule.

- [ ] **Step 4: Verify (CI-side)**
- [ ] **Step 5: Commit** — `Add the public catalog file and its CI checks`

---

### Task 3: Catalog state table and repository

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V117__llm_catalog_state.sql` (DDL exactly as spec §6.6,
  plus `INSERT INTO llm_catalog_state (id) VALUES (1);`)
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/catalog/LlmCatalogState.java`,
  `LlmCatalogStateRepository.java`
- Modify: `spire-orchestrator/build.gradle.kts` (`implementation(project(":spire-llm-catalog"))`)
- Modify: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkItemChangeCoverageTest.java` (decide
  `llm_catalog_state`: not a work-item read)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/catalog/LlmCatalogStateRepositoryTest.java`

**Interfaces:**
- Produces:
  - `record LlmCatalogState(String document, String sha256, String etag, String source, String url,
    Instant loadedAt, Instant lastCheckedAt, Instant lastSuccessAt, String lastError, String lastChangeJson)`
  - `LlmCatalogStateRepository` (`@ApplicationScoped`):
    `LlmCatalogState read()`;
    `void storeDocument(StoredDocument doc)` with `record StoredDocument(String document, String sha256, String etag,
    String source, String url, String lastChangeJson)` — one transaction, `SELECT … FOR UPDATE`, sets `loaded_at`,
    `last_checked_at`, `last_success_at` to now, clears `last_error`, and runs
    `SELECT pg_notify('llm_catalog_changed', ?)` with the sha in the same transaction;
    `void recordUnchanged(String etag)` (304 or same sha: `etag`, `last_checked_at`, `last_success_at`);
    `void recordFailure(String reason)` (`last_checked_at`, `last_error`).

- [ ] **Step 1: Write the failing test** — `storeDocumentWritesEveryColumnAndNotifies` (a `LISTEN` on a second
  connection receives the sha), `recordFailureKeepsTheDocument`, `recordUnchangedMovesLastSuccess`.
- [ ] **Step 2: Verify (CI-side)**
- [ ] **Step 3: Implement** with plain JDBC like `LlmModelRateRepository`.
- [ ] **Step 4: Verify (CI-side)**
- [ ] **Step 5: Commit** — `Store the last good LLM catalog in one row`

---

### Task 4: The resolver `LlmModelCatalog`

**Files:**
- Create in `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/catalog/`: `CatalogSnapshot.java`,
  `ResolvedModel.java`, `ModelSource.java`, `ManualModels.java`, `BundledCatalog.java`, `LlmModelCatalog.java`
- Modify: `spire-orchestrator/build.gradle.kts` — `processResources` copies `../ai-catalog/v1/catalog.yaml` to
  `llm-catalog/catalog.yaml` on the classpath
- Create: `spire-orchestrator/src/test/resources/llm-catalog-test/catalog.yaml` (one provider each, models
  `TEST-cat-a` (anthropic, aliases `[TEST-cat-a-dated]`), `TEST-cat-o` (openai), `TEST-cat-retired` (RETIRED), rates
  1/2/3)
- Modify: `spire-orchestrator/src/main/resources/application.yml` — `spire.llm.catalog.url`,
  `spire.llm.catalog.refresh-interval`, `spire.llm.catalog.bundled-resource` (default `llm-catalog/catalog.yaml`);
  `%test`: `url: none`, `bundled-resource: llm-catalog-test/catalog.yaml`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/catalog/LlmModelCatalogTest.java`

**Interfaces:**
- Consumes: Task 1 `CatalogDocument`, Task 3 repository.
- Produces:
  - `enum ModelSource { MANUAL, CATALOG }`
  - `record ResolvedModel(String name, String type, String label, ModelSource source, PricingMode pricingMode,
    Map<TokenType, ModelRate> rates, boolean enabled, ModelParamProfile profile, CatalogModel catalogEntry)` —
    `catalogEntry` null for manual
  - `final class CatalogSnapshot` (immutable): `static CatalogSnapshot of(CatalogDocument doc, String sha256)`,
    `Optional<CatalogModel> byName(String name)` (modelId or alias), `List<CatalogModel> models()`, `String sha256()`
  - `ManualModels`: `Optional<ResolvedModel> byAnyName(Connection c, Collection<String> names) throws SQLException`
    (moves the `llm_model` + `llm_model_rate` read from `LlmModelPricer.pricingFor` and the profile read from
    `LlmModelRegistry.profileForName`; `WHERE name = ANY(?)`; when two manual rows match one catalog entry, the row
    whose name equals the **asked** name wins, else the one equal to `modelId`, else the first alias in catalog
    order); `Set<String> allNames(Connection c) throws SQLException`. `resolve(name)` asks with
    `entry.names()` when a catalog entry has that name, else with `List.of(name)`.
  - `LlmModelCatalog` (`@ApplicationScoped`):
    - `Optional<ResolvedModel> resolve(String name)` — throws `CatalogueReadFailed` on a manual-table read fault
    - `List<ResolvedModel> effective()` — one per name, winner only, sorted by type then name
    - `List<CatalogRow> catalogRows()` with `record CatalogRow(CatalogModel model, boolean shadowed)`
    - `CatalogSnapshot snapshot()` — loads lazily on first call; `synchronized` first load, then a `volatile`
      reference. When `spire.llm.catalog.url` is `none`: the bundled resource only, the row is ignored (spec §6.5).
      Otherwise: the stored row document, else the bundled resource.
    - Collaborators are constructor-injectable (`ManualModels`, `LlmCatalogStateRepository`, a
      `Supplier<String> bundledText`, the URL string) so unit tests build it by hand.
    - `void replace(CatalogSnapshot next)` — atomic swap
    - `static final class CatalogueReadFailed extends RuntimeException` (thrown on `SQLException` from manual reads)
  - Catalog-entry mapping: `pricingMode = METERED`; rates map `INPUT←input`, `CACHED_INPUT←cacheRead`,
    `CACHE_WRITE←cacheWrite`, `OUTPUT←output`, `REASONING←reasoning`, `notBilled` → `ModelRate.notBilled()`, absent →
    no entry; `enabled = !isRetired()`; profile: `outputTokenParam`, `supportsTemperature = temperature`,
    `reasoningEffort`, `extraParams = Map.of()`.

- [ ] **Step 1: Write the failing tests** (hand-built `LlmModelCatalog` with a fake `ManualModels` and fake state
  repository for the load, fallback and fault cases; manual rows `TEST-…` created and deleted per test where a real
  database is used)
- `urlNoneUsesTheBundledCopyEvenWhenTheRowHoldsADocument`.
- `theLazyLoadReadsTheRowOnlyOnce` — the fake repository counts reads; two calls → one read. **Mutation-check:** the
  cached reference (a discriminating test, unlike "loads at all").
- `aCatalogModelResolvesByModelIdAndByAlias`
- `aManualModelWinsOverTheCatalogModelOfTheSameName` — source MANUAL, manual rates.
- `aSwitchedOffManualModelStillShadows` — `enabled=false`, source MANUAL.
- `aManualModelNamedLikeAnAliasShadowsTheWholeEntry` — `resolve("TEST-cat-a")` returns the manual model too.
- `aDatabaseFaultNeverFallsThroughToTheCatalog` — a `ManualModels` stub throwing `SQLException` →
  `CatalogueReadFailed`, not the catalog entry. **Mutation-check:** the catch that rethrows.
- `aRetiredModelIsDisabledButPriced` — `enabled=false`, `rates` has INPUT and OUTPUT.
- `effectiveListsEachNameOnce` — shadowed catalog entry absent; `catalogRows()` marks it `shadowed=true`.
- `theFirstCallLoadsTheStoredDocumentElseTheBundledOne` — fake repository with an empty row → bundled `TEST-cat-o`
  resolves; with a stored document naming `TEST-stored` → that resolves and `TEST-cat-o` does not.
- `aStoredDocumentThatNoLongerParsesFallsBackToTheBundledCopy`.
- [ ] **Step 2: Verify (CI-side)**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify (CI-side)**
- [ ] **Step 5: Commit** — `Resolve models from manual rows first, then the catalog`

---

### Task 5: Route every reader through the resolver

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/LlmModelPricer.java` (`pricingFor` →
  `catalog.resolve`; `CatalogueReadFailed` → `Pricing.UNKNOWN` with the existing ERROR log)
- Modify: `.../llm/LlmModelRegistry.java` (`isDisabled`: `CatalogueReadFailed` → `CatalogueUnavailable`; unknown →
  false; `profileForName` → resolver; `list()` → `catalog.effective()` mapped to views; `delete(id)` skips the
  `ModelInUseException` guard when `snapshot().byName(name)` returns a `CatalogModel` that is not retired and has
  `input` and `output` — computed from the `CatalogModel` itself, **not** through `resolve`, which would still return
  the manual row being deleted)
- Modify: `.../llm/LlmModelView.java` (add `String source`; `id` and `createdAt` nullable — catalog rows null),
  `.../llm/LlmModelResource.java`, `.../llm/LlmProviderResource.java`,
  `.../factory/HarnessCredentialResource.java` (use `Vendors.TYPES`), `.../llm/LlmModelPricingValidator.java`
  (use `CatalogLimits`)
- Modify: `.../readmodel/ReviewProjection.java` (remove the `llm_type` subquery at ~1628; after the result set
  closes, collect the distinct model names, resolve each once, and fill the type in Java; a `CatalogueReadFailed`
  gives a null type, never a failed list; `llmTypeFor` the same)
- Modify: `spire-ui/src/api.ts` (`LlmModelView`: `id: string | null`, `createdAt: string | null`,
  `source: 'MANUAL' | 'CATALOG'`), `spire-ui/src/components/SettingsLlmProviders.tsx` (`key` by `name`; delete only
  when `id`), every UI fixture that builds an `LlmModelView` (`grep -rn "pricingMode:" spire-ui/src` — includes
  `spire-ui/src/components/repositories/factory/factoryTestSetup.tsx:35,37` and `WorkItemPreparation.test.tsx:16`)
- Modify: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmModelResourceTest.java:46` — the setup
  deletes only rows with a non-null `id` (catalog rows have none)
- Test: extend `LlmModelPricerTest`/`LlmModelRegistry…Test` (whichever exist; else create
  `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/catalog/CatalogReadersTest.java`), and the UI test
  of `SettingsLlmProviders`

**Interfaces:**
- Consumes: Task 4 `LlmModelCatalog`.

- [ ] **Step 1: Write the failing tests**
- `aCatalogModelIsPricedFromTheCatalog` — `priceCall("TEST-cat-o", usage)` uses rates 1/2.
- `aShadowedModelIsPricedFromTheManualRow`.
- `aPricingReadFaultIsUnknownNotTheCatalogRate` — hand-built `LlmModelCatalog` whose fake `ManualModels` throws,
  wrapped by a real `LlmModelPricer`: every line UNKNOWN for a name the catalog prices. This covers both layers (the
  resolver's rethrow and the pricer's catch); Task 4 covers the resolver alone.
- `reviewListSurvivesAResolverFault` — the type is null, the list returns.
- `isDisabledOnAReadFaultThrowsCatalogueUnavailable`.
- `listReturnsEachNameOnceWithItsSource` — the catalog row has `id == null`, `source == "CATALOG"`.
- `useCatalogDeletesAnInUseManualModelThatShadowsAPricedCatalogEntry` — provider names it, delete succeeds.
  **Mutation-check:** the guard skip.
- `deleteStillRefusesAnInUseManualModelWithNoCatalogEntry`.
- `reviewListShowsTheVendorOfACatalogModel` — a charge on `TEST-cat-a` → `llmType == "anthropic"`.
- UI: `renders a catalog model with no id without a delete button`.
- [ ] **Step 2: Verify (CI-side)** — also search tests for `new LlmModelView(` and fix every construction site.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify (CI-side)**
- [ ] **Step 5: Commit** — `Read models and prices through the catalog resolver`

---

### Task 6: Fetch, refresh, notify, REST

**Files:**
- Create in `.../llm/catalog/`: `CatalogFetcher.java`, `LlmCatalogRefresh.java`, `LlmCatalogChangeListener.java`,
  `CatalogChange.java`, `LlmCatalogResource.java`, `LlmCatalogView.java`
- Modify: `.env.example`, `deploy/.env.example`, `deploy/helm/spire/values.yaml` (+ its orchestrator env template)
  with `SPIRE_LLM_CATALOG_URL` and `SPIRE_LLM_CATALOG_REFRESH_INTERVAL`; `deploy/render-manifests.sh` output if it
  checks drift (regenerate the rendered files by the script's documented command only if it runs no build — else
  leave a note in the commit body)
- Test: `CatalogFetcherTest.java` (JDK `HttpServer`), `LlmCatalogRefreshTest.java`, `LlmCatalogResourceTest.java`

**Interfaces:**
- Consumes: Tasks 1, 3, 4.
- Produces:
  - `CatalogFetcher`: `FetchResult fetch(URI url, String etag)`; `sealed interface FetchResult` with
    `record NotModified()`, `record Fetched(String body, String etag)`, `record Failed(String reason)` — reason is a
    status code or exception class name only. 10 s timeout, `HttpClient.Redirect.NEVER`, stop reading at
    `CatalogLimits.MAX_BYTES + 1`. It takes its `HttpClient` by constructor so tests pass one with a test
    `SSLContext`. **The https-only check is not in the fetcher**: `LlmCatalogRefresh` refuses a non-`https` configured
    URL (outcome `FAILED`, `last_error` `url_not_https`) before calling it, and logs it once at startup.
  - `LlmCatalogRefresh` (`@ApplicationScoped`, `@Scheduled(every = "${spire.llm.catalog.refresh-interval}")` with the
    same off-switch pattern as `HarnessCatalogues.refreshEvery`) and an `onStart(@Observes StartupEvent)` that runs
    one refresh off the startup thread (mirroring `HarnessCatalogues.onStart`), so `refresh-interval: off` still
    "reads only at startup" (spec §6.1); `RefreshOutcome refreshNow()`;
    `enum RefreshOutcome { DISABLED, NOT_MODIFIED, UNCHANGED, REPLACED, INVALID, FAILED }`. Flow exactly spec §6.3.
    On a new database (row document null) it first stores the bundled copy (`source='bundled'`) — unless the URL is
    `none`, in which case nothing is ever written (spec §6.5).
  - `CatalogChange`: `static CatalogChange between(CatalogDocument before, CatalogDocument after)` →
    `record CatalogChange(String oldSha, String newSha, List<String> added, List<String> retired,
    List<RePriced> rePriced)`; `toJson()`. Logged at INFO as sha pair + counts; stored as `last_change`.
  - `LlmCatalogChangeListener`: `@Observes StartupEvent`, own connection, `LISTEN llm_catalog_changed` committed,
    then reads the row once; on a notice whose sha differs from `snapshot().sha256()` re-reads the row and calls
    `catalog.replace`. Reconnects on a dropped session and re-reads. Pattern: `work/WorkItemChangeListener.java`.
  - REST: `GET /api/llm-catalog` → `LlmCatalogView(state fields, List<CatalogRow> rows)` and
    `POST /api/llm-catalog/refresh` → `{ "outcome": RefreshOutcome }`, both `@RolesAllowed("spire-admin")`, as
    `LlmModelResource:30`.
  - `LlmCatalogChangeListener` starts only when `spire.llm.catalog.listener` is `on` (default `on`, `%test` `off`).

- [ ] **Step 1: Write the failing tests**
- Fetcher, against a JDK `HttpsServer` with a self-signed test certificate and a client trusting it:
  `aRedirectIsAFailure`, `aBodyOverTheCapIsAFailure`, `304IsNotModified`, `theFailureReasonNeverContainsTheBody`
  (server returns 500 with body `SECRET-BODY`; reason lacks it). **Mutation-check:** `Redirect.NEVER` (only
  `aRedirectIsAFailure` fails).
- Refresh: `anHttpUrlIsRefusedBeforeAnyFetch` (the fake fetcher records no call). **Mutation-check:** the scheme check.
- Refresh: `intervalOffStillReadsOnceAtStartup`.
- Refresh (fetcher stubbed): `anInvalidFileKeepsTheCurrentCatalog`, `aNewValidFileIsStoredAndSwapped`,
  `theSameShaOnlyRecordsTheCheck`, `urlNoneNeverWritesTheRow`, `aNewDatabaseStoresTheBundledCopyFirst`,
  `aReplacementRecordsTheChange` (one re-priced model → `last_change` lists it with old and new rates).
- Listener (started by hand in the test): `aNoticeWithANewShaSwapsTheSnapshot` — write the row with **raw SQL** and
  send `pg_notify` with raw SQL (never through `storeDocument`, which would swap locally), assert
  `snapshot().sha256()` follows within 5 s; reset the row and snapshot in `@AfterEach`. **Mutation-check:** the
  listener's swap.
- Resource: `getListsShadowedRows`, `refreshIsAdminOnly`.
- [ ] **Step 2: Verify (CI-side)**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify (CI-side)**
- [ ] **Step 5: Commit** — `Fetch the catalog from its URL and share it across instances`

---

### Task 7: Claude Code's offer from the catalog; label optional

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/HarnessCatalogues.java`
- Modify: `.../factory/RepositoryBuildResource.java` (`modelsOf`, ~93-97) only if it bypasses `get`
- Delete: `deploy/agent/claude-code/models.json`
- Modify: `deploy/agent/build-claude-code.sh` (no `AGENT_MODELS`, header comment), `deploy/agent/claude-code/Dockerfile`
  (remove the `ARG AGENT_MODELS` + `LABEL dev.codespire.agent.models` lines and their comment),
  `.github/workflows/release.yml` (the Claude Code matrix row's `catalogueScript`, ~line 219, becomes `''`; the step at
  ~235-243 fails on an empty label otherwise), `.github/workflows/docker.yml` (comment at ~216 only; it has no check)
- Modify: `docs/factory/AGENT-IMAGE-CONTRACT.md` (~line 209: prose only — the `models` label is declared, never
  required, and Claude Code ships without it because its offer comes from the catalog). **No change to
  `Clauses.java`**: `DECLARED` (line 101) already means "declared, never required", and
  `ReferenceImageIT.anImageWithNoModelCatalogueSaysSoAndStillConforms` already proves an unlabelled image conforms.
- Modify every orchestrator test that sets a `claude_code` build or review setup with a model that is not an
  enabled `anthropic` resolver model: start from `RepositoryResourceTest:137` (`TEST-model`) and
  `grep -rn "claude_code" spire-orchestrator/src/test` (check `RunResourceTest`, `RunFundingKeyKindTest`,
  `SubscriptionNotAllowed*Test`); use `TEST-cat-a` from the test catalog, or assert the new refusal where the test
  is about refusal
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/factory/HarnessCataloguesCatalogOfferTest.java`

**Interfaces:**
- Consumes: Task 4 `LlmModelCatalog.effective()`.
- Produces: `HarnessCatalogues.get("claude_code")` returns
  `Catalogue(harness, image, Status.OK, models, observedAt, pinnedImage)` where `models` are the enabled `anthropic`
  resolver models as `new HarnessImageResult.Model(name, label, null, List.of(), true, index)` in `effective()`
  order; `image`/`observedAt`/`pinnedImage` come from the `harness_catalogue` row when present, else the configured
  image and `Instant.EPOCH` / null. **When Claude Code's configured image is `none` or absent
  (`application.yml:447` default), `get` returns `Optional.empty()`**, exactly as today. Codex unchanged. The rule "which harness takes its offer from the catalog" is a
  `Set<String> CATALOG_OFFERED = Set.of("claude_code")` constant in `HarnessCatalogues`.

- [ ] **Step 1: Write the failing tests**
- `claudeCodeOffersTheEnabledAnthropicModels` — `TEST-cat-a` offered, `TEST-cat-o` and `TEST-cat-retired` not.
- `claudeCodeRefusesANonAnthropicModel` — `admit("claude_code", "TEST-cat-o", null).refusal()` ==
  `"model_not_run_by_harness"`. **Mutation-check:** the offer source switch.
- `claudeCodeOffersAnEnabledManualAnthropicModel`.
- `claudeCodeWithAnUnlabelledImageIsNotNoCatalogue` — row status `NO_CATALOGUE` → `get` status `OK`.
- `codexStillReadsTheImageLabel`.
- `claudeCodeUnconfiguredHasNoCatalogue` — image `none` → `get` empty, `admit` refuses nothing for a null effort.
- `claudeCodeWithAnEffortIsNotOffered` — `admit("claude_code", "TEST-cat-a", "high").refusal()` ==
  `"effort_not_offered"` (today it is `effort_unverifiable`; the change is intended).
- [ ] **Step 2: Verify (CI-side)**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify (CI-side)**
- [ ] **Step 5: Commit** — `Offer Claude Code the catalog's Anthropic models`

---

### Task 8: Attention text by source, and a stale catalog row

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java` (rows at
  ~139, ~176, ~213: text by `ModelSource`; add `LLM_CATALOG_STALE`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/AttentionQueriesTest.java`

**Interfaces:**
- Consumes: Task 3 `read()`, Task 4 `resolve`.
- Produces: `AttentionView("LLM_CATALOG_STALE", Severity.WARNING, …)` when the URL is not `none` and
  (`last_success_at` is null or older than 3 × refresh interval — or older than 24 h when the interval is `off` —
  or the newest check recorded a validation failure); text names the URL and `last_error`. Catalog-source text for the three pricing rows:
  "Model {name} comes from the catalog and has no price for {types}. Update the catalog, or add a manual model with
  this name." Manual-source text unchanged.

- [ ] **Step 1: Write the failing tests** — `aCatalogModelMissingAPriceSaysUpdateTheCatalog`,
  `aManualModelKeepsTheSettingsText`, `aStaleCatalogRaisesAWarning`, `urlNoneNeverRaisesStale`,
  `aFreshCatalogRaisesNothing`, `seatPaidSetupsStaySkipped`.
- [ ] **Step 2: Verify (CI-side)**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify (CI-side)**
- [ ] **Step 5: Commit** — `Tell operators where a missing model price is fixed`

---

### Task 9: Dashboard — catalog in Settings → LLM → Models

**Files:**
- Modify: `spire-ui/src/api.ts` (`LlmCatalogView`, `CatalogRowView`, `fetchLlmCatalog`, `refreshLlmCatalog`,
  `llmCatalogKey`)
- Create: `spire-ui/src/components/SettingsLlmCatalogHeader.tsx`, `spire-ui/src/components/LlmCatalogModelDetail.tsx`
- Modify: `spire-ui/src/components/SettingsLlmProviders.tsx` (merged table, source column, status text, icons,
  "Use catalog"), `spire-ui/src/components/SettingsLlmModelForm.tsx` (override notice)
- Test: `spire-ui/src/components/SettingsLlmCatalogHeader.test.tsx`, `LlmCatalogModelDetail.test.tsx`, and the
  existing `SettingsLlmProviders` test file

**Interfaces:**
- Consumes: Task 6 REST shapes, Task 5 `source` on `LlmModelView`.
- Produces: `interface LlmCatalogView { url: string | null; source: 'bundled' | 'remote' | null; sha256: string | null;
  loadedAt: string | null; lastCheckedAt: string | null; lastSuccessAt: string | null; lastError: string | null;
  lastChange: CatalogChangeView | null; rows: CatalogRowView[] }`; `interface CatalogRowView { model: CatalogModelView;
  shadowed: boolean }`; `CatalogModelView` mirrors `CatalogModel` (camelCase, dates as ISO strings).
- Layout and copy: spec §8 sketch. Status strings: `Active · overrides catalog`, `Ignored · a manual model has this
  name`, `Deprecated · successor {name}`, `Retired`. Form notice: `This overrides the catalog model with the same
  name.` Rates shown as dollars per 1M from millicents (reuse `llmPricing.ts` formatting).
- Reads through `useQuery(llmCatalogKey, …)` and `preparationKeys.llmModels()`; "Reload now" and "Use catalog"
  invalidate both via `invalidateLive`. Tests render through `renderWithQuery`.

- [ ] **Step 1: Write the failing tests**
- Header: `shows source, load time, sha prefix and model count`, `shows the last error beside the catalog in use`,
  `reload now posts and re-reads`.
- Table: `a shadowed catalog row says Ignored`, `a manual row that shadows offers Use catalog`, `a catalog row has no
  Edit or Delete`, `a retired catalog row says Retired`.
- Detail: `shows every price with its source link and observed date`, `an unpriced type shows as not priced`.
- Form: `typing a catalog name shows the override notice`.
- [ ] **Step 2: Verify (CI-side)**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Verify (CI-side)**
- [ ] **Step 5: Commit** — `Show catalog models beside manual models in Settings`

---

### Task 10: First catalog content and the docs

**Files:**
- Modify: `ai-catalog/v1/catalog.yaml`
- Modify: `docs/DECISIONS.md` (new ADR: catalog from a public file; amends ADR-018), `docs/UNVERIFIED.md` (entries:
  raw GitHub `ETag`/cache behaviour, a refresh across two instances, Claude Code offer from the catalog, `cacheWrite1h`
  recorded not charged), `docs/factory/RUN-TOPOLOGY.md` or the operator guide that lists env vars (wherever
  `SPIRE_FACTORY_AGENT_IMAGE_CLAUDE_CODE` is documented), `docs/HISTORY.md` (a milestone entry)

- [ ] **Step 1: Collect the model list.** Codex: `docker inspect --format '{{ index .Config.Labels "dev.codespire.agent.models" }}' spire-agent-codex:latest | base64 -d`.
  **If the image is absent, stop Task 10 and report it; do not guess slugs.** Claude Code: the four slugs from the
  deleted `models.json` (git history). Gemini: none in this task.
- [ ] **Step 2: Read every price from the vendor's own pricing page in this session** (Anthropic, OpenAI, Google).
  For each model record input, output, cache read, cache write (5-minute), cache write (1-hour) where listed, and
  reasoning only where the page states how reasoning tokens are billed. `sourceUrl` = the page read, `observedAt` =
  today. A model whose price is not on a vendor page is left out. Convert dollars per 1M tokens to millicents:
  `$1.00 = 100_000`. If a vendor page cannot be read, leave its models out and report it.
- [ ] **Step 2b: Write `ai-catalog/PRICES-REVIEW.md`** for the human reviewer: one row per model and price field
  with the dollar figure **as quoted on the page**, the millicent value written, and the URL. The operator checks
  this table before the branch is pushed; it is deleted in the commit after their check.
- [ ] **Step 3: Fill `contextWindow`, `maxOutputTokens`, capabilities and lifecycle only from the vendor's model
  pages**; leave a field `null` when the page does not state it.
- [ ] **Step 4: Verify (CI-side)** — `RepositoryCatalogTest` and `checkCatalog` run in CI.
- [ ] **Step 5: Write the docs** listed above.
- [ ] **Step 6: Commit** — `Add the first catalog models and document the catalog`

---

## Self-review notes

- Spec §6.2 "load before consumers" is met by the resolver's synchronous lazy first load (Task 4): the first price,
  whenever a consumer asks, waits for the stored/bundled document. No startup-ordering dependency on Kafka.
- Spec §5 "JSON Schema file": replaced by the Java records + `CatalogValidator`, which CI runs strictly (Task 2), so
  there is one definition, not two. The spec is updated to match.
- Spec §7.3 shared vendor constant: Task 1 `Vendors.TYPES`, used in Task 5.
