package dev.codespire.contract.port;

import dev.codespire.contract.scm.FilePatch;

import java.util.List;
import java.util.Set;

/**
 * One language's knowledge of how a change refers to code elsewhere in its repository.
 *
 * <p>Split across the wire boundary on purpose. {@link #identifiersIn} runs at diff-fetch, where the
 * parsed diff lives; the rest run in the context provider, which is the only place that has fetched
 * the file's text. A diff carries hunks, not files, and imports live at the top of a file — so an
 * import block is generally NOT in a hunk and cannot be read at diff-fetch.
 *
 * <p>Adding a language is a new bean, not a core edit. A file whose language has no implementation
 * contributes nothing and its review proceeds exactly as before.
 */
public interface LanguageSupport {

    /** Language tags this handles, as produced by {@code Languages.of(path)} — e.g. "java". */
    Set<String> languages();

    /**
     * Identifiers referenced in the patch's CHANGED lines only — added and removed, never context
     * lines. Context lines are unchanged code and would flood the set with the whole file's
     * vocabulary, which is the difference between "this change touches three things" and "this file
     * mentions forty".
     */
    Set<String> identifiersIn(FilePatch patch);

    /** The import statements in a file's full text, in source order. */
    List<ImportRef> importsIn(String fileContent);

    /**
     * Repository paths an import could resolve to, best candidate first. Several are returned
     * because resolution is conventional rather than certain — a Java source root, a TypeScript
     * extension or {@code index.ts}. The caller tries them in order and stops at the first that
     * exists.
     */
    List<String> candidatePaths(ImportRef ref, String importingPath);

    /**
     * What a file's FULL TEXT declares and what it mentions — rung 2's index (ADR-026 §7).
     *
     * <p>Distinct from {@link #identifiersIn}, which reads a diff's changed lines to answer "what
     * does this change touch". This reads a whole file to answer "what is in here", so the index can
     * later be asked the reverse question — which files mention a symbol.
     *
     * <p>Defaulted to empty so a language that has not implemented it contributes nothing and its
     * reviews proceed exactly as before, matching this interface's existing promise that adding a
     * language is a new bean rather than a core edit.
     */
    default Symbols symbolsIn(String fileContent) {
        return Symbols.NONE;
    }

    /**
     * One file's structure. Names only — never source text, so nothing here can be persisted in
     * violation of ADR-011.
     *
     * @param defines    identifiers this file declares (types, methods, exported members)
     * @param references identifiers this file mentions but does not declare
     */
    record Symbols(Set<String> defines, Set<String> references) {

        public static final Symbols NONE = new Symbols(Set.of(), Set.of());

        public Symbols {
            defines = defines == null ? Set.of() : Set.copyOf(defines);
            references = references == null ? Set.of() : Set.copyOf(references);
        }
    }
    /**
     * One import. {@code symbols} is what the statement brings into scope — the names that can be
     * intersected with {@link #identifiersIn}. {@code specifier} is the raw module reference.
     */
    record ImportRef(String specifier, Set<String> symbols) {

        public ImportRef {
            symbols = symbols == null ? Set.of() : Set.copyOf(symbols);
        }
    }
}
