# The code provider's extension→language map is duplicated with no build guard

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-context-code/src/main/java/dev/codespire/context/code/CodeContextProvider.java` (`LANGUAGE_BY_EXTENSION`), `spire-diff/src/main/java/dev/codespire/diff/Languages.java` (`BY_EXTENSION`, `of`) |
| Found during | PR 63 code review — repository knowledge base rung 1 |
| Date | 2026-08-26 |

## Issue

Deciding a file's language from its extension happens twice on the two halves of the same pipeline,
against two independently maintained maps:

```java
// CodeContextProvider.LANGUAGE_BY_EXTENSION (spire-context-code) — decides which imports get parsed
private static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.of(
        "java", "java",
        "ts", "typescript", "tsx", "typescript",
        "js", "javascript", "jsx", "javascript");

// Languages.BY_EXTENSION (spire-diff) — decides FilePatch.language(), which WorkerCodeReferences
// keys off to decide which files get identifiers extracted at all
```

`WorkerCodeReferences.inDiff` keys off `patch.language()` (set from `Languages.of`) while
`CodeContextProvider.languageFor` keys off its own map. The two therefore have to agree about which
extensions are supported, and nothing checks that they do.

The duplication is deliberate and documented in the code: `spire-context-code` is framework-free and
has no dependency on `spire-diff`, and `Languages.BY_EXTENSION` covers many languages this module
ships no `LanguageSupport` for, so it could not simply be reused wholesale. As with
`techdebt/global/3-2-code-platform-detection-duplicated-with-no-drift-guard.md`, the problem is not
that two copies exist but that **nothing fails when they drift**.

## Risks

Divergence is silent and directional, so each direction fails differently and neither shows an error:

- An extension in `Languages` but not in `LANGUAGE_BY_EXTENSION`: identifiers are extracted from the
  file's changed lines and ride the wire, but its imports are never parsed, so nothing those imports
  point at is ever fetched. The review looks like one where the file simply had no dependencies.
- An extension in `LANGUAGE_BY_EXTENSION` but not in `Languages`: the file's imports would be parsed,
  but `patch.language()` is null so no identifiers were extracted from it in the first place, and the
  import-versus-identifier intersection matches nothing. Same symptom, opposite cause.

Both read as "the code provider contributed nothing for this file", which is also what a genuinely
dependency-free file looks like — so neither is distinguishable from correct behaviour without
reading the two maps side by side. Today they agree (the three languages `spire-context-code` ships
`LanguageSupport` implementations for are all present in `Languages`), so this is a drift risk rather
than a live defect, which is why it is Medium.

## Suggested Solutions

1. **Add a `spire-arch` test asserting every extension in `LANGUAGE_BY_EXTENSION` maps to the same
   language tag in `Languages.BY_EXTENSION`.** The scan-the-source idiom
   `RedirectHandlingHasOneHomeTest` and `PureModulesAreFrameworkFreeTest` already use fits exactly,
   and `spire-arch` may depend on both modules where neither may depend on the other. Cheapest fix,
   and it detects drift in both directions.
2. **Derive the provider's map from its own `LanguageSupport` list instead of a literal** —
   `LanguageSupport` already reports `languages()`, so the only thing genuinely missing is
   extension→tag, which could move onto the SPI. Removes one of the two maps rather than checking it,
   but widens the SPI for one caller.
3. Leave as is. Defensible only while the supported-language set stays at three and nobody adds a
   fourth to one map without the other.
