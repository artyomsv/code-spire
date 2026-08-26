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

    /**
     * The keyword list was partial, and a partial list is not merely untidy: every token it lets
     * through is tried against every resolved definition file in the quadratic extraction loop, so
     * {@code var}, {@code switch}, {@code case} and friends inflated that loop on every Java diff
     * while resolving nothing (PR 63 review). Each token below appeared in a real omission.
     */
    @Test
    void theOnceMissingKeywordsAreNotIdentifiersEither() {
        FilePatch p = patch(
                new DiffLine(LineType.ADDED, null, 1, "var mode = pricingMode;"),
                new DiffLine(LineType.ADDED, null, 2, "switch (mode) { case METERED: break; default: }"),
                new DiffLine(LineType.ADDED, null, 3, "abstract float ratio;"),
                new DiffLine(LineType.ADDED, null, 4, "char c; byte b; short s;"),
                new DiffLine(LineType.ADDED, null, 5, "if (mode instanceof String) { }"));

        Set<String> found = support.identifiersIn(p);

        for (String keyword : List.of("var", "String", "switch", "case", "break", "default",
                "instanceof", "abstract", "float", "char", "byte", "short")) {
            assertFalse(found.contains(keyword), keyword + " is not a symbol worth resolving");
        }
        // The real symbols on those same lines still come through — this is a narrowing, not a mute.
        assertTrue(found.contains("pricingMode"));
        assertTrue(found.contains("METERED"));
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
