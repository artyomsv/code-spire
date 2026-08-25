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
