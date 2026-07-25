package dev.codespire.context.confluence;

import dev.codespire.contract.port.ContextReferenceSource;

import java.util.Set;

/**
 * Recognises Confluence page links in free text.
 *
 * <p>Stateless and credential-free, so the pipeline can extract references at diff-fetch time
 * without a configured provider. Narrowing to pages on the instance's own host happens later, in
 * {@link ConfluenceContextProvider} — a candidate here is any URL that could be a page.
 */
public final class ConfluenceReferenceSource implements ContextReferenceSource {

    @Override
    public String source() {
        return ConfluenceContextProvider.SOURCE;
    }

    @Override
    public Set<String> referencesIn(String... texts) {
        return ConfluenceLinks.candidates(texts);
    }

    /** Links compare by page id when they carry one, so two URL shapes for one page dedupe. */
    @Override
    public String normalize(String reference) {
        return ConfluenceLinks.pageId(reference).orElse(reference == null ? "" : reference.trim());
    }
}
