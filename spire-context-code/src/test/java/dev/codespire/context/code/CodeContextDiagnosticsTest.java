package dev.codespire.context.code;

import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContextResolutionCounts;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ContextResolutionCounts} — the diagnostic that distinguishes a YAML-only diff
 * (nothing to extract, correct and uninteresting) from a systematically broken resolver (plenty
 * extracted, nothing resolved), a distinction {@link dev.codespire.contract.review.ContribStatus#EMPTY}
 * alone cannot make since both states report it identically.
 */
class CodeContextDiagnosticsTest {

    private final Map<String, String> files = new HashMap<>();

    private final SourceFileReader reader = new SourceFileReader() {
        @Override
        public String read(String repo, String path, String commit) {
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
    void countsDistinguishNothingToDoFromSystematicallyBroken() {
        // Nothing to do: no identifiers at all. Correct, and uninteresting.
        ContextResolutionCounts none = provider().resolve(request(CodeReferences.empty())).counts();
        assertEquals(0, none.extracted());

        // Broken: plenty extracted, none resolved. Both states report ContribStatus.EMPTY, which is
        // why EMPTY alone cannot be an attention row and why these counts have to exist — otherwise
        // a systematically broken resolver is indistinguishable from a YAML-only diff.
        files.put("src/main/java/dev/example/Alpha.java", "package dev.example;\nclass Alpha { }\n");
        ContextResolutionCounts broken = provider().resolve(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"),
                Set.of("Pricer", "chargeFor", "refundFor")))).counts();

        assertTrue(broken.extracted() > 0);
        assertEquals(0, broken.contributed());
    }

    @Test
    void resolvedCandidatesBeyondTheCapCountAsDroppedForBudget() {
        Set<String> changedPaths = new java.util.HashSet<>();
        Set<String> identifiers = new java.util.HashSet<>();

        // 21 changed files each import their own unique symbol — one more than MAX_SNIPPETS, so the
        // cap must drop exactly one resolved candidate rather than silently absorbing it.
        for (int i = 0; i < CodeContextProvider.MAX_SNIPPETS + 1; i++) {
            String n = String.format("%02d", i);
            String changedPath = "src/main/java/dev/example/a/A" + n + ".java";
            String symbol = "Sym" + n;
            String defPath = "src/main/java/dev/example/d/" + symbol + ".java";

            files.put(changedPath, "package dev.example.a;\n"
                    + "import dev.example.d." + symbol + ";\n"
                    + "class A" + n + " { }\n");
            files.put(defPath, "public long " + symbol + "() { return 1; }");

            changedPaths.add(changedPath);
            identifiers.add(symbol);
        }

        ContextResolutionCounts counts = provider()
                .resolve(request(new CodeReferences(changedPaths, identifiers)))
                .counts();

        assertEquals(CodeContextProvider.MAX_SNIPPETS + 1, counts.resolved());
        assertEquals(CodeContextProvider.MAX_SNIPPETS, counts.contributed());
        assertEquals(1, counts.droppedForBudget());
    }
}
