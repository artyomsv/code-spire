package dev.codespire.contract.port;

import java.util.Set;

/**
 * Finds the references a context source can resolve inside free text — the PR's title, branch and
 * description, and later the text those references themselves retrieve.
 *
 * <p>Separate from {@link ContextProvider} on purpose: extraction runs when the diff is fetched,
 * which is before any context credential has been brokered, so there is no provider instance to ask.
 * An implementation is therefore stateless and credential-free — it recognises its own reference
 * syntax and nothing more.
 *
 * <p>This exists so the core never parses one source's format. A worker asks every registered
 * extractor for candidates and unions them into one neutral set; each provider later narrows that
 * set to what it actually recognises. Adding a context source means adding an extractor beside its
 * provider, with no edit to the pipeline.
 */
public interface ContextReferenceSource {

    /** Matches the {@link ContextProvider#source()} this extractor feeds. */
    String source();

    /**
     * Every reference this source recognises in the given texts, favouring recall — the provider
     * narrows later, so a false candidate costs nothing but an unmatched string.
     */
    Set<String> referencesIn(String... texts);

    /**
     * The comparison form of a reference, so two spellings of the same thing dedupe across rounds
     * (a differently-cased key, two URL shapes for one page). The default compares trimmed text.
     */
    default String normalize(String reference) {
        return reference == null ? "" : reference.trim();
    }
}
