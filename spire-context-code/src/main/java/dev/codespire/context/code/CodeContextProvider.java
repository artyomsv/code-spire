package dev.codespire.context.code;

import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.port.LanguageSupport;
import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
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
 */
public class CodeContextProvider implements ContextProvider {

    public static final String SOURCE = "CODE";
    private static final String KIND = "CODE_SNIPPET";

    /**
     * Cap on the number of snippets one contribution may return — a large diff can resolve far more
     * definitions than the prompt budget should spend on this one source.
     */
    public static final int MAX_SNIPPETS = 20;

    /** Body lines kept per snippet, beyond the always-kept signature and doc comment (SnippetExtractor). */
    private static final int MAX_BODY_LINES = 40;

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
        this.reader = reader;
        this.languages = List.copyOf(languages);
        this.pathAllowList = pathAllowList == null ? Set.of() : Set.copyOf(pathAllowList);
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
     * {@link Counts} that produced it. {@link #contribute} is still the SPI entry point and discards
     * the counts down to the plain contribution; this method exists so a same-package diagnostics test
     * can assert on the counts directly, and — the reason it must be {@code public} rather than merely
     * package-visible — so {@code ContextWorker} (a different module, {@code spire-review-worker}) can
     * call it and log the counts itself, under the reviewId MDC it already carries and this
     * framework-free module has no access to (see {@code spire-context-code}'s build file).
     *
     * <p>Counts are a return value, never provider state: one {@link Fetcher} is built fresh per call
     * (see its own javadoc), and a mutable counts field here would suffer the identical hazard —
     * interleaving one review's counts with another's on a provider instance shared across concurrent
     * requests.
     */
    public Resolved resolve(ContextRequest request) {
        long start = System.nanoTime();
        Fetcher fetcher = new Fetcher(reader, request.repo().full(), request.commit(), pathAllowList);
        CodeReferences refs = request.codeReferences();

        Map<String, Set<String>> filesByDefinitionPath = resolveDefinitionFiles(refs, fetcher);
        List<DefinitionCandidate> candidates =
                extractCandidates(filesByDefinitionPath, refs.identifiers(), fetcher);
        List<ContextItem> items = rankAndCap(candidates);

        // A real per-file error (5xx, rate limit) never sinks the whole contribution by itself — it
        // only surfaces as ERROR when it leaves this contribution with literally nothing to say;
        // that is what distinguishes "broken" from an ordinary, error-free EMPTY.
        ContribStatus status = items.isEmpty()
                ? (fetcher.hadError() ? ContribStatus.ERROR : ContribStatus.EMPTY)
                : ContribStatus.OK;
        ContextContribution contribution = new ContextContribution(SOURCE, status, items, latencyMs(start));
        Counts counts = new Counts(refs.identifiers().size(), candidates.size(), items.size(),
                candidates.size() - items.size());
        return new Resolved(contribution, counts);
    }

    /**
     * The pipeline's stage counts — the only thing that distinguishes "nothing to do" from
     * "systematically broken", since both report {@link ContribStatus#EMPTY} identically.
     *
     * @param extracted        identifiers the diff-side extraction handed this request ({@link
     *                         CodeReferences#identifiers()} size) — zero here means a diff with no
     *                         symbols to look up (e.g. YAML-only), which is correct and uninteresting.
     * @param resolved         candidate snippets found — an import matched a requested identifier AND
     *                         the definition file it resolved to was fetched AND
     *                         {@link SnippetExtractor} found the identifier declared in it — counted
     *                         before the {@link #MAX_SNIPPETS} budget is applied. Zero while
     *                         {@code extracted} is positive is the broken case: plenty to look up, none
     *                         of it resolved.
     * @param contributed      items actually present in the returned {@link ContextContribution}, after
     *                         ranking and the cap.
     * @param droppedForBudget resolved candidates cut by the cap ({@code resolved - contributed}) — a
     *                         nonzero value means snippets that DID resolve were discarded for space,
     *                         not that resolution failed; without this a deployment silently losing
     *                         good snippets to a full diff would look identical to one working fine.
     */
    public record Counts(int extracted, int resolved, int contributed, int droppedForBudget) {
    }

    /** One resolution run: the {@link ContextContribution} it produced, paired with its {@link Counts}. */
    public record Resolved(ContextContribution contribution, Counts counts) {
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
     */
    private List<DefinitionCandidate> extractCandidates(Map<String, Set<String>> filesByDefinitionPath,
            Set<String> identifiers, Fetcher fetcher) {
        List<String> sortedIdentifiers = new ArrayList<>(identifiers);
        Collections.sort(sortedIdentifiers);

        List<DefinitionCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : filesByDefinitionPath.entrySet()) {
            String definitionPath = entry.getKey();
            String content = fetcher.content(definitionPath);
            if (content == null) {
                continue;
            }
            for (String identifier : sortedIdentifiers) {
                String snippet = SnippetExtractor.extract(content, identifier, MAX_BODY_LINES);
                if (snippet == null) {
                    continue;
                }
                DefinitionCandidate candidate = new DefinitionCandidate(identifier, definitionPath, snippet);
                candidate.changedFiles.addAll(entry.getValue());
                candidates.add(candidate);
            }
        }
        return candidates;
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
                    candidate.snippet, candidate.definitionPath));
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
    private static final class Fetcher {
        private final SourceFileReader reader;
        private final String repo;
        private final String commit;
        private final Set<String> pathAllowList;
        private final Map<String, String> cache = new HashMap<>();
        private final Set<String> attempted = new HashSet<>();
        private boolean hadError;

        Fetcher(SourceFileReader reader, String repo, String commit, Set<String> pathAllowList) {
            this.reader = reader;
            this.repo = repo;
            this.commit = commit;
            this.pathAllowList = pathAllowList;
        }

        /**
         * Reads one of the diff's own changed files. Never subject to {@code pathAllowList}: that list
         * narrows where import resolution may wander, not the files already under review.
         */
        String readChangedFile(String path) {
            return read(path);
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
        private String readCandidate(String path) {
            return isAllowed(path) ? read(path) : null;
        }

        /** The content already fetched for {@code path}, with no further I/O. */
        String content(String path) {
            return cache.get(path);
        }

        boolean hadError() {
            return hadError;
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
         */
        private boolean isAllowed(String path) {
            if (isTraversal(path)) {
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

        /**
         * One fetch per path per {@link #fetch} call: a cached outcome — success, absence, or a prior
         * error — never calls {@link SourceFileReader#read} again for the same path.
         */
        private String read(String path) {
            if (attempted.contains(path)) {
                return cache.get(path);
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
