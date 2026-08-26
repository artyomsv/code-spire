package dev.codespire.contract.port;

import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContextResolutionCounts;

/**
 * Optional {@link ContextProvider} capability: a provider whose resolution pipeline can report its
 * own {@link ContextResolutionCounts} alongside the {@link ContextContribution} it produces. Not every
 * provider has stages worth counting (a Jira/Confluence-style provider fetches-or-doesn't per
 * reference), so this rides as a separate, gated capability rather than a method every
 * {@link ContextProvider} must implement — the same shape {@link ThreadSource} already uses for the
 * SCM comment sinks that can also read a thread back. A caller checks with a plain
 * {@code instanceof ContextResolutionSource}; the aggregator never needs to know which concrete
 * provider class implements it, only that this one does.
 */
public interface ContextResolutionSource {

    Resolution resolve(ContextRequest request);

    /** One resolution run: the {@link ContextContribution} it produced, paired with its counts. */
    record Resolution(ContextContribution contribution, ContextResolutionCounts counts) {
    }
}
