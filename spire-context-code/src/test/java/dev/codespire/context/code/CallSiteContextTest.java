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

    /** In-memory stand-in for the Postgres index; records what it was told, answers what it holds. */
    private static final class FakeIndex implements SymbolIndex {
        final Map<String, List<String>> callers = new LinkedHashMap<>();
        final Map<String, List<String>> recordedDefines = new LinkedHashMap<>();
        final Map<String, List<String>> recordedReferences = new LinkedHashMap<>();

        @Override
        public List<String> callersOf(String repo, String symbol) {
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

    /** A file under review is not news about itself. */
    @Test
    void neverCitesAChangedFileAsItsOwnCaller() throws Exception {
        givenPricerIsChangedAndBillingCallsIt();
        index.callers.put("Pricer", List.of(CHANGED));

        assertFalse(contribute().items().stream().anyMatch(i -> i.uri().startsWith(CHANGED + "#")),
                "the changed file must not be cited as a caller of itself");
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
        for (int i = 0; i < CodeContextProvider.MAX_CALLER_SNIPPETS + 4; i++) {
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

        assertEquals(CodeContextProvider.MAX_CALLER_SNIPPETS, cited);
    }
}
