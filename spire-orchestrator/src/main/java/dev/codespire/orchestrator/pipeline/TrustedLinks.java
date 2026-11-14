package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.LinkSource;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The links an untrusted review keeps (step 8b): the union of the sources whose author is trusted, and the timeline's
 * account of who counted and who did not, by handle (or id when there is none), never by what a comment said.
 */
record TrustedLinks(Set<String> links, List<String> counted, List<String> notCounted) {

    static TrustedLinks select(List<LinkSource> sources, Map<String, LinkAuthorTrust.Verdict> verdicts) {
        Set<String> links = new LinkedHashSet<>();
        Map<String, String> counted = new LinkedHashMap<>();
        Map<String, String> notCounted = new LinkedHashMap<>();
        for (LinkSource source : sources) {
            String key = LinkAuthorTrust.keyOf(source);
            LinkAuthorTrust.Verdict verdict = verdicts.get(key);
            if (verdict != null && verdict.trusted()) {
                links.addAll(source.links());
                counted.putIfAbsent(key, label(source));
            } else {
                String reason = verdict == null ? "NOT_READ" : verdict.reason();
                notCounted.putIfAbsent(key, label(source) + " (" + reason + ")");
            }
        }
        return new TrustedLinks(Set.copyOf(links), List.copyOf(counted.values()), List.copyOf(notCounted.values()));
    }

    /** The {@code ContextLinksTrusted} line: what was kept and whose it was. */
    String keptDetail() {
        return "context links kept from trusted authors: " + links.size() + " link(s); counted: "
                + String.join(", ", counted) + notCountedSuffix();
    }

    /** The {@code ContextLinksSkipped} line when no author counted. */
    String skippedDetail() {
        return "context links skipped: untrusted pull request, no trusted author" + notCountedSuffix();
    }

    private String notCountedSuffix() {
        return notCounted.isEmpty() ? "" : "; not counted: " + String.join(", ", notCounted);
    }

    private static String label(LinkSource source) {
        String handle = source.authorHandle();
        if (handle != null && !handle.isBlank()) {
            return "@" + handle;
        }
        String id = LinkAuthorTrust.keyOf(source);
        return id.isEmpty() ? "unknown author" : "id " + id;
    }
}
