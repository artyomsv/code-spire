package dev.codespire.worker.adapters;

import dev.codespire.context.confluence.ConfluenceReferenceSource;
import dev.codespire.context.jira.JiraReferenceSource;
import dev.codespire.contract.port.ContextReferenceSource;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Composition root for reference extraction — the context-side counterpart to
 * {@link WorkerScmClients}.
 *
 * <p>Extraction cannot go through {@code ContextProvider}: it runs when the diff is fetched, before
 * any context credential has been brokered, so there is no configured provider to ask. The
 * extractors are stateless, so they are simply listed here. Naming them is this class's whole job,
 * which is why it is the only file on this path that mentions a context source: the pipeline sees
 * one neutral set of candidate references and never learns whose syntax produced which entry.
 */
@ApplicationScoped
public class WorkerContextReferences {

    private final List<ContextReferenceSource> extractors =
            List.of(new JiraReferenceSource(), new ConfluenceReferenceSource());

    /** Every candidate reference any context source recognises in the given texts. */
    public Set<String> referencesIn(String... texts) {
        Set<String> all = new LinkedHashSet<>();
        for (ContextReferenceSource extractor : extractors) {
            all.addAll(extractor.referencesIn(texts));
        }
        return all;
    }

    /**
     * Candidates not already seen, compared in each extractor's own normalized form so a
     * differently-spelled repeat does not start another retrieval round.
     *
     * <p>{@code seen} is updated with the normalized form of everything returned.
     */
    public Set<String> freshReferencesIn(Set<String> seen, String... texts) {
        Set<String> fresh = new LinkedHashSet<>();
        for (ContextReferenceSource extractor : extractors) {
            for (String candidate : extractor.referencesIn(texts)) {
                if (seen.add(extractor.normalize(candidate))) {
                    fresh.add(candidate);
                }
            }
        }
        return fresh;
    }

    /** The normalized form of a reference, for seeding {@code seen} from an existing set. */
    public Set<String> normalizeAll(Set<String> references) {
        Set<String> out = new LinkedHashSet<>();
        for (String reference : references) {
            for (ContextReferenceSource extractor : extractors) {
                out.add(extractor.normalize(reference));
            }
        }
        return out;
    }
}
