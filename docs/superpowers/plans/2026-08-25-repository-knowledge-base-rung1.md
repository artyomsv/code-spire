# Repository Knowledge Base — Rung 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a review the definitions of the symbols its diff touches, resolved through the repository's own import graph, contributed as ordinary `CODE_SNIPPET` context items — storing nothing.

**Architecture:** Two phases split at the wire boundary. At diff-fetch, `DiffWorker` derives changed paths plus the identifiers appearing in changed lines and carries them on a new `codeReferences` field. In the context aggregator, a new Apache-2.0 `spire-context-code` provider fetches each changed file at the review commit, parses its import block, intersects with those identifiers, resolves the surviving imports to candidate paths, fetches the definitions, and contributes them into a dedicated `{{code_context}}` prompt slot. No index, no embeddings, no crawl.

**Tech Stack:** Java 25, Quarkus 3.38.3, Gradle Kotlin DSL, JUnit 6, WireMock 3.13.2, JDK `HttpClient` + Jackson (framework-free library modules), React 19 + Vite + vitest for the settings UI.

**Spec:** `docs/superpowers/specs/2026-08-25-repository-knowledge-base-design.md` (and ADR-026 in `docs/DECISIONS.md`)

## Global Constraints

- **Rung 2 is NOT in this plan.** `worker.code_symbol`, `callersOf`, and any migration are out of scope. Only the `SymbolIndex` port is defined (Task 1), and nothing reads it.
- **Nothing is stored.** No new table, no new migration, no blob. Snippet text is fetched live and discarded. ADR-011 stands unamended.
- **Code references travel on their own wire field**, never the neutral `references` set, and code snippets are **excluded from the aggregator's level-2 reference mining** (spec §4.2).
- **Only metadata crosses the wire** — changed paths and identifiers. Never hunk text, never file content.
- **`spire-context-code` is Apache-2.0** and must not depend on any service module (`spire-gateway`, `spire-orchestrator`, `spire-review-worker`, `spire-ui`) — ADR-021. The reverse direction is allowed.
- **Framework-free library modules.** `spire-contract` and `spire-diff` permit only the JDK, each other, and `jackson-annotations`; `PureModulesAreFrameworkFreeTest` in `spire-arch` enforces it. `spire-context-code` follows the `spire-context-github` pattern: `java-library`, JDK `HttpClient` + Jackson, CDI wiring only in the host service.
- **Every module must join a test tier.** Add `spire-context-code` to `fastTestModules` in the root `build.gradle.kts`, or `TestTierCoverageTest` fails the build.
- **Provider neutrality is build-enforced.** `spire-arch` fails the build if a core module (`spire-contract`, `spire-orchestrator`, `spire-review-worker`, `spire-gateway`) names an SCM provider outside the allowlist. Per-platform code lives in `spire-context-code`; `WorkerContextClients` is already an allowlisted composition root.
- **Redirect handling has one home.** `RedirectHandlingHasOneHomeTest` fails a fourth hand-rolled redirect loop. Reuse `spire-http`.
- **No fabricated data.** Test fixtures use obviously-synthetic identifiers and `example.invalid` hosts. No real-looking repository content committed as a fixture beyond what a parse test needs.
- **Never name the product in new user-visible strings.** "Code Spire" is provisional and lives in exactly six production literals; do not add a seventh.
- **Style:** 4-space indent for Java, 2-space for TypeScript; explicit types over `var` in Java; `interface` over `type` for TS object shapes; lucide-react icons, never emoji.
- **Commits:** imperative subject, max 72 chars, body wrapped at 72. Describe what changed and why. No authorship trailers, no model or vendor names.
- **Verification commands:** `./gradlew testFast` (Docker-free, ~25s), `./gradlew testServices` (Testcontainers), `cd spire-ui && npx vitest run && npx tsc --noEmit`. Use `--rerun-tasks` when a green run must be proven rather than assumed.

---

## File Structure

**New module — `spire-context-code/` (Apache-2.0):**

| File | Responsibility |
|---|---|
| `build.gradle.kts` | `java-library`, deps on `spire-contract` + `spire-http` + jackson-databind |
| `LICENSE` | Apache-2.0, copied from `spire-context-github/LICENSE` |
| `CodeContextProvider.java` | The `ContextProvider`: phase-2 orchestration, per-file cache, ranking, caps |
| `CodeContextConfig.java` | baseUrl, authKind, secret, path allow-list — built from `ContextCredential` |
| `SourceFileReader.java` | Port: read a file's text at a commit. One implementation per platform |
| `GitHubSourceFileReader.java` / `GitLabSourceFileReader.java` / `BitbucketSourceFileReader.java` | Platform raw-content clients over `spire-http` |
| `SnippetExtractor.java` | Declaration + doc + clipped body from a file's text |
| `JavaLanguageSupport.java` / `TypeScriptLanguageSupport.java` | One language's identifier and import knowledge |

**Modified — `spire-contract/` (Apache-2.0):**

| File | Change |
|---|---|
| `review/CodeReferences.java` | **New** value type: changed paths + identifiers |
| `port/LanguageSupport.java` | **New** SPI |
| `port/SymbolIndex.java` | **New** SPI, defined but unread (rung 2 seam) |
| `event/IntegrationEvent.java` | `DiffFetched` gains `codeReferences` |
| `command/ActionCommand.java` | `GatherContext` gains `codeReferences` |
| `review/ContextRequest.java` | gains `codeReferences` |
| `llm/PromptCatalog.java` | `{{code_context}}` palette entry + default REVIEW body |

**Modified — services:**

| File | Change |
|---|---|
| `spire-review-worker/.../adapters/WorkerCodeReferences.java` | **New** composition root for `LanguageSupport` beans |
| `spire-review-worker/.../pipeline/DiffWorker.java` | populate `codeReferences` |
| `spire-review-worker/.../pipeline/ContextWorker.java` | pass `codeReferences` through; exclude code snippets from level-2 mining |
| `spire-review-worker/.../adapters/WorkerContextClients.java` | construct `CodeContextProvider` for type `code` |
| `spire-orchestrator/.../pipeline/*Saga.java` | carry `codeReferences` onto `GatherContext` |
| `spire-orchestrator/.../ContextProviderResource` + registry | accept type `code`, bearer-only, check endpoint |
| `spire-ui/src/components/SettingsContext.tsx` | the `code` provider type in the form |

---

## Task 1: New module, the three SPI ports, and build wiring

**Files:**
- Create: `spire-context-code/build.gradle.kts`, `spire-context-code/LICENSE`
- Create: `spire-contract/src/main/java/dev/codespire/contract/port/LanguageSupport.java`
- Create: `spire-contract/src/main/java/dev/codespire/contract/port/SymbolIndex.java`
- Create: `spire-context-code/src/test/java/dev/codespire/context/code/ModuleWiringTest.java`
- Modify: `settings.gradle.kts` (after `include("spire-context-gitlab")`)
- Modify: `build.gradle.kts` (`fastTestModules` list, after `"spire-context-gitlab"`)
- Modify: `LICENSING.md` (module table — thirteen Apache modules becomes fourteen)

**Interfaces:**
- Produces: `LanguageSupport` (used by Tasks 3, 4, 5, 10), `SymbolIndex` (unread in rung 1).

- [ ] **Step 1: Write the failing test**

`spire-context-code/src/test/java/dev/codespire/context/code/ModuleWiringTest.java`:

```java
package dev.codespire.context.code;

import dev.codespire.contract.port.LanguageSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleWiringTest {

    /**
     * The module exists, compiles, and can see spire-contract's SPI. Trivial on purpose:
     * its job is to give the module a test so TestTierCoverageTest has something to find.
     */
    @Test
    void theModuleSeesTheLanguageSupportPort() {
        assertTrue(LanguageSupport.class.isInterface());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :spire-context-code:test`
Expected: FAIL — project `:spire-context-code` not found.

- [ ] **Step 3: Create the module**

`spire-context-code/build.gradle.kts`:

```kotlin
// Repository code context provider: resolves the symbols a diff touches into
// CODE_SNIPPET ContextItems through the repository's own import graph (ADR-026).
// Framework-free library (JDK HttpClient + Jackson); CDI wiring happens in the
// service that hosts it (spire-review-worker).
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":spire-contract"))
    implementation(project(":spire-http"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.wiremock:wiremock:3.13.2")
}

tasks.test {
    useJUnitPlatform()
}
```

Copy `spire-context-github/LICENSE` to `spire-context-code/LICENSE` unchanged.

Add `include("spire-context-code")` to `settings.gradle.kts` after the `spire-context-gitlab` line, and `"spire-context-code",` to `fastTestModules` in the root `build.gradle.kts` after `"spire-context-gitlab",`.

- [ ] **Step 4: Write the two SPI ports**

`spire-contract/src/main/java/dev/codespire/contract/port/LanguageSupport.java`:

```java
package dev.codespire.contract.port;

import dev.codespire.contract.scm.FilePatch;

import java.util.List;
import java.util.Set;

/**
 * One language's knowledge of how a change refers to code elsewhere in its repository.
 *
 * <p>Split across the wire boundary on purpose. {@link #identifiersIn} runs at diff-fetch, where the
 * parsed diff lives; the rest run in the context provider, which is the only place that has fetched
 * the file's text. A diff carries hunks, not files, and imports live at the top of a file — so an
 * import block is generally NOT in a hunk and cannot be read at diff-fetch.
 *
 * <p>Adding a language is a new bean, not a core edit. A file whose language has no implementation
 * contributes nothing and its review proceeds exactly as before.
 */
public interface LanguageSupport {

    /** Language tags this handles, as produced by {@code Languages.of(path)} — e.g. "java". */
    Set<String> languages();

    /**
     * Identifiers referenced in the patch's CHANGED lines only — added and removed, never context
     * lines. Context lines are unchanged code and would flood the set with the whole file's
     * vocabulary, which is the difference between "this change touches three things" and "this file
     * mentions forty".
     */
    Set<String> identifiersIn(FilePatch patch);

    /** The import statements in a file's full text, in source order. */
    List<ImportRef> importsIn(String fileContent);

    /**
     * Repository paths an import could resolve to, best candidate first. Several are returned
     * because resolution is conventional rather than certain — a Java source root, a TypeScript
     * extension or {@code index.ts}. The caller tries them in order and stops at the first that
     * exists.
     */
    List<String> candidatePaths(ImportRef ref, String importingPath);

    /**
     * One import. {@code symbols} is what the statement brings into scope — the names that can be
     * intersected with {@link #identifiersIn}. {@code specifier} is the raw module reference.
     */
    record ImportRef(String specifier, Set<String> symbols) {

        public ImportRef {
            symbols = symbols == null ? Set.of() : Set.copyOf(symbols);
        }
    }
}
```

`spire-contract/src/main/java/dev/codespire/contract/port/SymbolIndex.java`:

```java
package dev.codespire.contract.port;

import java.util.List;

/**
 * Rung 2's structural symbol table (ADR-026). Defined here, and unread in rung 1, because ADR-021
 * forbids the Apache-2.0 provider depending on the FSL worker that owns the schema — so the port
 * must exist for rung 2 to be an addition rather than a refactor of rung 1. This is the
 * {@code BlobStore} arrangement repeated.
 *
 * <p><b>The index is a hint, never an answer.</b> A caller takes candidates from here, re-fetches
 * them at the review commit, and confirms the reference still exists before citing it. That is what
 * removes the staleness problem: there is no invalidation pass, and no stored row can speak for
 * current code.
 */
public interface SymbolIndex {

    /** Files known to reference the symbol. Candidates only — confirm before citing. */
    List<String> callersOf(String repo, String symbol);

    /** Record what a file was observed to define and reference, at the commit it was read at. */
    void record(String repo, String path, String commit, List<String> defines, List<String> references);
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :spire-context-code:test :spire-arch:test`
Expected: PASS, including `TestTierCoverageTest` (which fails if the new module joined no tier) and `PureModulesAreFrameworkFreeTest`.

- [ ] **Step 6: Update `LICENSING.md`**

In the Apache-2.0 module table add a `spire-context-code` row with the reason "repository code context provider (ADR-026)", and update the count sentence from thirteen to fourteen.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts LICENSING.md spire-context-code spire-contract/src/main/java/dev/codespire/contract/port/LanguageSupport.java spire-contract/src/main/java/dev/codespire/contract/port/SymbolIndex.java
git commit -m "Add the code context module and its two SPI ports"
```

---

## Task 2: `CodeReferences` on the wire

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/review/CodeReferences.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/IntegrationEvent.java` (`DiffFetched`)
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/ActionCommand.java` (`GatherContext`)
- Modify: `spire-contract/src/main/java/dev/codespire/contract/review/ContextRequest.java`
- Test: `spire-contract/src/test/java/dev/codespire/contract/review/CodeReferencesTest.java`
- Test: `spire-contract/src/test/resources/contract-schema.txt` (the golden `ContractSchemaSnapshotTest` reads)

**Interfaces:**
- Consumes: nothing.
- Produces: `CodeReferences(Set<String> changedPaths, Set<String> identifiers)`; `DiffFetched.codeReferences()`, `GatherContext.codeReferences()`, `ContextRequest.codeReferences()`.

- [ ] **Step 1: Write the failing test**

`spire-contract/src/test/java/dev/codespire/contract/review/CodeReferencesTest.java`:

```java
package dev.codespire.contract.review;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeReferencesTest {

    @Test
    void emptyIsTheAbsentCase() {
        assertTrue(CodeReferences.empty().isEmpty());
        assertEquals(Set.of(), CodeReferences.empty().changedPaths());
        assertEquals(Set.of(), CodeReferences.empty().identifiers());
    }

    @Test
    void bothSetsAreDefensivelyCopied() {
        Set<String> paths = new HashSet<>(Set.of("src/Alpha.java"));
        CodeReferences refs = new CodeReferences(paths, Set.of("betaSymbol"));
        paths.add("src/Gamma.java");
        assertEquals(Set.of("src/Alpha.java"), refs.changedPaths());
        assertThrows(UnsupportedOperationException.class, () -> refs.identifiers().add("x"));
    }

    @Test
    void nullSetsBecomeEmptyRatherThanNull() {
        CodeReferences refs = new CodeReferences(null, null);
        assertEquals(Set.of(), refs.changedPaths());
        assertEquals(Set.of(), refs.identifiers());
        assertTrue(refs.isEmpty());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :spire-contract:test --tests '*CodeReferencesTest*'`
Expected: FAIL — `CodeReferences` does not exist.

- [ ] **Step 3: Write the value type**

`spire-contract/src/main/java/dev/codespire/contract/review/CodeReferences.java`:

```java
package dev.codespire.contract.review;

import java.util.Set;

/**
 * What a diff says about the code it depends on: the paths it changed, and the identifiers appearing
 * in its changed lines.
 *
 * <p>Deliberately NOT folded into {@link ContextRequest}'s neutral {@code references} set, which
 * carries issue keys and page links. Two hazards run in opposite directions. The aggregator's
 * level-2 collection mines contributed item bodies for new references, so a {@code PROJ-123} inside a
 * code comment would be fetched as a ticket. And {@code references} is documented as recall-favouring
 * because "a false candidate costs nothing but an unmatched string" — true at ticket-key volume, and
 * not true of the tens-to-hundreds of identifiers a diff yields, scanned by every registered provider.
 *
 * <p><b>Metadata only.</b> Paths and identifiers, never hunk text — ADR-011 is untouched.
 */
public record CodeReferences(Set<String> changedPaths, Set<String> identifiers) {

    private static final CodeReferences EMPTY = new CodeReferences(Set.of(), Set.of());

    public CodeReferences {
        changedPaths = changedPaths == null ? Set.of() : Set.copyOf(changedPaths);
        identifiers = identifiers == null ? Set.of() : Set.copyOf(identifiers);
    }

    public static CodeReferences empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return changedPaths.isEmpty() || identifiers.isEmpty();
    }
}
```

Note `isEmpty()` is an OR, not an AND: with no changed paths there is no file to read imports from, and with no identifiers there is nothing to intersect against — either alone makes the contribution impossible, so both are "nothing to do".

- [ ] **Step 4: Add the wire fields**

In `IntegrationEvent.DiffFetched`, add `CodeReferences codeReferences` as the final component and keep a constructor without it that passes `CodeReferences.empty()`, so existing construction sites and any replayed record still deserialize. In `ActionCommand.GatherContext` and `ContextRequest`, add the same component with the same treatment.

Follow the existing compact-constructor style in `DiffFetched` — it already copies `languages` and `references` defensively.

- [ ] **Step 5: Update the contract snapshot**

Run: `./gradlew :spire-contract:test --tests '*ContractSchemaSnapshotTest*'`
Expected: FAIL with a diff naming the three changed types. Update the golden file to match, and confirm the failure named all three — if it named fewer, a field was added somewhere the gate cannot see.

Note the known gap recorded in `techdebt/spire-contract/3-2-contract-snapshot-does-not-recurse-into-nested-wire-types.md`: the snapshot renders a nested record component as `name: TypeName` and does not recurse, so it will show `codeReferences: CodeReferences` without describing that type's own shape. That is expected here and is not a reason to change the gate in this task.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :spire-contract:test`
Expected: PASS, including the round-trip test.

- [ ] **Step 7: Commit**

```bash
git add spire-contract
git commit -m "Carry diff-derived code references on the wire"
```

---

## Task 3: `JavaLanguageSupport`

**Files:**
- Create: `spire-context-code/src/main/java/dev/codespire/context/code/JavaLanguageSupport.java`
- Test: `spire-context-code/src/test/java/dev/codespire/context/code/JavaLanguageSupportTest.java`

**Interfaces:**
- Consumes: `LanguageSupport`, `LanguageSupport.ImportRef`, `FilePatch`, `Hunk`, `DiffLine`, `LineType`.
- Produces: `JavaLanguageSupport implements LanguageSupport` with `languages() == Set.of("java")`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.codespire.context.code;

import dev.codespire.contract.port.LanguageSupport.ImportRef;
import dev.codespire.contract.scm.ChangeType;
import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.LineType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLanguageSupportTest {

    private final JavaLanguageSupport support = new JavaLanguageSupport();

    private static FilePatch patch(DiffLine... lines) {
        return new FilePatch(null, "src/main/java/dev/example/Alpha.java", ChangeType.MODIFIED,
                "java", false, false, List.of(new Hunk(1, 1, 1, lines.length, List.of(lines))));
    }

    @Test
    void identifiersComeFromChangedLinesOnly() {
        FilePatch p = patch(
                new DiffLine(LineType.CONTEXT, 1, 1, "int ignoredFromContext = untouchedHelper();"),
                new DiffLine(LineType.ADDED, null, 2, "long total = pricingHelper.chargeFor(item);"),
                new DiffLine(LineType.REMOVED, 3, null, "long total = legacyHelper.oldCharge(item);"));

        Set<String> found = support.identifiersIn(p);

        assertTrue(found.contains("pricingHelper"));
        assertTrue(found.contains("chargeFor"));
        assertTrue(found.contains("legacyHelper"));
        // The whole point of the changed-lines rule: a context line's vocabulary must not leak in.
        assertFalse(found.contains("untouchedHelper"));
        assertFalse(found.contains("ignoredFromContext"));
    }

    @Test
    void languageKeywordsAndPrimitivesAreNotIdentifiers() {
        FilePatch p = patch(new DiffLine(LineType.ADDED, null, 1,
                "public static final int alphaCount = 0;"));

        Set<String> found = support.identifiersIn(p);

        assertEquals(Set.of("alphaCount"), found);
    }

    @Test
    void stringLiteralsAndCommentsAreNotMined() {
        FilePatch p = patch(
                new DiffLine(LineType.ADDED, null, 1, "String s = \"notAnIdentifier\"; // alsoNotOne"),
                new DiffLine(LineType.ADDED, null, 2, "realCall();"));

        Set<String> found = support.identifiersIn(p);

        assertTrue(found.contains("realCall"));
        assertFalse(found.contains("notAnIdentifier"));
        assertFalse(found.contains("alsoNotOne"));
    }

    @Test
    void importsAreParsedWithTheirSimpleName() {
        String file = """
                package dev.example;

                import dev.example.pricing.LlmModelPricer;
                import static dev.example.util.Assertions.assertPriced;
                import java.util.List;

                class Alpha { }
                """;

        List<ImportRef> imports = support.importsIn(file);

        assertTrue(imports.contains(
                new ImportRef("dev.example.pricing.LlmModelPricer", Set.of("LlmModelPricer"))));
        assertTrue(imports.contains(
                new ImportRef("dev.example.util.Assertions", Set.of("assertPriced"))));
    }

    @Test
    void aWildcardImportBringsNoNameIntoScopeSoItIsSkipped() {
        List<ImportRef> imports = support.importsIn("import dev.example.pricing.*;");

        assertTrue(imports.isEmpty());
    }

    @Test
    void candidatePathsTryEachConventionalSourceRoot() {
        List<String> paths = support.candidatePaths(
                new ImportRef("dev.example.pricing.LlmModelPricer", Set.of("LlmModelPricer")),
                "spire-orchestrator/src/main/java/dev/example/Alpha.java");

        assertTrue(paths.contains("spire-orchestrator/src/main/java/dev/example/pricing/LlmModelPricer.java"));
        assertTrue(paths.contains("src/main/java/dev/example/pricing/LlmModelPricer.java"));
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :spire-context-code:test --tests '*JavaLanguageSupportTest*'`
Expected: FAIL — `JavaLanguageSupport` does not exist.

- [ ] **Step 3: Implement**

Write `JavaLanguageSupport` with:

- `languages()` returns `Set.of("java")`.
- `identifiersIn(FilePatch)` iterates hunks, keeps lines whose `type()` is `ADDED` or `REMOVED`, strips `//` line comments and double-quoted string literals from each line before matching, then matches `[A-Za-z_$][A-Za-z0-9_$]*` and drops a fixed keyword/primitive set (`public private protected static final int long double boolean void class interface record new return if else for while this super null true false import package throws throw try catch`).
- `importsIn(String)` matches `^\s*import\s+(static\s+)?([\w.]+)\s*;` per line; the symbol is the last dot-segment; a specifier ending in `.*` yields no `ImportRef` at all.
- `candidatePaths(ref, importingPath)` converts dots to slashes plus `.java`, then prefixes it with each conventional source root in order: the importing file's own root (everything up to and including `src/main/java/` if present), then `src/main/java/`, then `src/test/java/`, then the bare path.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :spire-context-code:test --tests '*JavaLanguageSupportTest*'`
Expected: PASS.

- [ ] **Step 5: Mutation-verify the changed-lines rule**

Temporarily change the line filter to accept `LineType.CONTEXT` as well. Run the test. Exactly `identifiersComeFromChangedLinesOnly` must fail. Revert.

- [ ] **Step 6: Commit**

```bash
git add spire-context-code
git commit -m "Resolve Java identifiers and imports for code context"
```

---

## Task 4: `TypeScriptLanguageSupport`

**Files:**
- Create: `spire-context-code/src/main/java/dev/codespire/context/code/TypeScriptLanguageSupport.java`
- Test: `spire-context-code/src/test/java/dev/codespire/context/code/TypeScriptLanguageSupportTest.java`

**Interfaces:**
- Consumes: `LanguageSupport`, `LanguageSupport.ImportRef`, `FilePatch`.
- Produces: `TypeScriptLanguageSupport implements LanguageSupport` with `languages() == Set.of("typescript", "javascript")`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.codespire.context.code;

import dev.codespire.contract.port.LanguageSupport.ImportRef;
import dev.codespire.contract.scm.ChangeType;
import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.LineType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeScriptLanguageSupportTest {

    private final TypeScriptLanguageSupport support = new TypeScriptLanguageSupport();

    private static FilePatch patch(String path, DiffLine... lines) {
        return new FilePatch(null, path, ChangeType.MODIFIED, "typescript", false, false,
                List.of(new Hunk(1, 1, 1, lines.length, List.of(lines))));
    }

    @Test
    void bothTsAndJsAreHandled() {
        assertTrue(support.languages().containsAll(Set.of("typescript", "javascript")));
    }

    @Test
    void identifiersComeFromChangedLinesOnly() {
        FilePatch p = patch("spire-ui/src/components/Alpha.tsx",
                new DiffLine(LineType.CONTEXT, 1, 1, "const ignored = untouchedHelper()"),
                new DiffLine(LineType.ADDED, null, 2, "const total = formatCost(review.cost)"));

        Set<String> found = support.identifiersIn(p);

        assertTrue(found.contains("formatCost"));
        assertFalse(found.contains("untouchedHelper"));
    }

    @Test
    void namedDefaultAndNamespaceImportsAllYieldTheirBoundNames() {
        String file = """
                import { formatCost, parseSeverity } from './format'
                import ReviewCard from '../cards/ReviewCard'
                import * as api from '../api'
                """;

        List<ImportRef> imports = support.importsIn(file);

        assertTrue(imports.contains(new ImportRef("./format", Set.of("formatCost", "parseSeverity"))));
        assertTrue(imports.contains(new ImportRef("../cards/ReviewCard", Set.of("ReviewCard"))));
        assertTrue(imports.contains(new ImportRef("../api", Set.of("api"))));
    }

    @Test
    void anAliasedImportBindsTheAliasBecauseThatIsWhatTheCodeCalls() {
        List<ImportRef> imports = support.importsIn("import { formatCost as money } from './format'");

        assertEquals(List.of(new ImportRef("./format", Set.of("money"))), imports);
    }

    @Test
    void relativeSpecifiersResolveAgainstTheImportingFileWithExtensionCandidates() {
        List<String> paths = support.candidatePaths(
                new ImportRef("./format", Set.of("formatCost")),
                "spire-ui/src/components/Alpha.tsx");

        assertEquals(List.of(
                "spire-ui/src/components/format.ts",
                "spire-ui/src/components/format.tsx",
                "spire-ui/src/components/format.js",
                "spire-ui/src/components/format/index.ts",
                "spire-ui/src/components/format/index.tsx"), paths);
    }

    @Test
    void aBarePackageSpecifierResolvesToNothingBecauseItIsNotInThisRepository() {
        assertTrue(support.candidatePaths(
                new ImportRef("react", Set.of("useState")), "spire-ui/src/App.tsx").isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :spire-context-code:test --tests '*TypeScriptLanguageSupportTest*'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

- `identifiersIn` mirrors Java's: changed lines only, comments (`//`) and quoted literals (single, double, backtick) stripped, `[A-Za-z_$][A-Za-z0-9_$]*` matched, TS/JS keywords dropped (`const let var function class return if else import from export default async await new this null true false interface type`).
- `importsIn` handles the three forms in the tests. For `{ a as b }` bind `b`. For `import X from` bind `X`. For `import * as ns from` bind `ns`. A statement with no bindings (`import './side-effect'`) yields no `ImportRef`.
- `candidatePaths` returns empty for any specifier not starting with `.` — a bare package is a dependency, not repository code. For a relative one, normalize `./` and `../` against the importing file's directory, then emit `.ts`, `.tsx`, `.js`, `/index.ts`, `/index.tsx` in that order.

`tsconfig.json` `paths` aliases are **out of scope** (spec §11.2): an aliased import yields no candidates, which is a recall gap and not an error.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :spire-context-code:test --tests '*TypeScriptLanguageSupportTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-context-code
git commit -m "Resolve TypeScript identifiers and imports for code context"
```

---

## Task 5: `DiffWorker` populates `codeReferences`

**Files:**
- Create: `spire-review-worker/src/main/java/dev/codespire/worker/adapters/WorkerCodeReferences.java`
- Modify: `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/DiffWorker.java`
- Modify: `spire-review-worker/build.gradle.kts` (add `implementation(project(":spire-context-code"))`)
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/DiffWorkerCodeReferencesTest.java`

**Interfaces:**
- Consumes: `LanguageSupport`, `CodeReferences`, `FilePatch.language()`.
- Produces: `WorkerCodeReferences.inDiff(Diff) -> CodeReferences`; `DiffFetched.codeReferences()` now populated.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.worker.pipeline;

import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.scm.ChangeType;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.LineType;
import dev.codespire.worker.adapters.WorkerCodeReferences;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffWorkerCodeReferencesTest {

    private final WorkerCodeReferences refs = new WorkerCodeReferences();

    private static FilePatch patch(String path, String language, DiffLine... lines) {
        return new FilePatch(null, path, ChangeType.MODIFIED, language, false, false,
                List.of(new Hunk(1, 1, 1, lines.length, List.of(lines))));
    }

    @Test
    void changedPathsAndIdentifiersAreCollectedPerLanguage() {
        Diff diff = new Diff("cafe1234", List.of(
                patch("src/main/java/dev/example/Alpha.java", "java",
                        new DiffLine(LineType.ADDED, null, 1, "pricingHelper.chargeFor(item);")),
                patch("spire-ui/src/Beta.tsx", "typescript",
                        new DiffLine(LineType.ADDED, null, 1, "const c = formatCost(x)"))), false);

        CodeReferences result = refs.inDiff(diff);

        assertEquals(java.util.Set.of("src/main/java/dev/example/Alpha.java", "spire-ui/src/Beta.tsx"),
                result.changedPaths());
        assertTrue(result.identifiers().contains("chargeFor"));
        assertTrue(result.identifiers().contains("formatCost"));
    }

    @Test
    void anUnsupportedLanguageContributesNothingAndDoesNotFail() {
        Diff diff = new Diff("cafe1234", List.of(
                patch("infra/main.tf", "terraform",
                        new DiffLine(LineType.ADDED, null, 1, "resource \"aws_s3_bucket\" \"b\" {}"))), false);

        CodeReferences result = refs.inDiff(diff);

        assertTrue(result.identifiers().isEmpty());
        // The path is still absent: with no identifiers there is nothing to intersect against.
        assertTrue(result.isEmpty());
    }

    @Test
    void aBinaryOrTooLargePatchIsSkipped() {
        FilePatch binary = new FilePatch(null, "logo.png", ChangeType.MODIFIED, "unknown",
                true, false, List.of());
        Diff diff = new Diff("cafe1234", List.of(binary), false);

        assertFalse(refs.inDiff(diff).changedPaths().contains("logo.png"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-review-worker:test --tests '*DiffWorkerCodeReferencesTest*'`
Expected: FAIL — `WorkerCodeReferences` does not exist.

- [ ] **Step 3: Implement the composition root**

`WorkerCodeReferences` is `@ApplicationScoped`, holds `List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport())`, indexes them by the tags in `languages()`, and `inDiff(Diff)`:

- skips a `FilePatch` that is `binary()` or `tooLarge()`;
- looks up the support by `patch.language()`, skipping the patch entirely when there is none;
- unions `identifiersIn(patch)` and collects `patch.newPath()` (falling back to `oldPath()` for a deletion);
- returns `CodeReferences.empty()` when either set ends up empty.

This is a composition root in the same sense as `WorkerContextReferences` — it is the only place that knows which languages exist.

- [ ] **Step 4: Wire it into `DiffWorker`**

Inject `WorkerCodeReferences` and pass `codeRefs.inDiff(diff)` as the new final argument to the `DiffFetched` constructor, with a comment recording that this is metadata — paths and identifiers, never hunk text.

- [ ] **Step 5: Assert code identifiers never leak into the `references` set**

This is spec §8.3 test 2, and it guards the separation Task 2's value type exists for. Add to the same test class:

```java
@Test
void codeIdentifiersDoNotEnterTheNeutralReferencesSet() {
    // A changed line mentioning something ticket-shaped AND something symbol-shaped.
    Diff diff = new Diff("cafe1234", List.of(
            patch("src/main/java/dev/example/Alpha.java", "java",
                    new DiffLine(LineType.ADDED, null, 1, "pricingHelper.chargeFor(item);"))), false);

    DiffFetched emitted = // ... run DiffWorker against a stub DiffSource returning this diff ...

    // The two sets are populated by different extractors and must not cross-feed: the ticket
    // providers would scan hundreds of identifiers, and a ticket-shaped token in code would be
    // fetched as a real ticket.
    assertTrue(emitted.codeReferences().identifiers().contains("chargeFor"));
    assertFalse(emitted.references().contains("chargeFor"));
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :spire-review-worker:test --tests '*DiffWorker*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add spire-review-worker
git commit -m "Derive code references when the diff is fetched"
```

---

## Task 6: The orchestrator carries `codeReferences` to `GatherContext`

**Files:**
- Modify: the orchestrator saga that builds `GatherContext` from `DiffFetched` (find it with `grep -rn "new GatherContext" spire-orchestrator/src/main`)
- Modify: `spire-review-worker/.../pipeline/ContextWorker.java` — put `codeReferences` on the `ContextRequest` it builds
- Test: add to the orchestrator's existing choreography test suite for the diff-to-context hop

**Interfaces:**
- Consumes: `DiffFetched.codeReferences()`.
- Produces: `GatherContext.codeReferences()` and `ContextRequest.codeReferences()` populated end to end.

- [ ] **Step 1: Write the failing test**

Add to the orchestrator's diff-to-context choreography test:

```java
@Test
void codeReferencesSurviveTheHopFromDiffFetchedToGatherContext() {
    CodeReferences refs = new CodeReferences(
            java.util.Set.of("src/main/java/dev/example/Alpha.java"),
            java.util.Set.of("chargeFor"));

    // ... emit DiffFetched carrying refs through the existing test harness ...

    GatherContext dispatched = // ... capture the dispatched command ...
    assertEquals(refs, dispatched.codeReferences());
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*Context*'`
Expected: FAIL — the field arrives empty because the saga drops it.

- [ ] **Step 3: Carry the field**

Pass `event.codeReferences()` through at the `new GatherContext(...)` site, and in `ContextWorker.request(...)` pass `command.codeReferences()` onto the `ContextRequest` it builds for each level.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :spire-orchestrator:test :spire-review-worker:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator spire-review-worker
git commit -m "Carry code references through to the context request"
```

---

## Task 7: `SourceFileReader` and the GitHub implementation

**Files:**
- Create: `spire-context-code/src/main/java/dev/codespire/context/code/SourceFileReader.java`
- Create: `spire-context-code/src/main/java/dev/codespire/context/code/CodeContextConfig.java`
- Create: `spire-context-code/src/main/java/dev/codespire/context/code/CodeContextApiException.java`
- Create: `spire-context-code/src/main/java/dev/codespire/context/code/GitHubSourceFileReader.java`
- Test: `spire-context-code/src/test/java/dev/codespire/context/code/GitHubSourceFileReaderTest.java`

**Interfaces:**
- Consumes: `spire-http`'s `PinnedJsonClient` for its SSRF-guarded, pinned-redirect request path.
- Produces: `SourceFileReader.read(String repo, String path, String commit) -> String` (null when absent); `CodeContextConfig(String baseUrl, String authKind, String secret, Set<String> pathAllowList)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.context.code;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubSourceFileReaderTest {

    private WireMockServer server;
    private GitHubSourceFileReader reader;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        reader = new GitHubSourceFileReader(new CodeContextConfig(
                "http://localhost:" + server.port(), "bearer", "CANARY-TOKEN", Set.of()));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void readsAFileAtTheGivenCommit() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/contents/src/Alpha.java"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/vnd.github.raw")
                        .withBody("class Alpha { }")));

        assertEquals("class Alpha { }", reader.read("acme/widgets", "src/Alpha.java", "cafe1234"));
    }

    @Test
    void anAbsentFileIsNullRatherThanAnError() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/contents/src/Missing.java"))
                .willReturn(aResponse().withStatus(404)));

        assertNull(reader.read("acme/widgets", "src/Missing.java", "cafe1234"));
    }

    @Test
    void anUnauthorizedResponseIsRaisedSoTheCredentialCanBeMarkedRejected() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/contents/src/Alpha.java"))
                .willReturn(aResponse().withStatus(401)));

        CodeContextApiException e = assertThrows(CodeContextApiException.class,
                () -> reader.read("acme/widgets", "src/Alpha.java", "cafe1234"));
        assertEquals(401, e.status());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-context-code:test --tests '*GitHubSourceFileReaderTest*'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Implement**

- `SourceFileReader` is an interface with `String read(String repo, String path, String commit)` and `String apiHost()`.
- `CodeContextApiException extends RuntimeException` carrying `int status()`, `boolean isNotFound()`, `boolean isUnauthorized()`, and `Integer retryAfterSeconds()`, mirroring the shape `ScmApiException` already uses.
- `GitHubSourceFileReader` GETs `{baseUrl}/repos/{repo}/contents/{path}?ref={commit}` with `Accept: application/vnd.github.raw`, using `spire-http`'s client so the SSRF and pinned-redirect guards apply. 404 returns null; any other non-2xx throws.

**Do not hand-roll a redirect loop** — `RedirectHandlingHasOneHomeTest` fails the build for a fourth one.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :spire-context-code:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-context-code
git commit -m "Read repository files at a commit for GitHub"
```

---

## Task 8: GitLab and Bitbucket readers

**Files:**
- Create: `spire-context-code/src/main/java/dev/codespire/context/code/GitLabSourceFileReader.java`
- Create: `spire-context-code/src/main/java/dev/codespire/context/code/BitbucketSourceFileReader.java`
- Test: `spire-context-code/src/test/java/dev/codespire/context/code/GitLabSourceFileReaderTest.java`
- Test: `spire-context-code/src/test/java/dev/codespire/context/code/BitbucketSourceFileReaderTest.java`

**Interfaces:**
- Consumes: `SourceFileReader`, `CodeContextConfig`, `CodeContextApiException`.
- Produces: two more `SourceFileReader` implementations.

- [ ] **Step 1: Write the failing tests**

Mirror `GitHubSourceFileReaderTest` exactly — the same three cases (reads a file, 404 is null, 401 raises) against each platform's URL shape:

- GitLab: `GET {baseUrl}/api/v4/projects/{urlEncoded(repo)}/repository/files/{urlEncoded(path)}/raw?ref={commit}`. Note **both** the project path and the file path are URL-encoded, including the slashes — a GitLab quirk that a test must pin, because getting it wrong yields a 404 that looks exactly like an absent file.
- Bitbucket: `GET {baseUrl}/repositories/{repo}/src/{commit}/{path}`.

Add one GitLab-specific case:

```java
@Test
void theProjectPathIsFullyUrlEncodedIncludingSlashes() {
    server.stubFor(get(urlPathEqualTo("/api/v4/projects/acme%2Fwidgets/repository/files/src%2FAlpha.java/raw"))
            .willReturn(aResponse().withStatus(200).withBody("class Alpha { }")));

    assertEquals("class Alpha { }", reader.read("acme/widgets", "src/Alpha.java", "cafe1234"));
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :spire-context-code:test`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Implement both readers**

Same structure as the GitHub reader; only the URL shape and the auth header differ. Both use `spire-http`.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :spire-context-code:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-context-code
git commit -m "Read repository files at a commit for GitLab and Bitbucket"
```

---

## Task 9: `SnippetExtractor`

**Files:**
- Create: `spire-context-code/src/main/java/dev/codespire/context/code/SnippetExtractor.java`
- Test: `spire-context-code/src/test/java/dev/codespire/context/code/SnippetExtractorTest.java`

**Interfaces:**
- Consumes: nothing outside the module.
- Produces: `SnippetExtractor.extract(String fileContent, String symbol, int maxBodyLines) -> String` (null when the symbol is not declared in the text).

- [ ] **Step 1: Write the failing tests**

```java
package dev.codespire.context.code;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetExtractorTest {

    private static final String FILE = """
            package dev.example.pricing;

            /** Prices one call at the rate in force when it happened. */
            public long chargeFor(TokenCount tokens, Rate rate) {
                long a = 1;
                long b = 2;
                long c = 3;
                return a + b + c;
            }
            """;

    @Test
    void theDeclarationAndItsDocCommentAreIncluded() {
        String snippet = SnippetExtractor.extract(FILE, "chargeFor", 40);

        assertTrue(snippet.contains("public long chargeFor(TokenCount tokens, Rate rate)"));
        assertTrue(snippet.contains("Prices one call at the rate in force"));
    }

    @Test
    void theSignatureSurvivesEvenWhenTheBodyIsClippedToNothing() {
        String snippet = SnippetExtractor.extract(FILE, "chargeFor", 1);

        // The high-value information lives in the signature and doc, so clipping must never
        // reach them — a snippet clipped to its signature is still useful; one clipped past it
        // is worse than absent.
        assertTrue(snippet.contains("public long chargeFor(TokenCount tokens, Rate rate)"));
        assertTrue(snippet.contains("...(truncated to fit the model context)"));
    }

    @Test
    void aSymbolNotDeclaredInTheTextIsNull() {
        assertNull(SnippetExtractor.extract(FILE, "someOtherThing", 40));
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :spire-context-code:test --tests '*SnippetExtractorTest*'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

Find the first line declaring the symbol (a line containing the symbol followed by `(` or `=` or preceded by `class`/`interface`/`record`/`const`/`function`/`type`), walk backwards over any immediately preceding comment block, then take lines forward until brace depth returns to zero or `maxBodyLines` is reached, appending the literal marker `...(truncated to fit the model context)` when clipped. Return null when no declaration line matches.

The truncation marker text must match `TokenBudget.TRUNCATION_MARKER` — three ASCII dots, not an ellipsis character.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :spire-context-code:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-context-code
git commit -m "Extract a symbol's declaration, doc and clipped body"
```

---

## Task 10: `CodeContextProvider`

**Files:**
- Create: `spire-context-code/src/main/java/dev/codespire/context/code/CodeContextProvider.java`
- Test: `spire-context-code/src/test/java/dev/codespire/context/code/CodeContextProviderTest.java`

**Interfaces:**
- Consumes: `ContextProvider`, `ContextRequest.codeReferences()`, `LanguageSupport`, `SourceFileReader`, `SnippetExtractor`.
- Produces: `CodeContextProvider implements ContextProvider` with `source() == "CODE"`, contributing `ContextItem` with `kind == "CODE_SNIPPET"`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.codespire.context.code;

import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeContextProviderTest {

    private final Map<String, String> files = new HashMap<>();
    private final AtomicInteger reads = new AtomicInteger();

    private final SourceFileReader reader = new SourceFileReader() {
        @Override
        public String read(String repo, String path, String commit) {
            reads.incrementAndGet();
            return files.get(path);
        }

        @Override
        public String apiHost() {
            return "code.example.invalid";
        }
    };

    private CodeContextProvider provider() {
        return new CodeContextProvider(reader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()));
    }

    private static ContextRequest request(CodeReferences refs) {
        return new ContextRequest("review::acme/widgets#1", new RepoRef("acme", "widgets"), 1,
                "cafe1234", Set.of(), Set.of(), null, null, refs);
    }

    @Test
    void resolvesAnImportedSymbolIntoACodeSnippet() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                class Alpha { long go() { return Pricer.chargeFor(1); } }
                """);
        files.put("src/main/java/dev/example/pricing/Pricer.java", """
                package dev.example.pricing;
                /** Returns millicents. */
                public long chargeFor(long tokens) { return tokens; }
                """);

        ContextContribution c = provider().contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Pricer", "chargeFor"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.OK, c.status());
        assertTrue(c.items().stream().allMatch(i -> "CODE_SNIPPET".equals(i.kind())));
        assertTrue(c.items().stream().anyMatch(i -> i.body().contains("Returns millicents")));
    }

    @Test
    void onlyImportsMatchingAChangedIdentifierAreFetched() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                import dev.example.unrelated.NeverTouched;
                class Alpha { }
                """);
        files.put("src/main/java/dev/example/pricing/Pricer.java", "public long chargeFor(long t) { return t; }");
        files.put("src/main/java/dev/example/unrelated/NeverTouched.java", "class NeverTouched { }");

        ContextContribution c = provider().contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Pricer"))))
                .toCompletableFuture().get();

        assertTrue(c.items().stream().noneMatch(i -> i.uri().contains("NeverTouched")));
    }

    @Test
    void oneFetchPerFileEvenWhenSeveralSymbolsResolveIntoIt() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                class Alpha { }
                """);
        files.put("src/main/java/dev/example/pricing/Pricer.java", """
                public long chargeFor(long t) { return t; }
                public long refundFor(long t) { return t; }
                """);
        reads.set(0);

        provider().contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"),
                Set.of("Pricer", "chargeFor", "refundFor")))).toCompletableFuture().get();

        // The changed file, then Pricer.java once — not once per symbol found in it.
        assertEquals(2, reads.get());
    }

    @Test
    void emptyCodeReferencesMeanTheProviderDoesNotSupportTheRequest() {
        assertFalse(provider().supports(request(CodeReferences.empty())));
    }

    @Test
    void aMissingDefinitionFileYieldsNoItemRatherThanAnError() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                class Alpha { }
                """);
        // Pricer.java deliberately absent — reader returns null for it.

        ContextContribution c = provider().contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Pricer"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.EMPTY, c.status());
        assertTrue(c.items().isEmpty());
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :spire-context-code:test --tests '*CodeContextProviderTest*'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

`contribute` does, in order:

1. read each changed path once, caching by path;
2. for each, pick the `LanguageSupport` by the path's extension and parse its imports;
3. keep imports whose `symbols()` intersect the request's identifiers;
4. for each kept import, walk `candidatePaths` and read the first that exists (cached);
5. for each identifier the import brought in, `SnippetExtractor.extract`, skipping nulls;
6. rank and cap (Step 4), then emit `ContextItem("CODE_SNIPPET", symbol + " — " + path, snippet, path)`.

Status is `OK` with items, `EMPTY` with none, `ERROR` only when a read raised something other than not-found.

- [ ] **Step 4: Add ranking and caps**

Add a test asserting order and the cap:

```java
@Test
void symbolsFromAddedLinesRankAboveOthersAndTheCapHolds() throws Exception {
    // ... a request whose identifiers resolve to more than MAX_SNIPPETS definitions ...
    ContextContribution c = provider().contribute(request(refs)).toCompletableFuture().get();
    assertEquals(CodeContextProvider.MAX_SNIPPETS, c.items().size());
}
```

Rank by: number of distinct changed files whose imports brought the symbol in (descending), then first appearance. Cap at `MAX_SNIPPETS = 20`.

Spec §6.4 also ranks added-line symbols above removed-only ones. `CodeReferences` does not currently distinguish them — **rung 1 ships without that tie-break**, and the rank comment must say so rather than implying the spec is fully implemented. Adding the distinction means a third set on `CodeReferences` and is deliberately deferred.

- [ ] **Step 5: Run to verify they pass**

Run: `./gradlew :spire-context-code:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add spire-context-code
git commit -m "Contribute code snippets for the symbols a diff touches"
```

---

## Task 11: The `{{code_context}}` prompt slot

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/llm/PromptCatalog.java` (REVIEW palette + default body)
- Modify: `spire-llm/src/main/java/dev/codespire/llm/ReviewPromptBuilder.java`
- Test: `spire-llm/src/test/java/dev/codespire/llm/CodeContextSlotTest.java`

**Interfaces:**
- Consumes: `ContextItem.kind()`.
- Produces: a `code_context` `PromptVariable` with `maxTokens = 6_000`; `ReviewPromptBuilder` renders `CODE_SNIPPET` items into it and everything else into `context`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.codespire.llm;

import dev.codespire.contract.review.ContextItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeContextSlotTest {

    private static String bodyOf(List<ContextItem> context) {
        return ReviewPromptBuilder.build(TestFixtures.pr(), TestFixtures.patches(), context)
                .prompt().user();
    }

    @Test
    void codeSnippetsRenderIntoTheirOwnSlotAndTicketsIntoTheirs() {
        String user = bodyOf(List.of(
                new ContextItem("JIRA_TICKET", "CANARY-1", "ticket body text", "https://example.invalid/1"),
                new ContextItem("CODE_SNIPPET", "chargeFor — src/Pricer.java", "long chargeFor()", "src/Pricer.java")));

        assertTrue(user.contains("ticket body text"));
        assertTrue(user.contains("long chargeFor()"));
    }

    @Test
    void anOversizedTicketCannotEvictCodeSnippets() {
        String huge = "x".repeat(200_000);
        String user = bodyOf(List.of(
                new ContextItem("JIRA_TICKET", "CANARY-1", huge, "https://example.invalid/1"),
                new ContextItem("CODE_SNIPPET", "chargeFor — src/Pricer.java", "long chargeFor()", "src/Pricer.java")));

        // The slots are budgeted independently. Sharing one slot is what would make this fail,
        // silently, on exactly the repositories with the richest ticket context.
        assertTrue(user.contains("long chargeFor()"));
    }

    @Test
    void anOversizedSnippetSetCannotEvictTicketContext() {
        String huge = "y".repeat(200_000);
        String user = bodyOf(List.of(
                new ContextItem("JIRA_TICKET", "CANARY-1", "ticket body text", "https://example.invalid/1"),
                new ContextItem("CODE_SNIPPET", "big — src/Big.java", huge, "src/Big.java")));

        assertTrue(user.contains("ticket body text"));
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :spire-llm:test --tests '*CodeContextSlotTest*'`
Expected: FAIL — snippets render into `{{context}}` and the eviction tests fail.

- [ ] **Step 3: Implement**

- In `PromptCatalog`, add to the REVIEW palette:
  `new PromptVariable("code_context", false, true, 6_000, "Definitions of the symbols this diff touches, retrieved from the repository.")`
- Add a `{{code_context}}` section to the built-in default REVIEW template body, fenced like the others.
- In `ReviewPromptBuilder.build`, partition `context` by `kind()`: `CODE_SNIPPET` renders into `code_context`, everything else into `context`. Reuse the existing `renderContext` shape for both.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :spire-llm:test :spire-contract:test`
Expected: PASS.

- [ ] **Step 5: Mutation-verify slot independence**

Temporarily render all items into the single `context` slot. Run the tests. Both eviction tests must fail. Revert.

- [ ] **Step 6: Assert the drift banner covers a template without the slot**

Add to the orchestrator's prompt-drift test suite a case where a stored customization lacks `{{code_context}}` while the built-in default has it, asserting the drift banner reports it. Without this, an operator with a customized template silently gets no code context.

Run: `./gradlew :spire-orchestrator:test --tests '*Drift*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add spire-contract spire-llm spire-orchestrator spire-review-worker
git commit -m "Give retrieved code its own prompt slot and budget"
```

---

## Task 12: Register the `code` context-provider type

**Files:**
- Modify: `spire-review-worker/.../adapters/WorkerContextClients.java`
- Modify: `spire-review-worker/.../pipeline/ContextWorker.java` (level-2 exclusion)
- Modify: the orchestrator's context-provider registry and `ContextProviderResource` (type validation, bearer-only, check endpoint)
- Test: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/ContextWorkerCodeExclusionTest.java`

**Interfaces:**
- Consumes: `ContextCredential(type="code", baseUrl, authKind, secret, projectKeys)`, `CodeContextProvider`.
- Produces: a working `code` provider type end to end.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.worker.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ContextWorkerCodeExclusionTest {

    /**
     * Level 2 mines level-1 item BODIES for new references. A code snippet is an item body, so a
     * ticket-shaped string inside a code comment would be fetched as a real ticket — turning a
     * source comment into a context fetch against a system the author never mentioned.
     */
    @Test
    void aTicketKeyInsideACodeCommentIsNotMinedAsAReference() {
        // Build a level-1 contribution containing:
        //   new ContextItem("CODE_SNIPPET", "alpha — src/Alpha.java",
        //           "// see PROJ-123 for background\nlong alpha() { return 1; }", "src/Alpha.java")
        // Run the level-2 mining step and assert the resulting reference set is empty.
        assertFalse(minedReferences().contains("PROJ-123"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-review-worker:test --tests '*ContextWorkerCodeExclusionTest*'`
Expected: FAIL — the miner reads every item body including code snippets.

- [ ] **Step 3: Exclude code snippets from level-2 mining**

In `ContextWorker.collect`, filter out items whose `kind()` is `CODE_SNIPPET` before handing bodies to `contextReferences` for the next level. Add a comment naming the hazard, because the line looks like an optimization and is a correctness fix.

- [ ] **Step 4: Wire the provider into the composition root**

In `WorkerContextClients.forCommand`, add:

```java
case "code" -> providers.add(new CodeContextProvider(
        readerFor(cred), LANGUAGES, CodeContextConfig.parsePathAllowList(cred.projectKeys())));
```

where `readerFor` selects the platform reader from the credential's baseUrl host, and `LANGUAGES` is the same list `WorkerCodeReferences` holds.

**Use the three-argument constructor.** `CodeContextProvider` also has a two-argument overload that defaults the allow-list to empty, meaning *unrestricted*. Task 10 added `pathAllowList` enforcement precisely because a review found nothing enforced it; calling the short overload here would make the control dead code again while Settings presents operators a field that silently does nothing. A test must assert the constructed provider carries the credential's allow-list.

- [ ] **Step 5: Add the registry type**

Add `code` to `ContextProviderResource.SUPPORTED_TYPES` (line ~60, currently `Set.of("jira", "confluence", "github-issues", "gitlab-issues")`) and to `BEARER_ONLY_TYPES` (line ~69), and give `ContextKeyValidator` its check path (all three platforms' raw-content APIs are bearer-token-only, the same rule `github-issues` and `gitlab-issues` already follow). Give it a Check endpoint that reads a known-present path — the repository root `README.md`, treating 404 as a pass, since the check is of the credential and not of the file.

- [ ] **Step 6: Put the reader behind the per-host circuit breaker**

Spec §8.1. The wrap happens in the **worker**, not in `spire-context-code` — `ProviderCircuits` is worker-owned and ADR-021 forbids the Apache-2.0 module depending on it. This is the `CircuitBreakingLlmProvider` arrangement repeated: wrap at the composition root where the concrete instance is built.

```java
@Test
void aNotFoundDoesNotCountTowardTheCircuit() {
    // 404 is the normal case for a moved or deleted file. Counting it would let a repository
    // with reorganized paths open the circuit against its own reviews — and the circuit is
    // shared with the SCM adapters, so that would pause reviewing, not just code context.
    for (int i = 0; i < 10; i++) {
        assertNull(wrappedReader.read("acme/widgets", "src/Gone.java", "cafe1234"));
    }
    assertFalse(circuits.isOpen("code.example.invalid"));
}

@Test
void repeatedServerFailuresOpenTheCircuit() {
    // 5 failures opens for 30s, per ProviderCircuits' existing policy.
    for (int i = 0; i < 5; i++) {
        assertThrows(CodeContextApiException.class,
                () -> failingReader.read("acme/widgets", "src/Alpha.java", "cafe1234"));
    }
    assertTrue(circuits.isOpen("code.example.invalid"));
}
```

Key the circuit on `SourceFileReader.apiHost()`, for the reason `DiffSource.apiHost()` is not a `default` method: two self-managed instances of one platform are independent, and collapsing them onto one key pauses a healthy instance because a different one is down.

- [ ] **Step 7: Run the tests**

Run: `./gradlew testFast && ./gradlew :spire-review-worker:test :spire-orchestrator:test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add spire-review-worker spire-orchestrator
git commit -m "Register the code context provider and fence its snippets"
```

---

## Task 13: Settings UI for the `code` provider type

**Files:**
- Modify: `spire-ui/src/components/SettingsContextProviders.tsx`
- Modify: `spire-ui/src/api.ts` (the context-provider type union)
- Test: `spire-ui/src/components/SettingsContextProviders.form.test.tsx`

**Interfaces:**
- Consumes: `/api/context-providers` with `type: 'code'`.
- Produces: a `code` option in the type picker, bearer-only auth, and a working Check button.

- [ ] **Step 1: Write the failing test**

```tsx
it('offers the code provider type and forces bearer auth', async () => {
  render(<SettingsContextProviders />)
  await userEvent.selectOptions(await screen.findByLabelText(/type/i), 'code')

  // Bearer-only types must not offer a username field — sending one would be
  // silently ignored by the API and mislead the operator into thinking it mattered.
  expect(screen.queryByLabelText(/username/i)).not.toBeInTheDocument()
})

it('does not send an empty secret when editing an existing provider', async () => {
  // The blank-secret-on-edit rule: sending secret: '' would wipe the stored token.
  render(<SettingsContextProviders />)
  // ... open an existing 'code' provider, change only the base URL, save ...
  expect(fetchMock.mock.calls.at(-1)?.[1]?.body).not.toContain('"secret":""')
})
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd spire-ui && npx vitest run SettingsContextProviders`
Expected: FAIL — `code` is not an option.

- [ ] **Step 3: Implement**

Add `'code'` to the `ContextProviderType` union in `api.ts`, add the option to the picker with the label "Repository code", and include it in the existing bearer-only set so the username field is hidden. The blank-secret-on-edit rule already exists in this component — confirm the new type goes through it rather than around it.

Use lucide-react icons if any icon is added; never emoji.

- [ ] **Step 4: Run to verify they pass**

Run: `cd spire-ui && npx vitest run && npx tsc --noEmit`
Expected: PASS, `tsc` silent.

- [ ] **Step 5: Commit**

```bash
git add spire-ui
git commit -m "Add the repository code provider to context settings"
```

---

## Task 14: Diagnostics — counts that separate "nothing to do" from "broken"

**Files:**
- Modify: `spire-context-code/src/main/java/dev/codespire/context/code/CodeContextProvider.java`
- Test: `spire-context-code/src/test/java/dev/codespire/context/code/CodeContextDiagnosticsTest.java`

**Interfaces:**
- Consumes: the provider's own resolution pipeline.
- Produces: `CodeContextProvider.Counts(int extracted, int resolved, int contributed, int droppedForBudget)` and `CodeContextProvider.Resolved(ContextContribution contribution, Counts counts)`, returned by a package-visible `resolve(ContextRequest)` that `contribute` delegates to. Counts are returned, never held as mutable provider state — one provider instance serves concurrent requests.

- [ ] **Step 1: Write the failing test**

```java
@Test
void countsDistinguishNothingToDoFromSystematicallyBroken() {
    // Nothing to do: no identifiers at all. Correct, and uninteresting.
    CodeContextProvider.Counts none = provider().resolve(request(CodeReferences.empty())).counts();
    assertEquals(0, none.extracted());

    // Broken: plenty extracted, none resolved. Both states report ContribStatus.EMPTY, which is
    // why EMPTY alone cannot be an attention row and why these counts have to exist — otherwise
    // a systematically broken resolver is indistinguishable from a YAML-only diff.
    files.put("src/main/java/dev/example/Alpha.java", "package dev.example;\nclass Alpha { }\n");
    CodeContextProvider.Counts broken = provider().resolve(request(new CodeReferences(
            Set.of("src/main/java/dev/example/Alpha.java"),
            Set.of("Pricer", "chargeFor", "refundFor")))).counts();

    assertTrue(broken.extracted() > 0);
    assertEquals(0, broken.contributed());
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :spire-context-code:test --tests '*CodeContextDiagnosticsTest*'`
Expected: FAIL — `Counts` and `resolve` do not exist.

- [ ] **Step 3: Implement and log**

Add the `Counts` and `Resolved` records, move the body of `contribute` into `resolve(ContextRequest)`, and have `contribute` return `completedFuture(resolve(request).contribution())`. In `ContextWorker`, log the counts at INFO under the existing reviewId MDC when the source is `CODE`. Counts carry no source text, so they are safe to log; snippet bodies must never be.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :spire-context-code:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-context-code spire-review-worker
git commit -m "Record whether code context found nothing or resolved nothing"
```

---

## Task 15: End-to-end seam, full verification, and docs

**Files:**
- Modify: `spire-review-worker/src/test/java/dev/codespire/worker/pipeline/ReviewWorkerTest.java`
- Modify: `CLAUDE.md` (Status section), `docs/ROADMAP.md` (P3 rung 1 delivered)
- Modify: `docs/SMOKE-TEST.md` (a new runbook mode for manual verification)

**Interfaces:**
- Consumes: everything above.
- Produces: proof that a snippet reaches the model, and current status docs.

- [ ] **Step 1: Extend the existing seam test**

`ReviewWorkerTest.assembledContextReachesThePromptSentToTheModel` already fakes a `BlobStore` holding an `AssembledContext` and asserts the captured `Prompt` contains the item's title and body, and it was confirmed to discriminate when `contextRef` is null. Add a sibling:

```java
@Test
void aCodeSnippetReachesThePromptSentToTheModel() throws Exception {
    // AssembledContext holding one ContextItem("CODE_SNIPPET", "chargeFor — src/Pricer.java",
    //         "public long chargeFor(long tokens) { return tokens; }", "src/Pricer.java")
    Prompt sent = capturedPrompt();
    assertTrue(sent.user().contains("public long chargeFor(long tokens)"));
}
```

- [ ] **Step 2: Verify it discriminates**

Temporarily make `ReviewPromptBuilder` render `code_context` as the empty string. Run the test. It must fail. Revert.

- [ ] **Step 3: Run the full suite, proving a real run**

```bash
./gradlew testFast --rerun-tasks
./gradlew testServices --rerun-tasks
cd spire-ui && npx vitest run && npx tsc --noEmit
```

Record the actual counts. A report of "up-to-date" is a cached pass, not a run — that is why `--rerun-tasks` is here. Do not run two Gradle builds against this directory concurrently; they corrupt the shared `spire-contract` jar.

- [ ] **Step 4: Update the status docs**

- `CLAUDE.md`: a Status bullet for P3 rung 1, naming what shipped, the two languages, and that rung 2 is gated. Include the measured test counts.
- `docs/ROADMAP.md`: mark P3 rung 1 delivered with the date; leave rung 2 in the open table with its evidence gate.
- `docs/SMOKE-TEST.md`: a new mode — register a `code` context provider, open a PR whose diff calls an imported symbol, confirm the snippet appears in the review's Context card and that the finding reflects it.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md docs spire-review-worker
git commit -m "Prove a retrieved snippet reaches the model and record status"
```

---

## Post-plan: the evidence gate

Rung 2 is **not** authorized by finishing this plan. Before any of `worker.code_symbol` is built, run the measurement fixed in spec §9:

1. Take a set of real pull requests this deployment has already reviewed.
2. Re-run each review with recorded controls, once with code context and once without.
3. Diff the findings.

**Pass:** at least one new finding judged correct by the operator, and no increase in false positives.
**Fail:** repository context does not move findings — P3 stops here, having cost a fraction of its estimate to learn.

Do not begin rung 2 on the strength of the feature working. Working and helping are different claims, and only the second one justifies a table.
