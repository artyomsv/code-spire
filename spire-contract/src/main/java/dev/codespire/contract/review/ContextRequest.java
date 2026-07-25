package dev.codespire.contract.review;

import dev.codespire.contract.scm.RepoRef;

import java.util.Set;

/**
 * What the context aggregator asks every provider to resolve.
 *
 * <p>{@code references} is one neutral, recall-favouring set of candidates found in the PR's own
 * text — issue keys, page links, whatever a registered
 * {@link dev.codespire.contract.port.ContextReferenceSource} recognises. Each provider narrows it to
 * the entries it can actually resolve, so nothing outside a provider needs to know which syntax
 * belongs to which source.
 */
public record ContextRequest(String reviewId,
                             RepoRef repo,
                             long prId,
                             String commit,
                             Set<String> references,
                             Set<String> expectedSources) {

    public ContextRequest {
        references = references == null ? null : Set.copyOf(references);
        expectedSources = expectedSources == null ? null : Set.copyOf(expectedSources);
    }
}
