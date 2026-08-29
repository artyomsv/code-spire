package dev.codespire.context.code;

import dev.codespire.contract.port.SymbolIndex;
import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rung 2 (ADR-026 §7): "what depends on this diff", the question imports structurally cannot answer.
 *
 * <p>The behaviour these pin is the one the design turns on — <b>a candidate is re-fetched at the
 * review commit and confirmed before it is cited</b>. That is what makes a stale row harmless and
 * removes the need for any invalidation pass.
 */
class CallSiteContextTest {

    private final Map<String, String> files = new HashMap<>();

    /** Every path actually fetched, so the confirmation budget is observable. */
    private final List<String> reads = new ArrayList<>();

    private final SourceFileReader reader = new SourceFileReader() {
        @Override
        public String read(String repo, String path, String commit) {
            reads.add(path);
            return files.get(path);
        }

        @Override
        public String apiHost() {
            return "code.example.invalid";
        }
    };

    /** In-memory stand-in for the Postgres index; records what it was told, answers what it holds. */
    private static final class FakeIndex implements SymbolIndex {
        final Map<String, List<String>> callers = new LinkedHashMap<>();
        final List<String> lookedUp = new ArrayList<>();
        final Map<String, List<String>> recordedDefines = new LinkedHashMap<>();
        final Map<String, List<String>> recordedReferences = new LinkedHashMap<>();

        @Override
        public List<String> callersOf(String repo, String symbol) {
            lookedUp.add(symbol);
            return callers.getOrDefault(symbol, List.of());
        }

        @Override
        public void record(String repo, String path, String commit,
                           List<String> defines, List<String> references) {
            recordedDefines.put(path, new ArrayList<>(defines));
            recordedReferences.put(path, new ArrayList<>(references));
        }
    }

    private final FakeIndex index = new FakeIndex();

    private CodeContextProvider provider() {
        return new CodeContextProvider(reader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()), Set.of(), index);
    }

    private static ContextRequest request(CodeReferences refs) {
        return new ContextRequest("review::acme/widgets#1", new RepoRef("acme", "widgets"), 1,
                "cafe1234", Set.of(), Set.of(), null, null, refs);
    }

    private static final String CHANGED = "src/main/java/dev/example/pricing/Pricer.java";
    private static final String CALLER = "src/main/java/dev/example/Billing.java";

    private void givenPricerIsChangedAndBillingCallsIt() {
        files.put(CHANGED, """
                package dev.example.pricing;
                public final class Pricer {
                    public static long chargeFor(long tokens) { return tokens; }
                }
                """);
        files.put(CALLER, """
                package dev.example;
                public final class Billing {
                    public void run() { Pricer.chargeFor(1); }
                }
                """);
    }

    private ContextContribution contribute() throws Exception {
        return provider().contribute(request(new CodeReferences(Set.of(CHANGED),
                Set.of("Pricer", "chargeFor")))).toCompletableFuture().get();
    }

    @Test
    void citesAFileKnownToCallSomethingTheDiffDeclares() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        index.callers.put("Pricer", List.of(CALLER));

        List<ContextItem> items = contribute().items();

        assertTrue(items.stream().anyMatch(i -> i.uri().startsWith(CALLER)),
                "the caller should be cited: " + items);
    }

    /**
     * The design's core guarantee. The index says Billing calls Pricer; the file at the review commit
     * no longer does. A stored row must never speak for current code, so this candidate drops out —
     * which is why there is no invalidation pass anywhere in rung 2.
     */
    @Test
    void doesNotCiteACandidateWhoseReferenceIsGoneAtTheReviewCommit() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        files.put(CALLER, """
                package dev.example;
                public final class Billing {
                    public void run() { }
                }
                """);
        index.callers.put("Pricer", List.of(CALLER));

        assertFalse(contribute().items().stream().anyMatch(i -> i.uri().startsWith(CALLER)),
                "a stale row must not become a citation");
    }

    /**
     * The case that makes confirmation load-bearing: the symbol is STILL IN THE FILE, but only in a
     * comment. It is no longer a reference.
     *
     * <p>Without this, the sibling test above passes for the wrong reason — when the symbol is absent
     * entirely, snippet extraction fails too, so deleting the confirmation check changes nothing and
     * the guard proves nothing. Verified by mutation: removing the check leaves that test green and
     * turns this one red. It is also the realistic shape — the index lists a file because it once
     * called the symbol, and now only its documentation mentions it.
     */
    @Test
    void doesNotCiteAFileWhereTheSymbolSurvivesOnlyInAComment() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        files.put(CALLER, """
                package dev.example;
                public final class Billing {
                    // Used to call Pricer.chargeFor here, before the rewrite.
                    public void run() { }
                }
                """);
        index.callers.put("Pricer", List.of(CALLER));

        assertFalse(contribute().items().stream().anyMatch(i -> i.uri().startsWith(CALLER)),
                "a mention in a comment is not a call site");
    }
    /** A candidate the index names but the repository no longer has is likewise dropped, not cited. */
    @Test
    void doesNotCiteACandidateThatNoLongerExists() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        index.callers.put("Pricer", List.of("src/main/java/dev/example/Deleted.java"));

        assertFalse(contribute().items().stream().anyMatch(i -> i.uri().contains("Deleted")));
    }

    /**
     * The index is deliberately incomplete — it holds only what reviews have already read — so an
     * item that reads as "the callers" invites a finding claiming completeness the handed set cannot
     * support. That is the no-synthetic-data rule applied to completeness (§7.5).
     */
    @Test
    void framesEveryCallerAsKnownRatherThanComplete() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        index.callers.put("Pricer", List.of(CALLER));

        ContextItem caller = contribute().items().stream()
                .filter(i -> i.uri().startsWith(CALLER)).findFirst().orElseThrow();

        assertTrue(caller.title().contains("known caller"), caller.title());
        assertTrue(caller.title().contains("others may exist"), caller.title());
    }

    @Test
    void recordsWhatEveryFileItReadDeclaresAndMentions() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        contribute();

        assertTrue(index.recordedDefines.containsKey(CHANGED), index.recordedDefines.toString());
        assertTrue(index.recordedDefines.get(CHANGED).contains("Pricer"));
        assertTrue(index.recordedDefines.get(CHANGED).contains("chargeFor"));
    }

    /**
     * A file under review is not news about itself.
     *
     * <p>The skip set must be exercised by a file confirmation would otherwise ACCEPT, and only a
     * second changed file can be one: {@code symbolsIn} subtracts a file's own declarations from its
     * references, so the file that declares the symbol can never confirm as a caller of it and a
     * one-file version of this test held with the skip set deleted. Here {@code Invoicing} genuinely
     * calls {@code chargeFor} and is genuinely in the diff — the reviewer is already reading it, so
     * quoting it back as retrieved context spends the budget on nothing.
     */
    @Test
    void neverCitesAChangedFileAsItsOwnCaller() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        String alsoChanged = "src/main/java/dev/example/Invoicing.java";
        files.put(alsoChanged, """
                package dev.example;
                import dev.example.pricing.Pricer;
                public final class Invoicing {
                    public void bill() { Pricer.chargeFor(2); }
                }
                """);
        index.callers.put("chargeFor", List.of(alsoChanged));

        List<ContextItem> items = provider().contribute(request(new CodeReferences(
                Set.of(CHANGED, alsoChanged), Set.of("chargeFor")))).toCompletableFuture().get().items();

        assertFalse(items.stream().anyMatch(i -> i.uri().startsWith(alsoChanged + "#")),
                "a file already in the diff must not be cited as caller context: " + items);
    }

    /**
     * A confirmed caller is re-recorded, so the rows this feature depends on most stay fresh.
     *
     * <p>The write phase runs before the caller phase, and it can only record what has been fetched
     * by then — which never includes a caller, since a caller is fetched to confirm it. So the row
     * for a file that reviews keep proving still correct kept the timestamp of whichever review
     * first saw it, and the retention sweep prunes on that timestamp: the most useful rows in the
     * index were the first to be deleted.
     */
    @Test
    void aConfirmedCallerIsReRecordedSoItsRowStaysFresh() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        index.callers.put("Pricer", List.of(CALLER));

        contribute();

        assertTrue(index.recordedReferences.containsKey(CALLER),
                "the confirmed caller must be recorded: " + index.recordedReferences.keySet());
        assertTrue(index.recordedReferences.get(CALLER).contains("Pricer"));
    }

    /**
     * One review cannot write an unbounded number of index rows.
     *
     * <p>{@code PostgresSymbolIndex} bounds rows per FILE, which bounds nothing per review: a
     * generated-code PR touching a thousand files would have written that per-file cap a thousand
     * times over in a single pass.
     */
    @Test
    void recordsAtMostAFixedNumberOfFilesPerReview() throws Exception {
        Set<String> changedPaths = new java.util.HashSet<>();
        for (int i = 0; i < CallerLookup.MAX_RECORDED_FILES + 20; i++) {
            String path = "src/main/java/dev/example/bulk/Bulk" + i + ".java";
            files.put(path, "package dev.example.bulk;\nclass Bulk" + i
                    + " { public long value" + i + "() { return 1; } }\n");
            changedPaths.add(path);
        }

        provider().contribute(request(new CodeReferences(changedPaths, Set.of("value0"))))
                .toCompletableFuture().get();

        assertTrue(index.recordedDefines.size() <= CallerLookup.MAX_RECORDED_FILES,
                "one review wrote rows for " + index.recordedDefines.size() + " files");
    }

    /**
     * Callers do not push the combined list past the slot budget.
     *
     * <p>{@code MAX_SNIPPETS} is derived from the {@code code_context} slot's token budget, so
     * appending callers on top of a full definition list overflows the very slot the cap exists to
     * protect — and the renderer resolves that overflow by tail-clipping, which would silently drop
     * the callers just appended.
     */
    @Test
    void callersNeverPushTheCombinedListPastTheSnippetBudget() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        index.callers.put("Pricer", List.of(CALLER));
        Set<String> changedPaths = new java.util.HashSet<>(Set.of(CHANGED));
        Set<String> identifiers = new java.util.HashSet<>(Set.of("Pricer", "chargeFor"));
        for (int i = 0; i < CodeContextProvider.MAX_SNIPPETS + 1; i++) {
            String n = String.format("%02d", i);
            String changedPath = "src/main/java/dev/example/a/A" + n + ".java";
            String symbol = "Sym" + n;
            files.put(changedPath, "package dev.example.a;\nimport dev.example.d." + symbol
                    + ";\nclass A" + n + " { }\n");
            files.put("src/main/java/dev/example/d/" + symbol + ".java",
                    "public long " + symbol + "() { return 1; }");
            changedPaths.add(changedPath);
            identifiers.add(symbol);
        }

        List<ContextItem> items = provider()
                .contribute(request(new CodeReferences(changedPaths, identifiers)))
                .toCompletableFuture().get().items();

        assertTrue(items.size() <= CodeContextProvider.MAX_SNIPPETS,
                "combined definitions and callers overran the slot budget: " + items.size());
        assertTrue(items.stream().anyMatch(i -> i.uri().startsWith(CALLER)),
                "the caller must survive the trim — definitions yield from the tail: " + items);
    }

    /**
     * Null index is rung 1 exactly. A deployment that has not wired rung 2 must behave as it did
     * before — nothing recorded, no caller cited, definitions unaffected.
     */
    @Test
    void withoutAnIndexItBehavesExactlyAsRungOne() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        index.callers.put("Pricer", List.of(CALLER));

        CodeContextProvider rungOne = new CodeContextProvider(reader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()), Set.of(), null);
        List<ContextItem> items = rungOne.contribute(request(
                new CodeReferences(Set.of(CHANGED), Set.of("Pricer")))).toCompletableFuture().get().items();

        assertFalse(items.stream().anyMatch(i -> i.uri().startsWith(CALLER)));
        assertTrue(index.recordedDefines.isEmpty(), "nothing is recorded without an index");
    }

    /** Each caller costs a confirmation fetch and prompt room, so the budget is small and enforced. */
    @Test
    void capsHowManyCallersOneReviewCites() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < CallerLookup.MAX_CALLER_SNIPPETS + 4; i++) {
            String path = "src/main/java/dev/example/Caller" + i + ".java";
            files.put(path, """
                    package dev.example;
                    public final class Caller%d {
                        public void run() { Pricer.chargeFor(1); }
                    }
                    """.formatted(i));
            many.add(path);
        }
        index.callers.put("Pricer", many);

        long cited = contribute().items().stream().filter(i -> i.uri().contains("Caller")).count();

        assertEquals(CallerLookup.MAX_CALLER_SNIPPETS, cited);
    }
    /**
     * A path the index names is still subject to the allow-list and traversal guards.
     *
     * <p>The existing traversal tests all exercise IMPORT-resolved candidates. The index is now the
     * higher-risk source — its rows are influenced by whatever a reviewed repository contains — and
     * nothing would have failed if confirmation had read the path directly.
     */
    @Test
    void neverFetchesAnIndexSuppliedPathThatEscapesTheRepository() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        files.put("../../etc/passwd", "root:x:0:0");
        index.callers.put("Pricer", List.of("../../etc/passwd"));

        assertFalse(contribute().items().stream().anyMatch(i -> i.uri().contains("passwd")));
    }

    /** Same guard, for a configured allow-list rather than a traversal. */
    @Test
    void neverFetchesAnIndexSuppliedPathOutsideTheAllowList() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        index.callers.put("Pricer", List.of(CALLER));
        CodeContextProvider restricted = new CodeContextProvider(reader,
                List.of(new JavaLanguageSupport(), new TypeScriptLanguageSupport()),
                Set.of("src/main/java/dev/allowed/"), index);

        var items = restricted.contribute(request(new CodeReferences(Set.of(CHANGED),
                Set.of("Pricer", "chargeFor")))).toCompletableFuture().get().items();

        assertFalse(items.stream().anyMatch(i -> i.uri().startsWith(CALLER)),
                "an index row outside the allow-list must not be fetched");
    }

    /**
     * A file already quoted as a definition is not quoted again as a caller.
     *
     * <p>Both roles are real here: {@code Shared} is resolved through {@code Api}'s import (rung 1)
     * and also calls back into {@code Api.compute}, which the index names it a caller of (rung 2). The
     * same file therefore reaches the prompt twice, spending twice the budget {@code MAX_SNIPPETS}
     * exists to ration, on text the model already has.
     */
    @Test
    void doesNotCiteAsACallerAFileAlreadyCitedAsADefinition() throws Exception {
        String api = "src/main/java/dev/example/a/Api.java";
        String shared = "src/main/java/dev/example/Shared.java";
        files.put(api, """
                package dev.example.a;
                import dev.example.Shared;
                public final class Api {
                    public long compute() { return Shared.helper(); }
                }
                """);
        files.put(shared, """
                package dev.example;
                public final class Shared {
                    public static long helper() { return 1; }
                    public long callBack(Api api) { return api.compute(); }
                }
                """);
        index.callers.put("compute", List.of(shared));

        List<ContextItem> items = provider().contribute(request(new CodeReferences(Set.of(api),
                Set.of("Shared", "compute")))).toCompletableFuture().get().items();

        assertEquals(1, items.stream().filter(i -> i.uri().startsWith(shared)).count(),
                "one file must not occupy two snippet slots: " + items);
    }

    /**
     * The lookup budget bounds index reads even when nothing is ever cited.
     *
     * <p>Isolated deliberately: every candidate here fails confirmation, so the citation cap can
     * never fire and the lookup cap is the only thing that stops the loop. A test where candidates
     * confirm would stop after three citations and prove nothing about this budget.
     */
    @Test
    void capsHowManySymbolsOneReviewLooksUp() throws Exception {
        String ghost = "src/main/java/dev/example/Ghost.java";
        files.put(ghost, "package dev.example;\nclass Ghost { }\n");
        StringBuilder body = new StringBuilder("package dev.example.pricing;\npublic final class Pricer {\n");
        Set<String> identifiers = new java.util.HashSet<>();
        for (int i = 0; i < CallerLookup.MAX_CALLER_LOOKUPS + 6; i++) {
            body.append("    public long rate").append(i).append("() { return 1; }\n");
            identifiers.add("rate" + i);
            index.callers.put("rate" + i, List.of(ghost));
        }
        files.put(CHANGED, body.append("}\n").toString());

        provider().contribute(request(new CodeReferences(Set.of(CHANGED), identifiers)))
                .toCompletableFuture().get();

        assertEquals(CallerLookup.MAX_CALLER_LOOKUPS, index.lookedUp.size(),
                "looked up: " + index.lookedUp);
    }

    /**
     * The confirmation budget bounds the FETCHES between an index read and a citation.
     *
     * <p>The other two budgets bound reads and citations; without this one a single very common
     * identifier turned into one content GET per candidate, against the same SCM rate limit every
     * adapter shares — and none of those fetches produced anything, because none confirmed.
     */
    @Test
    void capsHowManyCandidatesOneReviewFetchesToConfirm() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < CallerLookup.MAX_CALLER_CONFIRMATIONS + 15; i++) {
            String path = "src/main/java/dev/example/Ghost" + i + ".java";
            files.put(path, "package dev.example;\nclass Ghost" + i + " { }\n");
            candidates.add(path);
        }
        index.callers.put("Pricer", candidates);

        contribute();

        long fetched = reads.stream().filter(path -> path.contains("Ghost")).count();
        assertTrue(fetched <= CallerLookup.MAX_CALLER_CONFIRMATIONS,
                "confirmation spent " + fetched + " fetches");
    }

    /** Whole-word only: a symbol must not be found inside a longer identifier. */
    @Test
    void matchesACallSiteOnWholeWordsOnly() {
        String usesADifferentName = """
                package dev.example;
                class Holder { void go() { PricerFactory.build(); } }
                """;

        assertNull(CallerLookup.callSiteSnippet(usesADifferentName, "Pricer"),
                "Pricer must not match inside PricerFactory");
        assertNotNull(CallerLookup.callSiteSnippet(usesADifferentName, "PricerFactory"));
    }
}
