package dev.codespire.context.gitlab;

import dev.codespire.contract.port.ContextReferenceSource;

import java.util.Set;

/**
 * Recognises GitLab references — {@code #12} an issue, {@code !34} a merge request, {@code &7} an
 * epic, the qualified {@code group/project#12}, and the three URL shapes — in free text.
 *
 * <p>Stateless and credential-free, so extraction can run at diff-fetch time before any credential
 * is brokered. Narrowing to this host, to the allowed projects, and to the platform the review runs
 * on happens later in {@link GitLabIssueContextProvider}.
 */
public final class GitLabIssueReferenceSource implements ContextReferenceSource {

    @Override
    public String source() {
        return GitLabIssueContextProvider.SOURCE;
    }

    @Override
    public Set<String> referencesIn(String... texts) {
        return GitLabIssueRefs.candidates(texts);
    }

    @Override
    public String normalize(String reference) {
        return GitLabIssueRefs.normalize(reference);
    }
}
