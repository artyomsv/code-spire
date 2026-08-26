package dev.codespire.context.code;

import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
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

    @Test
    void symbolsFromAddedLinesRankAboveOthersAndTheCapHolds() throws Exception {
        Set<String> changedPaths = new HashSet<>();
        Set<String> identifiers = new HashSet<>();
        identifiers.add("Popular");
        files.put("src/main/java/dev/example/pop/Popular.java", "public long Popular() { return 1; }");

        // 20 changed files each import their own unique symbol; the first two ALSO import "Popular",
        // so Popular is brought in by two distinct changed files while every SymNN is brought in by
        // only one — that is what must rank Popular above all 20 of them despite arriving last.
        for (int i = 0; i < 20; i++) {
            String n = String.format("%02d", i);
            String changedPath = "src/main/java/dev/example/a/A" + n + ".java";
            String symbol = "Sym" + n;
            String defPath = "src/main/java/dev/example/d/" + symbol + ".java";

            StringBuilder content = new StringBuilder("package dev.example.a;\n");
            content.append("import dev.example.d.").append(symbol).append(";\n");
            if (i < 2) {
                content.append("import dev.example.pop.Popular;\n");
            }
            content.append("class A").append(n).append(" { }\n");

            files.put(changedPath, content.toString());
            files.put(defPath, "public long " + symbol + "() { return 1; }");

            changedPaths.add(changedPath);
            identifiers.add(symbol);
        }

        ContextContribution c = provider()
                .contribute(request(new CodeReferences(changedPaths, identifiers)))
                .toCompletableFuture().get();

        assertEquals(CodeContextProvider.MAX_SNIPPETS, c.items().size());
        // Ranked first (two contributing changed files) — must survive the cap.
        assertTrue(c.items().stream().anyMatch(i -> i.uri().contains("Popular.java")));
        // First-appearance tie-break among the count-1 candidates keeps the earliest ones...
        assertTrue(c.items().stream().anyMatch(i -> i.uri().contains("Sym00.java")));
        // ...and drops the one that would push the total past MAX_SNIPPETS.
        assertTrue(c.items().stream().noneMatch(i -> i.uri().contains("Sym19.java")));
    }

    @Test
    void pathAllowListPermitsAMatchingCandidatePath() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                class Alpha { }
                """);
        files.put("src/main/java/dev/example/pricing/Pricer.java", "public long chargeFor(long t) { return t; }");

        CodeContextProvider allowed = new CodeContextProvider(reader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()),
                Set.of("src/main/java/dev/example/pricing/"));

        ContextContribution c = allowed.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Pricer", "chargeFor"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.OK, c.status());
        assertTrue(c.items().stream().anyMatch(i -> i.uri().contains("Pricer.java")));
    }

    @Test
    void pathAllowListRejectsAnUnlistedCandidateBeforeAnyFetch() throws Exception {
        Set<String> attemptedPaths = new HashSet<>();
        SourceFileReader trackingReader = new SourceFileReader() {
            @Override
            public String read(String repo, String path, String commit) {
                attemptedPaths.add(path);
                return files.get(path);
            }

            @Override
            public String apiHost() {
                return "code.example.invalid";
            }
        };
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                class Alpha { }
                """);
        files.put("src/main/java/dev/example/pricing/Pricer.java", "public long chargeFor(long t) { return t; }");

        CodeContextProvider restricted = new CodeContextProvider(trackingReader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()),
                Set.of("src/main/java/dev/example/other/"));

        ContextContribution c = restricted.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Pricer", "chargeFor"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.EMPTY, c.status());
        assertTrue(c.items().isEmpty());
        // The disallowed candidate must never even be attempted — only the changed file itself is,
        // since changed paths are not subject to the allow-list.
        assertEquals(Set.of("src/main/java/dev/example/Alpha.java"), attemptedPaths);
    }

    @Test
    void aPerFileFetchFailureSkipsThatFileButOthersStillContribute() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                import dev.example.discount.Discounter;
                class Alpha { }
                """);
        files.put("src/main/java/dev/example/pricing/Pricer.java", "public long chargeFor(long t) { return t; }");
        // Discounter.java exists, but its reads always throw — a 5xx or rate limit, not a 404.

        SourceFileReader flakyReader = new SourceFileReader() {
            @Override
            public String read(String repo, String path, String commit) {
                if (path.contains("Discounter")) {
                    throw new RuntimeException("simulated 503");
                }
                return files.get(path);
            }

            @Override
            public String apiHost() {
                return "code.example.invalid";
            }
        };

        CodeContextProvider provider = new CodeContextProvider(flakyReader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()));

        ContextContribution c = provider.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"),
                Set.of("Pricer", "chargeFor", "Discounter", "applyDiscount"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.OK, c.status());
        assertTrue(c.items().stream().anyMatch(i -> i.uri().contains("Pricer.java")));
        assertTrue(c.items().stream().noneMatch(i -> i.uri().contains("Discounter.java")));
    }

    @Test
    void aFetchFailureWithNothingElseToShowIsReportedAsError() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.discount.Discounter;
                class Alpha { }
                """);

        SourceFileReader flakyReader = new SourceFileReader() {
            @Override
            public String read(String repo, String path, String commit) {
                if (path.contains("Discounter")) {
                    throw new RuntimeException("simulated 503");
                }
                return files.get(path);
            }

            @Override
            public String apiHost() {
                return "code.example.invalid";
            }
        };

        CodeContextProvider provider = new CodeContextProvider(flakyReader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()));

        ContextContribution c = provider.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Discounter"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.ERROR, c.status());
        assertTrue(c.items().isEmpty());
    }
}
