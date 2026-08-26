package dev.codespire.context.code;

import dev.codespire.contract.port.LanguageSupport;
import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.FilePatch;
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
        assertTrue(c.items().stream().allMatch(i -> ContextItem.CODE_SNIPPET.equals(i.kind())));
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

        ContextContribution c = provider().contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"),
                Set.of("Pricer", "chargeFor", "refundFor")))).toCompletableFuture().get();

        // The changed file, then Pricer.java once — not once per symbol found in it.
        assertEquals(2, reads.get());
        // C1: both declarations found in one file must survive as distinct items — the aggregator's
        // cross-source dedup keys on `uri`, so `rankAndCap` must not collapse them onto one address.
        assertEquals(2, c.items().size());
        assertTrue(c.items().stream().anyMatch(i ->
                i.uri().equals("src/main/java/dev/example/pricing/Pricer.java#chargeFor")));
        assertTrue(c.items().stream().anyMatch(i ->
                i.uri().equals("src/main/java/dev/example/pricing/Pricer.java#refundFor")));
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

    /**
     * Named for what it asserts. It used to be called {@code symbolsFromAddedLinesRankAboveOthers…},
     * a rule {@code rankAndCap}'s own javadoc says rung 1 deliberately does NOT implement —
     * {@code CodeReferences} carries no added/removed split to rank on — so anyone grepping for that
     * tie-break found a green test claiming it existed (PR 63 review).
     */
    @Test
    void aDefinitionBroughtInByMoreChangedFilesRanksHigherAndTheCapHolds() throws Exception {
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

    @Test
    void pathAllowListRejectsATraversalCandidateEvenWhenItWouldOtherwiseMatchThePrefix() throws Exception {
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
        files.put("src/main/java/dev/example/Alpha.java", "package dev.example;\nclass Alpha { }\n");
        files.put("etc/passwd", "root:x:0:0:root:/root:/bin/bash");

        // A naive `startsWith("src/allowed")` would approve this — it IS textually prefixed that
        // way — even though the trailing ".." segments walk it out of the allowed tree entirely.
        LanguageSupport escapingSupport = new FixedImportLanguageSupport("Escaper",
                List.of("src/allowed/../../etc/passwd"));

        CodeContextProvider restricted = new CodeContextProvider(trackingReader,
                List.of(escapingSupport), Set.of("src/allowed"));

        ContextContribution c = restricted.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Escaper"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.EMPTY, c.status());
        assertTrue(c.items().isEmpty());
        assertFalse(attemptedPaths.contains("src/allowed/../../etc/passwd"));
    }

    @Test
    void pathAllowListRejectsALeadingSlashCandidateEvenWhenItWouldOtherwiseMatchThePrefix() throws Exception {
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
        files.put("src/main/java/dev/example/Alpha.java", "package dev.example;\nclass Alpha { }\n");
        files.put("/etc/passwd", "root:x:0:0:root:/root:/bin/bash");

        // An allow-list entry that itself starts with "/" would let a naive `startsWith` approve
        // this absolute-looking candidate too — the leading-slash guard rejects it regardless.
        LanguageSupport escapingSupport = new FixedImportLanguageSupport("Escaper", List.of("/etc/passwd"));

        CodeContextProvider restricted = new CodeContextProvider(trackingReader,
                List.of(escapingSupport), Set.of("/etc"));

        ContextContribution c = restricted.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Escaper"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.EMPTY, c.status());
        assertTrue(c.items().isEmpty());
        assertFalse(attemptedPaths.contains("/etc/passwd"));
    }

    @Test
    void pathAllowListRejectsAPercentEncodedTraversalCandidateEvenWhenItWouldOtherwiseMatchThePrefix()
            throws Exception {
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
        files.put("src/main/java/dev/example/Alpha.java", "package dev.example;\nclass Alpha { }\n");
        files.put("deploy/keys", "super-secret-key-material");

        // A literal ".." is normalised away and caught by isTraversal directly; the percent-encoded
        // form (I3, rung-1 final review) is the one that would otherwise sail past isTraversal (which
        // compares raw, un-decoded segments) AND the "src/allowed" prefix check, only to be decoded by
        // the platform on arrival.
        LanguageSupport escapingSupport = new FixedImportLanguageSupport("Escaper",
                List.of("src/allowed/%2e%2e/%2e%2e/deploy/keys"));

        CodeContextProvider restricted = new CodeContextProvider(trackingReader,
                List.of(escapingSupport), Set.of("src/allowed"));

        ContextContribution c = restricted.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Escaper"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.EMPTY, c.status());
        assertTrue(c.items().isEmpty());
        // The reader must never even be called for the rejected candidate — not merely "no items".
        assertFalse(attemptedPaths.contains("src/allowed/%2e%2e/%2e%2e/deploy/keys"));
    }

    @Test
    void aFileFetchedBeforeTheDeadlineStillContributesWhenALaterFetchRunsOutOfBudget() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                import dev.example.discount.Discounter;
                class Alpha { }
                """);
        files.put("src/main/java/dev/example/pricing/Pricer.java", "public long chargeFor(long t) { return t; }");
        files.put("src/main/java/dev/example/discount/Discounter.java",
                "public long applyDiscount(long t) { return t; }");

        // Pricer.java's fetch is slow enough, on its own, to burn through the 500ms test deadline
        // before Discounter.java's fetch is even attempted — proving I2's "whatever resolved before
        // the deadline still ships" rather than losing the whole contribution. The deadline and sleep
        // are scaled well above the timings actually being asserted (rather than the tightest values
        // that would still pass) so the test doesn't flake under load: too tight a margin here failed
        // the "must still ship" assertion whenever something on the machine stalled before the first
        // fetch (final rung-1 re-review).
        SourceFileReader slowForPricerReader = new SourceFileReader() {
            @Override
            public String read(String repo, String path, String commit) {
                if (path.contains("Pricer")) {
                    sleep(1000);
                }
                return files.get(path);
            }

            @Override
            public String apiHost() {
                return "code.example.invalid";
            }
        };

        CodeContextProvider provider = new CodeContextProvider(slowForPricerReader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()), Set.of(), 500);

        ContextContribution c = provider.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"),
                Set.of("Pricer", "chargeFor", "Discounter", "applyDiscount"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.OK, c.status());
        assertTrue(c.items().stream().anyMatch(i -> i.uri().contains("Pricer.java")),
                "resolved before the deadline — must still ship");
        assertTrue(c.items().stream().noneMatch(i -> i.uri().contains("Discounter.java")),
                "the deadline was already spent by the time this candidate would have been fetched");
    }

    @Test
    void aDeadlineAlreadySpentBeforeAnyFetchIsReportedAsErrorRatherThanAMisleadingEmpty() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                class Alpha { }
                """);
        files.put("src/main/java/dev/example/pricing/Pricer.java", "public long chargeFor(long t) { return t; }");

        // A negative deadline is already in the past the instant resolve() computes it, so even the
        // very first fetch attempted afterward is guaranteed to observe it as spent — no timing race.
        CodeContextProvider provider = new CodeContextProvider(reader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()), Set.of(), -1);

        ContextContribution c = provider.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Pricer", "chargeFor"))))
                .toCompletableFuture().get();

        assertEquals(ContribStatus.ERROR, c.status());
        assertTrue(c.items().isEmpty());
    }

    /**
     * Extraction — {@code definitionFiles × identifiers} full-file regex scans — used to run in full
     * however long it took, and {@code CompletableFuture.cancel} does not interrupt, so the aggregator
     * giving up did not stop it either (M1/M2, PR 63 review). Here the fetch budget is generous and
     * every fetch SUCCEEDS; only extraction's own budget is spent, which is the one state that shows
     * the check is extraction's rather than the fetches'.
     */
    @Test
    void extractionStopsAtItsOwnBudgetEvenThoughEveryFetchSucceeded() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                class Alpha { }
                """);
        files.put("src/main/java/dev/example/pricing/Pricer.java", "public long chargeFor(long t) { return t; }");

        AtomicInteger reads = new AtomicInteger();
        SourceFileReader countingReader = new SourceFileReader() {
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

        // A negative extraction budget is already in the past the instant extraction computes it, so
        // the first symbol observes it as spent — no timing race. The 10s fetch deadline is untouched.
        CodeContextProvider provider = new CodeContextProvider(countingReader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()), Set.of(), 10_000, -1);

        ContextContribution c = provider.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Pricer", "chargeFor"))))
                .toCompletableFuture().get();

        assertEquals(2, reads.get(), "both the changed file and the definition file were fetched");
        assertTrue(c.items().isEmpty(), "extraction must not run on a budget that is already gone");
        assertEquals(ContribStatus.ERROR, c.status(), "a budget shortfall is reported, not hidden as EMPTY");
    }

    /**
     * L1 (PR 63 review). {@code isAllowed}'s javadoc calls the traversal/percent-encoding check
     * unconditional, and the changed-file path was the one caller that skipped it. The allow-list
     * exemption stays — a changed file is by definition part of the repository under review — but the
     * shape check applies to every path this provider fetches.
     */
    @Test
    void aTraversalShapedChangedPathIsNeverFetched() throws Exception {
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
        files.put("../../etc/passwd.java", "root:x:0:0:root:/root:/bin/bash");
        files.put("src/main/java/dev/example/Alpha.java", """
                package dev.example;
                import dev.example.pricing.Pricer;
                class Alpha { }
                """);
        files.put("src/main/java/dev/example/pricing/Pricer.java", "public long chargeFor(long t) { return t; }");

        CodeContextProvider provider = new CodeContextProvider(trackingReader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()));

        ContextContribution c = provider.contribute(request(new CodeReferences(
                Set.of("../../etc/passwd.java", "src/main/java/dev/example/Alpha.java"),
                Set.of("Pricer", "chargeFor")))).toCompletableFuture().get();

        assertFalse(attemptedPaths.contains("../../etc/passwd.java"));
        // The legitimate changed file is still read, and still resolves — the guard rejects a shape,
        // not the changed-path list.
        assertTrue(attemptedPaths.contains("src/main/java/dev/example/Alpha.java"));
        assertTrue(c.items().stream().anyMatch(i -> i.uri().contains("Pricer.java")));
    }

    /**
     * Extraction is the quadratic step, so the identifier list it walks is capped independently of
     * {@code CodeReferences}'s (much larger) wire cap. Identifiers are sorted before the cut, so which
     * ones survive is deterministic — this asserts that, not merely that "some" were dropped.
     */
    @Test
    void identifiersPastTheExtractionCapAreNotTriedAgainstADefinitionFile() throws Exception {
        files.put("src/main/java/dev/example/Alpha.java", "package dev.example;\nclass Alpha { }\n");
        files.put("src/def/Defs.java", """
                public long aaaEarly(long t) { return t; }
                public long zzzLate(long t) { return t; }
                """);

        Set<String> identifiers = new HashSet<>(Set.of("Escaper", "aaaEarly", "zzzLate"));
        // Sort between "aaaEarly" and "zzzLate", and there are enough of them to push "zzzLate" past
        // the cap while "aaaEarly" stays comfortably inside it.
        for (int i = 0; i < CodeContextProvider.MAX_EXTRACTION_IDENTIFIERS + 50; i++) {
            identifiers.add(String.format("m%04d", i));
        }

        LanguageSupport fixed = new FixedImportLanguageSupport("Escaper", List.of("src/def/Defs.java"));
        CodeContextProvider provider = new CodeContextProvider(trackingReaderOverFiles(), List.of(fixed));

        ContextContribution c = provider.contribute(request(new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), identifiers)))
                .toCompletableFuture().get();

        assertTrue(c.items().stream().anyMatch(i -> i.uri().endsWith("#aaaEarly")),
                "an identifier inside the cap still resolves");
        assertTrue(c.items().stream().noneMatch(i -> i.uri().endsWith("#zzzLate")),
                "an identifier past the cap is never tried");
    }

    private SourceFileReader trackingReaderOverFiles() {
        return new SourceFileReader() {
            @Override
            public String read(String repo, String path, String commit) {
                return files.get(path);
            }

            @Override
            public String apiHost() {
                return "code.example.invalid";
            }
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A {@link LanguageSupport} test double that always reports one fixed import (bringing in
     * {@code importedSymbol}) resolving to one fixed list of candidate paths — used to feed
     * {@link CodeContextProvider} a candidate path shape neither real {@link LanguageSupport}
     * implementation produces today (see the traversal/leading-slash tests above), without reaching
     * into the provider's private allow-list-enforcement internals directly.
     */
    private static final class FixedImportLanguageSupport implements LanguageSupport {
        private final String importedSymbol;
        private final List<String> candidates;

        FixedImportLanguageSupport(String importedSymbol, List<String> candidates) {
            this.importedSymbol = importedSymbol;
            this.candidates = candidates;
        }

        @Override
        public Set<String> languages() {
            return Set.of("java");
        }

        @Override
        public Set<String> identifiersIn(FilePatch patch) {
            return Set.of();
        }

        @Override
        public List<ImportRef> importsIn(String fileContent) {
            return List.of(new ImportRef("fixed", Set.of(importedSymbol)));
        }

        @Override
        public List<String> candidatePaths(ImportRef ref, String importingPath) {
            return candidates;
        }
    }
}
