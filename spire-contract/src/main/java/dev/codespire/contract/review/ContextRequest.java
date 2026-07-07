package dev.codespire.contract.review;

import dev.codespire.contract.scm.RepoRef;

import java.util.List;
import java.util.Set;

public record ContextRequest(String reviewId,
                             RepoRef repo,
                             long prId,
                             String commit,
                             Set<String> ticketKeys,
                             List<String> links,
                             Set<String> expectedSources) {

    public ContextRequest {
        ticketKeys = ticketKeys == null ? null : Set.copyOf(ticketKeys);
        links = links == null ? null : List.copyOf(links);
        expectedSources = expectedSources == null ? null : Set.copyOf(expectedSources);
    }
}
