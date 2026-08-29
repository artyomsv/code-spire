package dev.codespire.context.code;

import dev.codespire.contract.port.LanguageSupport;
import dev.codespire.contract.port.SymbolIndex;
import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.review.ContextItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Rung 2 of ADR-026: the reverse edge — <em>what depends on this diff</em>.
 *
 * <p>Rung 1 follows a changed file's imports outward and answers "what does this diff depend on".
 * Imports point one way, so that question can never be turned around by reading the diff alone: to
 * find a file that calls a changed method you must already know it exists. {@link SymbolIndex} is
 * that knowledge, grown as a side effect of reviewing rather than by crawling.
 *
 * <p><b>The index is a hint, never an answer.</b> Every candidate path it names is re-fetched at the
 * review commit and the reference confirmed before anything is cited (§7.1). That is what removes
 * staleness as a category — there is no invalidation pass, and no stored row ever speaks for current
 * code, because the file was read moments before it was quoted. A row whose reference has since been
 * deleted simply drops out here, silently and harmlessly.
 *
 * <p>Split out of {@link CodeContextProvider} rather than left inline: the caller path has its own
 * budgets, its own confirmation rule and its own failure mode, and none of it runs at all when
 * {@code index} is null (rung 1 exactly). Keeping it here means the rung-1 pipeline reads as it did
 * before rung 2 existed.
 */
final class CallerLookup {

    /**
     * Caller snippets one review may cite, on top of the definitions rung 1 resolved.
     *
     * <p>Deliberately small. A definition answers "what does this diff depend on" and is nearly
     * always relevant; a caller answers "what might this break" and is speculative until the model
     * judges it. Letting callers compete freely for the definition budget would trade a certain
     * signal for an uncertain one, and each caller also costs a confirmation fetch.
     */
    static final int MAX_CALLER_SNIPPETS = 3;

    /**
     * Symbols per review whose callers are looked up. A changed file can declare dozens; without a
     * bound, a wide refactor turns into dozens of index reads and fetches for a budget that can only
     * cite three of them anyway.
     */
    static final int MAX_CALLER_LOOKUPS = 8;

    /**
     * Confirmation fetches one review may spend looking for callers.
     *
     * <p>The other two budgets bound index READS and CITATIONS, and neither bounds the network work
     * between them: {@code callersOf} returns up to {@code MAX_CANDIDATES} paths and every one must
     * be fetched to be confirmed. Unbounded, a review with a stale index issued up to 8 × 25 = 200
     * extra content GETs to cite at most three — against the same SCM rate limit the adapters share.
     */
    static final int MAX_CALLER_CONFIRMATIONS = 20;

    /** Lines of context either side of a call site — enough to see the call in its method. */
    static final int CALL_SITE_CONTEXT_LINES = 4;

    /**
     * Files one review may write index rows for.
     *
     * <p>{@code PostgresSymbolIndex} bounds rows per FILE, which bounds nothing per review: a
     * generated-code PR touching a thousand files would have written that cap a thousand times over
     * in one pass, and the write is a side effect of reviewing rather than the point of it. Bounding
     * files rather than rows keeps each recorded file complete — a half-recorded file is a file whose
     * missing references are indistinguishable from references that do not exist.
     *
     * <p>Costs recall only: the files past the cut are recorded by the next review that reads them.
     */
    static final int MAX_RECORDED_FILES = 100;

    private static final String KIND = ContextItem.CODE_SNIPPET;

    /** Rung 2, or null — null is rung 1 exactly: nothing recorded, no caller cited. */
    private final SymbolIndex index;

    private final Function<String, LanguageSupport> languageFor;

    CallerLookup(SymbolIndex index, Function<String, LanguageSupport> languageFor) {
        this.index = index;
        this.languageFor = languageFor;
    }

    /** Whether rung 2 is on. Exposed so a wiring test can prove the index reached the provider. */
    boolean enabled() {
        return index != null;
    }

    /**
     * Records what every file this review read declares and references.
     *
     * <p>The write side is free of extra I/O by construction — it parses files already fetched for
     * rung 1, so growing the index costs no SCM call of its own. The index therefore grows toward
     * the part of the repository that is actively changing, which is the set this feature needs: it
     * never crawls, so a ten-thousand-file monorepo never holds most of itself, and that is correct
     * rather than a limitation.
     *
     * <p>Structure only: names and paths, never a line of source.
     */
    void recordObserved(String repoKey, String commit, Map<String, String> fetched, long deadlineNanos) {
        if (index == null) {
            return;
        }
        int recorded = 0;
        for (Map.Entry<String, String> file : fetched.entrySet()) {
            // Recording is best-effort and runs AFTER the fetch budget is spent, so without this it
            // kept parsing whole files and opening connections long past the point ContextWorker had
            // timed out and discarded the result -- on one of four shared fan-out threads. Dropping
            // costs recall, never correctness.
            if (recorded >= MAX_RECORDED_FILES || System.nanoTime() > deadlineNanos) {
                return;
            }
            if (recordFile(repoKey, file.getKey(), commit, file.getValue())) {
                recorded++;
            }
        }
    }

    /** @return whether the file had anything worth storing. */
    private boolean recordFile(String repoKey, String path, String commit, String content) {
        LanguageSupport support = languageFor.apply(path);
        if (support == null) {
            return false;
        }
        LanguageSupport.Symbols symbols = support.symbolsIn(content);
        if (symbols.defines().isEmpty() && symbols.references().isEmpty()) {
            return false;
        }
        index.record(repoKey, path, commit, List.copyOf(symbols.defines()),
                List.copyOf(symbols.references()));
        return true;
    }

    /**
     * Files known to call something this diff declares — rung 2's question, which rung 1 structurally
     * cannot answer.
     *
     * @param alreadyCited the definition items rung 1 produced; their paths are skipped, so one file
     *     never appears twice in the same prompt spending a budget twice.
     */
    List<ContextItem> callers(String repoKey, String commit, CodeReferences refs,
                              CodeContextProvider.Fetcher fetcher, List<ContextItem> alreadyCited,
                              long deadlineNanos) {
        if (index == null) {
            return List.of();
        }
        Budget budget = new Budget(deadlineNanos);
        Set<String> skip = pathsAlreadySpent(refs, alreadyCited);
        List<ContextItem> items = new ArrayList<>();

        List<String> changedPaths = new ArrayList<>(refs.changedPaths());
        Collections.sort(changedPaths);
        for (String changedPath : changedPaths) {
            if (budget.exhausted(items)) {
                return items;
            }
            LanguageSupport support = languageFor.apply(changedPath);
            String content = support == null ? null : fetcher.content(changedPath);
            if (content == null) {
                continue;
            }
            for (String symbol : lookupTargets(support, content, refs)) {
                if (budget.exhausted(items) || !budget.spendLookup()) {
                    return items;
                }
                citeCallersOf(repoKey, commit, symbol, fetcher, skip, items, budget);
            }
        }
        return items;
    }

    /**
     * Paths a caller must not be cited for: the diff's own files (a file under review is not news)
     * and anything rung 1 already quoted as a definition.
     */
    private static Set<String> pathsAlreadySpent(CodeReferences refs, List<ContextItem> alreadyCited) {
        Set<String> skip = new LinkedHashSet<>(refs.changedPaths());
        for (ContextItem cited : alreadyCited) {
            int hash = cited.uri().indexOf('#');
            skip.add(hash < 0 ? cited.uri() : cited.uri().substring(0, hash));
        }
        return skip;
    }

    /**
     * The symbols worth asking about: what the changed file declares AND the diff actually touched.
     *
     * <p>Intersecting with the request's identifiers is what makes this "what depends on THIS DIFF"
     * rather than "what depends on this file". Without it, a one-line change to one method of a
     * forty-member class spent its whole lookup budget on unrelated declarations.
     *
     * <p>Sorted because the budget CUTS this list, and {@code Set.copyOf} iteration order is salted
     * per JVM — so which symbols got looked up changed between runs of the same review. The sibling
     * budgets in {@link CodeContextProvider} already sort for exactly this reason.
     */
    private static List<String> lookupTargets(LanguageSupport support, String content, CodeReferences refs) {
        Set<String> declared = new LinkedHashSet<>(support.symbolsIn(content).defines());
        declared.retainAll(refs.identifiers());
        List<String> targets = new ArrayList<>(declared);
        Collections.sort(targets);
        return targets;
    }

    /** Confirms and cites callers of one symbol, within the review-wide budget. */
    private void citeCallersOf(String repoKey, String commit, String symbol,
                               CodeContextProvider.Fetcher fetcher, Set<String> skip,
                               List<ContextItem> items, Budget budget) {
        for (String candidatePath : index.callersOf(repoKey, symbol)) {
            if (budget.exhausted(items) || !budget.spendConfirmation()) {
                return;
            }
            if (!skip.add(candidatePath)) {
                continue;
            }
            String content = confirmedContent(fetcher, candidatePath, symbol);
            if (content == null) {
                continue;
            }
            // Re-record the confirmed caller. It was fetched and parsed after the write phase ran, so
            // without this its row keeps the timestamp of whichever earlier review first saw it — and
            // the retention sweep prunes by that timestamp. The rows most useful to this feature, the
            // ones a review just proved still correct, were the first to be deleted.
            recordFile(repoKey, candidatePath, commit, content);
            String snippet = callSiteSnippet(content, symbol);
            if (snippet != null) {
                items.add(new ContextItem(KIND, callerTitle(symbol, candidatePath), snippet,
                        candidatePath + "#" + symbol));
            }
        }
    }

    /**
     * Re-reads a candidate at the review commit and returns its content only if the reference is
     * still there. Null means the index was out of date and this candidate is silently dropped — the
     * whole reason a stale row is harmless.
     */
    private String confirmedContent(CodeContextProvider.Fetcher fetcher, String candidatePath, String symbol) {
        String content = fetcher.readCandidate(candidatePath);
        if (content == null) {
            return null;
        }
        LanguageSupport support = languageFor.apply(candidatePath);
        if (support == null || !support.symbolsIn(content).references().contains(symbol)) {
            return null;
        }
        return content;
    }

    /**
     * The lines around where a caller actually uses the symbol.
     *
     * <p>Deliberately not {@link SnippetExtractor}, which finds a DECLARATION — the right thing for
     * rung 1, where the fetched file is the one that defines the symbol, and the wrong thing here,
     * where the whole point is that this file only uses it. Asking the declaration extractor for a
     * call site returns nothing, so every caller would be silently dropped as unconfirmed.
     */
    static String callSiteSnippet(String content, String symbol) {
        String[] lines = content.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (!mentions(lines[i], symbol)) {
                continue;
            }
            int from = Math.max(0, i - CALL_SITE_CONTEXT_LINES);
            int to = Math.min(lines.length, i + CALL_SITE_CONTEXT_LINES + 1);
            return String.join("\n", Arrays.copyOfRange(lines, from, to));
        }
        return null;
    }

    /** Whole-word match, so {@code Pricer} is not found inside {@code PricerFactory}. */
    private static boolean mentions(String line, String symbol) {
        int at = line.indexOf(symbol);
        while (at >= 0) {
            boolean leftClear = at == 0 || !Character.isJavaIdentifierPart(line.charAt(at - 1));
            int after = at + symbol.length();
            boolean rightClear = after >= line.length() || !Character.isJavaIdentifierPart(line.charAt(after));
            if (leftClear && rightClear) {
                return true;
            }
            at = line.indexOf(symbol, at + 1);
        }
        return false;
    }

    /**
     * States partial recall in the item itself (ADR-026 §7.5).
     *
     * <p>The index is deliberately incomplete — it only holds what reviews have already read — so a
     * finding claiming "this breaks all three callers" when twelve exist would be a fabrication: the
     * no-synthetic-data rule applied to completeness. "A known caller" is a claim the handed set can
     * actually support; "the callers" is not.
     */
    private static String callerTitle(String symbol, String path) {
        return "A known caller of " + symbol + " — " + path
                + " (known callers only; others may exist that were not retrieved)";
    }

    /**
     * The three things that bound the caller path: citations, index reads, and the confirmation
     * fetches between them — plus the same deadline the fetch phase honours.
     */
    private static final class Budget {
        private final long deadlineNanos;
        private int lookups;
        private int confirmations;

        Budget(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        boolean exhausted(List<ContextItem> items) {
            return items.size() >= MAX_CALLER_SNIPPETS || System.nanoTime() > deadlineNanos;
        }

        boolean spendLookup() {
            return lookups++ < MAX_CALLER_LOOKUPS;
        }

        boolean spendConfirmation() {
            return confirmations++ < MAX_CALLER_CONFIRMATIONS;
        }
    }
}
