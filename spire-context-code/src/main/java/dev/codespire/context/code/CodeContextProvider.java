package dev.codespire.context.code;

import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.port.ContextResolutionSource;
import dev.codespire.contract.port.FirstLevelOnly;
import dev.codespire.contract.port.LanguageSupport;
import dev.codespire.contract.port.SymbolIndex;
import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContextResolutionCounts;
import dev.codespire.contract.review.ContribStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Resolves the symbols a diff's changed lines touch into {@link ContextItem}s of kind
 * {@code CODE_SNIPPET} — the repository's own import graph read back as review context (ADR-026).
 *
 * <p>For each changed file, this walks its {@link LanguageSupport#importsIn parsed imports}, keeps
 * only the ones whose {@link LanguageSupport.ImportRef#symbols() symbols} intersect the request's
 * {@link CodeReferences#identifiers() identifiers} — the diff mentions the imported name, so the file
 * behind that import is worth reading — then fetches the first
 * {@link LanguageSupport#candidatePaths resolved candidate path} that exists. Once a definition file
 * is fetched, every identifier the request carries — not only the import's own symbol — is tried
 * against it via {@link SnippetExtractor#extract}: an import brings a whole file into scope, and the
 * diff typically references a *member* of that file, not the imported name itself.
 * {@code Pricer.chargeFor(...)} touches both the class name {@code Pricer} (what the import statement
 * names, and what makes the import "kept") and the member {@code chargeFor} (what the diff is actually
 * calling, and what {@link SnippetExtractor} finds declared in the fetched file) — restricting
 * extraction to the import's own symbol would silently miss the member the diff cares about.
 * Accepted consequence: a file reached via one import can surface a snippet for an identifier that
 * import never brought in, if the file happens to declare something else of the same name — so a
 * snippet is occasionally attributed to the "wrong" import. This is a byproduct of widening
 * extraction to every requested identifier per fetched file, not a separate defect, and is not worth
 * guarding against given how narrow the resulting misattribution is (the item still names its own
 * true definition path; only which import is credited for having found it can be off).
 *
 * <p>Retrieved source is UNTRUSTED input to the prompt (SECURITY.md), same as every other
 * {@link ContextProvider}.
 *
 * <p>Framework-free by design (see {@code spire-context-code}'s build file) — no CDI; one instance is
 * built per {@code GatherContext} command from the brokered credential, the same lifecycle the
 * sibling context providers (Jira, Confluence, GitHub/GitLab issues) already use.
 *
 * <p>Implements {@link FirstLevelOnly}: {@code codeReferences} is carried unchanged on every level of
 * {@code ContextWorker}'s bounded two-level fan-out, so without that gate this provider would re-run
 * its whole fetch-and-extract pipeline a second time whenever the PR also carries a ticket reference
 * that triggers level 2 — for zero new information, inside the same 20s budget.
 */
public class CodeContextProvider implements ContextProvider, ContextResolutionSource, FirstLevelOnly {

    public static final String SOURCE = "CODE";
    private static final String KIND = ContextItem.CODE_SNIPPET;

    /**
     * Cap on the number of snippets one contribution may return — a large diff can resolve far more
     * definitions than the prompt budget should spend on this one source.
     *
     * <p>Derived from the slot it feeds, not chosen freely: {@code {{code_context}}}'s budget is
     * 6,000 tokens (`PromptCatalog`), {@code TokenBudget.CHARS_PER_TOKEN} is 3.2, and a worst-case
     * snippet — {@link #MAX_BODY_LINES} body lines plus a short signature/doc comment and the
     * rendered title/prefix (see {@code ReviewPromptBuilder.renderContext}) — runs to roughly 1,600
     * characters (~500 tokens). 6,000 / 500 is 12: the previous cap of 20 could reach ~9,000 tokens,
     * which {@code PromptRenderer} would then silently tail-clip inside this slot — the very
     * eviction the dedicated slot exists to prevent, just relocated (M1, rung-1 final review).
     */
    public static final int MAX_SNIPPETS = 12;

    /** Body lines kept per snippet, beyond the always-kept signature and doc comment (SnippetExtractor). */
    private static final int MAX_BODY_LINES = 40;

    /**
     * Identifiers {@link #extractCandidates} will try against each resolved definition file.
     *
     * <p>Extraction is the quadratic step — every resolved file is scanned once per identifier — and
     * {@link CodeReferences} carries whatever the diff produced, up to its own (much larger) wire cap.
     * Bounding the wire value alone would still leave a big refactor running hundreds of full-file
     * regex scans per resolved file. {@link #EXTRACTION_BUDGET_MILLIS} stops that mid-flight; this cap
     * keeps it from being entered at that size in the first place, so the ordinary case never has to
     * fall back on a wall-clock check to finish in reasonable time.
     *
     * <p>Chosen well above what a real review carries: {@link #MAX_SNIPPETS} is 12, so the identifiers
     * past this point could only ever compete for a budget already spent many times over. Identifiers
     * are sorted before the cut, so which ones survive is deterministic rather than depending on a
     * {@code Set}'s iteration order.
     *
     * <p>Package-private, like the deadline-overriding constructor below and for the same reason: a
     * same-package test asserts the cut without hard-coding the number.
     */
    static final int MAX_EXTRACTION_IDENTIFIERS = 500;

    /**
     * Wall-clock budget this provider gives itself, checked between fetches (see {@link Fetcher}) so
     * a slow host degrades to a partial contribution — whatever resolved before the deadline still
     * ships — rather than losing everything to the aggregator's own cancellation. Deliberately below
     * {@code ContextWorker.TIMEOUT_SECONDS} (20s): this module cannot reference that class (see
     * {@code spire-context-code}'s build file — no dependency on {@code spire-review-worker}), and
     * even if it could, this deadline must expire *first* so the partial result below is what the
     * aggregator actually observes, instead of racing its own {@code CompletableFuture.cancel} (which
     * does not interrupt a running fetch) (I2, rung-1 final review).
     */
    private static final long DEADLINE_MILLIS = 18_000;

    /**
     * Wall-clock budget {@link #extractCandidates} gives itself, measured from when extraction starts.
     *
     * <p>Extraction is CPU, not I/O, and it is the quadratic step — every resolved definition file is
     * scanned once per identifier. Until this existed, {@link Fetcher} was the only place the deadline
     * was consulted, so extraction ran <em>in full</em> however long it took, and
     * {@code CompletableFuture.cancel} does not interrupt a running task, so the aggregator giving up
     * did not stop it either: it kept burning a pool thread long after the review had moved on
     * (M1/M2, PR 63 review).
     *
     * <p><b>Its own budget, not {@link #DEADLINE_MILLIS} itself</b>, which was the obvious first move
     * and is wrong: the whole point of that deadline is that a slow host degrades to a partial
     * contribution — "whatever resolved before the deadline still ships" (I2). Sharing it would mean
     * a run whose budget went entirely on fetching contributes <em>nothing</em>, having already paid
     * for the files it holds, and extracting a handful of cached files costs milliseconds, so there
     * would be no CPU saved for the contribution lost. A separate budget bounds the pathological case
     * (hundreds of files × hundreds of identifiers) without touching the ordinary slow-host one. Kept
     * short enough that the worst case still lands inside {@code ContextWorker}'s 20s fan-out budget.
     */
    private static final long EXTRACTION_BUDGET_MILLIS = 1_000;

    /**
     * Extension -> language tag, scoped to exactly the languages this module ships
     * {@link LanguageSupport} implementations for. Deliberately NOT {@code dev.codespire.diff.Languages}
     * — that class lives in {@code spire-diff}, which this framework-free module does not depend on,
     * and its mapping covers many languages this module has no {@link LanguageSupport} for.
     */
    private static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.of(
            "java", "java",
            "ts", "typescript", "tsx", "typescript",
            "js", "javascript", "jsx", "javascript");

    private final SourceFileReader reader;
    private final List<LanguageSupport> languages;
    private final Set<String> pathAllowList;

    /**
     * Rung 2 (ADR-026 §7), or null. Null is rung 1 exactly: nothing is recorded and no caller is
     * cited. Nullable rather than a second provider because the two rungs share every fetch — the
     * index is filled from files this provider already reads, and asking for callers reuses the same
     * budget, deadline and allow-list.
     */
    private final SymbolIndex symbolIndex;

    /** Rung 2 behind one collaborator; a no-op throughout when {@link #symbolIndex} is null. */
    private final CallerLookup callerLookup;

    private final long deadlineMillis;
    private final long extractionBudgetMillis;

    public CodeContextProvider(SourceFileReader reader, List<LanguageSupport> languages) {
        this(reader, languages, Set.of());
    }

    /**
     * @param pathAllowList optional path-prefix allow-list narrowing which resolved definition paths
     *                      this provider may fetch (see {@code CodeContextConfig.pathAllowList()}).
     *                      Matching rule: a candidate path is allowed when it starts with one of the
     *                      configured prefixes (a plain string prefix — include a trailing {@code /}
     *                      in an entry when directory-boundary precision matters); an empty allow-list
     *                      (the default, via the two-argument constructor) permits every candidate,
     *                      mirroring the sibling context providers' project-key filters. Enforced only
     *                      on paths reached through {@link LanguageSupport#candidatePaths} — never on
     *                      the diff's own changed paths, which are already part of the repository under
     *                      review and carry no such notion of "elsewhere in the tree".
     */
    public CodeContextProvider(SourceFileReader reader, List<LanguageSupport> languages,
            Set<String> pathAllowList) {
        this(reader, languages, pathAllowList, DEADLINE_MILLIS);
    }

    /**
     * Same as the three-argument constructor, with the resolution deadline overridable —
     * package-private, for a same-package test to prove the deadline behaviour (I2, rung-1 final
     * review) without waiting out the real {@link #DEADLINE_MILLIS} budget.
     */
    CodeContextProvider(SourceFileReader reader, List<LanguageSupport> languages,
            Set<String> pathAllowList, long deadlineMillis) {
        this(reader, languages, pathAllowList, deadlineMillis, EXTRACTION_BUDGET_MILLIS);
    }

    /**
     * Same again with the extraction budget overridable too — package-private, so a same-package test
     * can prove extraction stops on a budget of its own while every fetch still succeeds. The two
     * budgets have to be separately settable to show that at all: sharing one makes "the fetches
     * finished, the extraction did not" unreachable.
     */
    CodeContextProvider(SourceFileReader reader, List<LanguageSupport> languages,
            Set<String> pathAllowList, long deadlineMillis, long extractionBudgetMillis) {
        this(reader, languages, pathAllowList, deadlineMillis, extractionBudgetMillis, null);
    }

    /**
     * The canonical constructor, with rung 2's index (ADR-026 §7).
     *
     * <p>A null {@code symbolIndex} is rung 1 exactly — nothing recorded, no caller cited — so every
     * constructor above stays valid and a deployment that has not wired the index behaves as it did
     * before. The index is a collaborator rather than a second provider because both rungs share the
     * same fetches, budget, deadline and allow-list.
     */
    public CodeContextProvider(SourceFileReader reader, List<LanguageSupport> languages,
            Set<String> pathAllowList, long deadlineMillis, long extractionBudgetMillis,
            SymbolIndex symbolIndex) {
        this.reader = reader;
        this.languages = List.copyOf(languages);
        this.pathAllowList = pathAllowList == null ? Set.of() : Set.copyOf(pathAllowList);
        this.deadlineMillis = deadlineMillis;
        this.extractionBudgetMillis = extractionBudgetMillis;
        this.symbolIndex = symbolIndex;
        this.callerLookup = new CallerLookup(symbolIndex, this::languageFor);
    }

    /** The three-argument form plus rung 2's index — what the worker's composition root builds. */
    public CodeContextProvider(SourceFileReader reader, List<LanguageSupport> languages,
            Set<String> pathAllowList, SymbolIndex symbolIndex) {
        this(reader, languages, pathAllowList, DEADLINE_MILLIS, EXTRACTION_BUDGET_MILLIS, symbolIndex);
    }

    /**
     * The allow-list this instance enforces — exposed read-only so a composition-root test can
     * assert a credential's allow-list actually reached the constructed provider, rather than only
     * that construction compiled (Task 10 added enforcement precisely because nothing upstream of it
     * was ever proven to carry the allow-list through).
     */
    public Set<String> pathAllowList() {
        return pathAllowList;
    }

    /**
     * Whether rung 2 is wired, for the same reason {@link #pathAllowList()} is exposed: a composition
     * root that compiles proves nothing about what it passed. Without this, nothing could distinguish
     * "the worker wired the index" from "the code branch silently built a rung-1 provider".
     */
    public boolean hasSymbolIndex() {
        return callerLookup.enabled();
    }

    /**
     * The reader this instance fetches through — exposed read-only for the same reason
     * {@link #pathAllowList()} is. A single generic {@code code} credential covers three raw-content
     * APIs and the platform is inferred from its host, so which {@link SourceFileReader} a composition
     * root picked is a real decision with no other observable trace: routing a self-managed GitLab to
     * the GitHub reader produces 404s indistinguishable from "the file isn't there", and without this
     * accessor that branch could not be asserted even by intent (PR 63 QA review).
     */
    public SourceFileReader reader() {
        return reader;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return !request.codeReferences().isEmpty();
    }

    @Override
    public CompletionStage<ContextContribution> contribute(ContextRequest request) {
        return CompletableFuture.supplyAsync(() -> resolve(request).contribution());
    }

    /**
     * Runs the full resolution pipeline and returns both the {@link ContextContribution} and the
     * {@link ContextResolutionCounts} that produced it — the {@link ContextResolutionSource} capability
     * this provider implements. {@link #contribute} is still the plain {@code ContextProvider} SPI
     * entry point and discards the counts down to the contribution alone; this method exists so a
     * same-package diagnostics test can assert on the counts directly, and — the reason it must be
     * {@code public} — so {@code ContextWorker} (a different module, {@code spire-review-worker}) can
     * reach it through the capability interface and log the counts itself, under the reviewId MDC it
     * already carries and this framework-free module has no access to (see {@code spire-context-code}'s
     * build file).
     *
     * <p>Counts are a return value, never provider state: one {@link Fetcher} is built fresh per call
     * (see its own javadoc), and a mutable counts field here would suffer the identical hazard —
     * interleaving one review's counts with another's on a provider instance shared across concurrent
     * requests.
     */
    @Override
    public Resolution resolve(ContextRequest request) {
        long start = System.nanoTime();
        long deadlineNanos = start + deadlineMillis * 1_000_000L;
        Fetcher fetcher = new Fetcher(reader, request.repo().full(), request.commit(), pathAllowList,
                deadlineNanos);
        CodeReferences refs = request.codeReferences();

        Map<String, Set<String>> filesByDefinitionPath = resolveDefinitionFiles(refs, fetcher);
        List<DefinitionCandidate> candidates =
                extractCandidates(filesByDefinitionPath, refs.identifiers(), fetcher);
        List<ContextItem> items = rankAndCap(candidates);

        // Rung 2. Recording comes first so a repository being reviewed for the first time still
        // contributes to the index, even though it can have no callers to cite yet.
        String indexKey = indexKey(request);
        callerLookup.recordObserved(indexKey, request.commit(), fetcher.fetched(), deadlineNanos);
        items = withCallers(items, callerLookup.callers(indexKey, request.commit(), refs, fetcher,
                items, deadlineNanos));

        // A real per-file error (5xx, rate limit) never sinks the whole contribution by itself — it
        // only surfaces as ERROR when it leaves this contribution with literally nothing to say;
        // that is what distinguishes "broken" from an ordinary, error-free EMPTY.
        ContribStatus status = items.isEmpty()
                ? (fetcher.hadError() ? ContribStatus.ERROR : ContribStatus.EMPTY)
                : ContribStatus.OK;
        ContextContribution contribution = new ContextContribution(SOURCE, status, items, latencyMs(start));
        return new Resolution(contribution, countsFor(refs, candidates, items));
    }

    /**
     * The diagnostic counts {@code ContextWorker} logs beside the contribution.
     *
     * <p>extracted: identifiers the diff-side extraction handed this request — zero means nothing to
     * look up (e.g. a YAML-only diff), correct and uninteresting. resolved: candidates found before
     * the {@link #MAX_SNIPPETS} budget is applied — zero while extracted is positive is the broken
     * case: plenty to look up, none of it resolved. droppedForBudget: resolved candidates the cap cut.
     */
    private static ContextResolutionCounts countsFor(CodeReferences refs,
            List<DefinitionCandidate> candidates, List<ContextItem> items) {
        return new ContextResolutionCounts(refs.identifiers().size(), candidates.size(), items.size(),
                candidates.size() - items.size());
    }

    /**
     * Walks each changed file's imports, keeping only those whose symbols intersect the request's
     * identifiers, and resolves each kept import to the first existing candidate path.
     *
     * @return the definition files this diff's imports actually reach, each mapped to the distinct
     *     changed files whose imports brought it in — the input to ranking. Changed paths are visited
     *     in sorted order so "first appearance" (the ranking tie-break) is deterministic rather than
     *     depending on a {@code Set}'s unspecified iteration order.
     */
    private Map<String, Set<String>> resolveDefinitionFiles(CodeReferences refs, Fetcher fetcher) {
        Map<String, Set<String>> filesByDefinitionPath = new LinkedHashMap<>();
        List<String> changedPaths = new ArrayList<>(refs.changedPaths());
        Collections.sort(changedPaths);

        for (String changedPath : changedPaths) {
            String content = fetcher.readChangedFile(changedPath);
            if (content == null) {
                continue;
            }
            LanguageSupport support = languageFor(changedPath);
            if (support == null) {
                continue;
            }
            for (LanguageSupport.ImportRef ref : support.importsIn(content)) {
                if (!intersects(ref.symbols(), refs.identifiers())) {
                    continue;
                }
                String definitionPath = fetcher.firstExistingCandidatePath(support, ref, changedPath);
                if (definitionPath != null) {
                    filesByDefinitionPath.computeIfAbsent(definitionPath, p -> new LinkedHashSet<>())
                            .add(changedPath);
                }
            }
        }
        return filesByDefinitionPath;
    }

    private LanguageSupport languageFor(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return null;
        }
        // Locale.ROOT: the default locale must not change extension matching (Turkish-I).
        String tag = LANGUAGE_BY_EXTENSION.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (tag == null) {
            return null;
        }
        for (LanguageSupport support : languages) {
            if (support.languages().contains(tag)) {
                return support;
            }
        }
        return null;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String s : a) {
            if (b.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * For each resolved definition file, tries every identifier the request carries — not only the
     * symbol whose import led here — against its content; see the class javadoc for why. Identifiers
     * are tried in sorted order purely for deterministic candidate ordering before ranking.
     *
     * <p>Each file's content is split into lines exactly once and reused across every identifier
     * tried against it (M7, rung-1 final review) — the loop runs (definition files × identifiers)
     * times, so re-splitting per identifier would re-split the same file's full text as many times
     * as it has identifiers to try. For the same reason each identifier's declaration patterns are
     * compiled once, before the file loop, rather than once per (file, identifier) pair, and the
     * identifier list is capped at {@link #MAX_EXTRACTION_IDENTIFIERS}.
     *
     * <p><b>Bounded by {@link #EXTRACTION_BUDGET_MILLIS}</b>, its own budget rather than the fetches' —
     * see that constant for why sharing one would have cost a contribution it saves no work by
     * dropping. A run cut short here reports the shortfall through {@link Fetcher#hadError()}, exactly
     * as a deadline-skipped fetch does, and whatever was extracted before it still ships.
     */
    private List<DefinitionCandidate> extractCandidates(Map<String, Set<String>> filesByDefinitionPath,
            Set<String> identifiers, Fetcher fetcher) {
        long extractionDeadline = System.nanoTime() + extractionBudgetMillis * 1_000_000L;
        List<String> sortedIdentifiers = new ArrayList<>(identifiers);
        Collections.sort(sortedIdentifiers);
        if (sortedIdentifiers.size() > MAX_EXTRACTION_IDENTIFIERS) {
            sortedIdentifiers = sortedIdentifiers.subList(0, MAX_EXTRACTION_IDENTIFIERS);
        }
        List<SnippetExtractor.Symbol> symbols = new ArrayList<>(sortedIdentifiers.size());
        for (String identifier : sortedIdentifiers) {
            symbols.add(new SnippetExtractor.Symbol(identifier));
        }

        List<DefinitionCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : filesByDefinitionPath.entrySet()) {
            String definitionPath = entry.getKey();
            String content = fetcher.content(definitionPath);
            if (content == null) {
                continue;
            }
            String[] lines = content.split("\\R");
            for (int i = 0; i < symbols.size(); i++) {
                if (System.nanoTime() > extractionDeadline) {
                    fetcher.recordBudgetExhausted();
                    return candidates;
                }
                String snippet = SnippetExtractor.extract(lines, symbols.get(i), MAX_BODY_LINES);
                if (snippet == null) {
                    continue;
                }
                DefinitionCandidate candidate =
                        new DefinitionCandidate(sortedIdentifiers.get(i), definitionPath, snippet);
                candidate.changedFiles.addAll(entry.getValue());
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    /**
     * Appends caller snippets, trimming the lowest-ranked definitions so the two together never
     * exceed {@link #MAX_SNIPPETS}.
     *
     * <p>{@code MAX_SNIPPETS} is derived from the {@code code_context} slot's 6,000-token budget, so
     * appending callers on top of a full definition list overflows the very slot the cap exists to
     * protect — and {@code PromptRenderer} resolves that overflow by silently tail-clipping, which
     * would drop the callers just appended. Definitions are already ranked, so the trim comes off the
     * tail: the weakest definition yields to a caller rather than the strongest one.
     *
     * <p>The substitution is 1:1 and conservative — a call site is {@code 2 ×
     * CALL_SITE_CONTEXT_LINES + 1} lines where a definition runs to {@link #MAX_BODY_LINES} plus its
     * signature — so this leaves headroom rather than consuming it.
     */
    private static List<ContextItem> withCallers(List<ContextItem> definitions, List<ContextItem> callers) {
        if (callers.isEmpty()) {
            return definitions;
        }
        int room = Math.max(0, MAX_SNIPPETS - callers.size());
        List<ContextItem> combined = new ArrayList<>(
                definitions.subList(0, Math.min(room, definitions.size())));
        combined.addAll(callers);
        return combined;
    }

    /**
     * The index key, which must name the PLATFORM as well as the repository.
     *
     * <p>{@code workspace/slug} alone is shared: the same name routinely exists on two SCMs, and this
     * project has already been bitten by that collision twice. Without the platform, one org's rows
     * would name candidate paths that another org's review then fetches from ITS repository with ITS
     * credential — confirmation stops forged content being quoted, but not an outsider choosing which
     * of your files get read. The sibling issue providers gate on {@code scmType} for the same reason.
     */
    private static String indexKey(ContextRequest request) {
        String scm = request.scmType() == null ? "" : request.scmType().name();
        return scm + ":" + request.repo().full();
    }

    /**
     * Ranks by the number of distinct changed files whose imports brought the definition file in
     * (descending), then by first appearance ({@code List.sort} is stable, so ties keep the discovery
     * order {@link #extractCandidates} produced them in), and caps at {@link #MAX_SNIPPETS}.
     *
     * <p>Spec §6.4 also ranks a symbol found on an added line above one found only on a removed line.
     * {@link CodeReferences} does not distinguish added from removed identifiers, so rung 1 ships
     * without that tie-break — adding it means a third set on {@code CodeReferences} and is
     * deliberately deferred, not an oversight.
     *
     * <p>Each item's {@link ContextItem#uri} is {@code definitionPath + "#" + symbol}, not the bare
     * path: one file legitimately yields several items — {@link #extractCandidates} deliberately
     * tries every requested identifier against each fetched file — and a bare path would make
     * {@code ContextWorker.gatherContext}'s cross-source uri dedup (built for "the same page/ticket
     * referenced from two places") collapse every symbol from one file down to one item. That was C1
     * in the rung-1 final review: on a change touching several members of one file, all but the first
     * silently vanished after `contribute` reported them, with the diagnostic
     * ({@code ContextResolutionCounts.contributed}) reporting the pre-collapse count as if nothing
     * were wrong. The uri is also the UI's link target, and {@code path#symbol} still names it.
     */
    private List<ContextItem> rankAndCap(List<DefinitionCandidate> candidates) {
        List<DefinitionCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingInt((DefinitionCandidate c) -> c.changedFiles.size()).reversed());

        List<ContextItem> items = new ArrayList<>();
        for (DefinitionCandidate candidate : ranked) {
            if (items.size() >= MAX_SNIPPETS) {
                break;
            }
            items.add(new ContextItem(KIND, candidate.symbol + " — " + candidate.definitionPath,
                    candidate.snippet, candidate.definitionPath + "#" + candidate.symbol));
        }
        return items;
    }

    private static long latencyMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** One (identifier, definition file) resolution, carrying the changed files that led to it. */
    private static final class DefinitionCandidate {
        final String symbol;
        final String definitionPath;
        final String snippet;
        final Set<String> changedFiles = new LinkedHashSet<>();

        DefinitionCandidate(String symbol, String definitionPath, String snippet) {
            this.symbol = symbol;
            this.definitionPath = definitionPath;
            this.snippet = snippet;
        }
    }

    /**
     * Per-request fetch cache, allow-list gate, and error tracking — one instance per {@link #fetch}
     * call, never held as provider state, so one provider instance safely serves concurrent requests.
     */
    /**
     * Package-private rather than private: {@link CallerLookup} confirms every index candidate
     * through this same fetcher, so the caller path inherits its cache, its deadline, its allow-list
     * and its traversal guards instead of growing a second, unguarded read path.
     */
    static final class Fetcher {
        private final SourceFileReader reader;
        private final String repo;
        private final String commit;
        private final Set<String> pathAllowList;
        private final long deadlineNanos;
        private final Map<String, String> cache = new HashMap<>();
        private final Set<String> attempted = new HashSet<>();
        private boolean hadError;
        private boolean deadlineExceeded;

        Fetcher(SourceFileReader reader, String repo, String commit, Set<String> pathAllowList,
                long deadlineNanos) {
            this.reader = reader;
            this.repo = repo;
            this.commit = commit;
            this.pathAllowList = pathAllowList;
            this.deadlineNanos = deadlineNanos;
        }

        /**
         * Reads one of the diff's own changed files. Never subject to {@code pathAllowList}: that list
         * narrows where import resolution may wander, not the files already under review.
         *
         * <p>The traversal and percent-encoding checks DO apply, exactly as they do to a resolved
         * candidate — {@link #isSafePath}'s contract is that a path escaping the repository is never
         * one this provider reads, restriction configured or not, and this path used to be the one
         * place that skipped it. No leak followed today (a changed file's content is parsed for
         * imports and never becomes a {@code ContextItem}, and every reader encodes the path before it
         * reaches a URL), but the invariant was stated and not enforced, which is one refactor away
         * from mattering (L1, PR 63 review).
         */
        String readChangedFile(String path) {
            return isSafePath(path) ? read(path) : null;
        }

        /**
         * Walks {@code support.candidatePaths(ref, importingPath)} and reads the first one that
         * exists, honoring the allow-list on each candidate before any I/O is attempted.
         */
        String firstExistingCandidatePath(LanguageSupport support, LanguageSupport.ImportRef ref,
                String importingPath) {
            for (String candidate : support.candidatePaths(ref, importingPath)) {
                if (readCandidate(candidate) != null) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * Reads a path reached through {@code candidatePaths}. Rejected before any I/O when a
         * non-empty allow-list does not match — the enforcement point requirement 1 (see the
         * constructor javadoc) exists for.
         */
        String readCandidate(String path) {
            return isAllowed(path) ? read(path) : null;
        }

        /** The content already fetched for {@code path}, with no further I/O. */
        String content(String path) {
            return cache.get(path);
        }

        /** Every path this review actually read, with its content — the write side of the index. */
        Map<String, String> fetched() {
            return cache;
        }

        /**
         * True when a real error (5xx, rate limit) skipped at least one path, OR the resolution
         * deadline was reached before every path could be attempted — both are reasons a contribution
         * fell short of what it could have resolved, as opposed to an ordinary, error-free EMPTY
         * (nothing to look up). See {@link CodeContextProvider#DEADLINE_MILLIS} and I2 in the rung-1
         * final review.
         */
        boolean hadError() {
            return hadError || deadlineExceeded;
        }

        /** Whether the fetch budget is spent, recording it so {@link #hadError()} reports the shortfall. */
        private boolean deadlineReached() {
            if (System.nanoTime() > deadlineNanos) {
                recordBudgetExhausted();
                return true;
            }
            return false;
        }

        /**
         * Records that a budget cut this resolution short. Called from
         * {@code CodeContextProvider.extractCandidates} too, which runs on its own budget: a
         * contribution that fell short because extraction stopped is exactly as incomplete as one that
         * fell short because a fetch was skipped, and {@link #hadError()} is where that is reported.
         */
        void recordBudgetExhausted() {
            deadlineExceeded = true;
        }

        /**
         * A traversal-shaped candidate is rejected outright, before the prefix check even runs — a
         * plain {@code startsWith} would otherwise happily approve
         * {@code "src/allowed/../../etc/passwd"} against an allow-list entry of {@code "src/allowed"},
         * or {@code "/etc/passwd"} against an entry of {@code "/etc"}, since both are textually
         * prefixed exactly as configured. Neither {@code JavaLanguageSupport} nor
         * {@code TypeScriptLanguageSupport} builds a candidate path that looks like this today — this
         * guard exists for the resolver either could grow tomorrow, since nothing about
         * {@link LanguageSupport#candidatePaths} promises it never will. Checked unconditionally, even
         * with an empty allow-list: a resolved path escaping the repository is never a path this
         * provider should read, restriction configured or not.
         *
         * <p>A percent-encoded candidate is rejected the same way, for the same reason: {@link
         * #isTraversal} compares raw, un-decoded segments against {@code ".."}, so an encoded
         * traversal segment ({@code %2e%2e}) would sail past it and past the prefix check below, only
         * to be decoded by the platform on arrival — I3 in the rung-1 final review. No legitimate
         * candidate either {@code JavaLanguageSupport} or {@code TypeScriptLanguageSupport} produces
         * contains a {@code %}, so this costs no real recall.
         */
        private boolean isAllowed(String path) {
            if (!isSafePath(path)) {
                return false;
            }
            if (pathAllowList.isEmpty()) {
                return true;
            }
            for (String prefix : pathAllowList) {
                if (path.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        /** The unconditional half of {@link #isAllowed} — see its javadoc, and {@link #readChangedFile}. */
        private static boolean isSafePath(String path) {
            return !isTraversal(path) && !containsPercentEncoding(path);
        }

        private static boolean isTraversal(String path) {
            if (path.startsWith("/")) {
                return true;
            }
            for (String segment : path.split("/")) {
                if (segment.equals("..")) {
                    return true;
                }
            }
            return false;
        }

        private static boolean containsPercentEncoding(String path) {
            return path.indexOf('%') >= 0;
        }

        /**
         * One fetch per path per {@link #fetch} call: a cached outcome — success, absence, or a prior
         * error — never calls {@link SourceFileReader#read} again for the same path.
         *
         * <p>Checked against {@code deadlineNanos} before every attempt, not only at entry to
         * {@link CodeContextProvider#resolve}: several fetches happen per invocation (the changed
         * files, then each resolved candidate), and the budget is meant to bound the whole sequence,
         * not just the first fetch in it (I2, rung-1 final review). A path skipped this way is
         * reported as absent — indistinguishable to the caller from a 404 — which is the correct
         * degrade: whatever already resolved before the deadline still ships, via {@link #hadError()}.
         */
        private String read(String path) {
            if (attempted.contains(path)) {
                return cache.get(path);
            }
            if (deadlineReached()) {
                return null;
            }
            attempted.add(path);
            try {
                String content = reader.read(repo, path, commit);
                if (content != null) {
                    cache.put(path, content);
                }
                return content;
            } catch (RuntimeException e) {
                // A real error (5xx, rate limit) — skip this one path and let every other file still
                // contribute; ContribStatus.ERROR fires only when the whole contribution ends up empty.
                hadError = true;
                return null;
            }
        }
    }
}
