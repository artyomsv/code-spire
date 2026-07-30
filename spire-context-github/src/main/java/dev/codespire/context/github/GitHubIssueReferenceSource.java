package dev.codespire.context.github;

import dev.codespire.contract.port.ContextReferenceSource;

import java.util.Set;

/**
 * Recognises GitHub issue references ({@code #123}, {@code owner/repo#123}, issue and pull-request
 * URLs) in free text.
 *
 * <p>Stateless and credential-free, so the pipeline can extract references at diff-fetch time before
 * any credential is brokered. Narrowing — to this host, to the allowed repositories, and to the
 * platform the review actually runs on — happens later in {@link GitHubIssueContextProvider}, which
 * is the part that has configuration and knows the review's platform.
 */
public final class GitHubIssueReferenceSource implements ContextReferenceSource {

    @Override
    public String source() {
        return GitHubIssueContextProvider.SOURCE;
    }

    @Override
    public Set<String> referencesIn(String... texts) {
        return GitHubIssueRefs.candidates(texts);
    }

    /** Repository names compare case-insensitively, so two spellings dedupe. */
    @Override
    public String normalize(String reference) {
        return GitHubIssueRefs.normalize(reference);
    }
}
