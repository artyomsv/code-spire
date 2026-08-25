package dev.codespire.contract.port;

import java.util.List;

/**
 * Rung 2's structural symbol table (ADR-026). Defined here, and unread in rung 1, because ADR-021
 * forbids the Apache-2.0 provider depending on the FSL worker that owns the schema — so the port
 * must exist for rung 2 to be an addition rather than a refactor of rung 1. This is the
 * {@code BlobStore} arrangement repeated.
 *
 * <p><b>The index is a hint, never an answer.</b> A caller takes candidates from here, re-fetches
 * them at the review commit, and confirms the reference still exists before citing it. That is what
 * removes the staleness problem: there is no invalidation pass, and no stored row can speak for
 * current code.
 */
public interface SymbolIndex {

    /** Files known to reference the symbol. Candidates only — confirm before citing. */
    List<String> callersOf(String repo, String symbol);

    /** Record what a file was observed to define and reference, at the commit it was read at. */
    void record(String repo, String path, String commit, List<String> defines, List<String> references);
}
