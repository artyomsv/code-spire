package dev.codespire.scm.github;

import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.scm.RepoRef;

/** Lands a pull request on GitHub. */
public final class GitHubPullRequestLander implements PullRequestLander {
    private final GitHubClient client;

    public GitHubPullRequestLander(GitHubClient client) {
        this.client = client;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public LandState read(RepoRef repo, long pullRequest) {
        throw new UnsupportedOperationException("RED");
    }

    @Override
    public AllowedMethods allowedMethods(RepoRef repo) {
        throw new UnsupportedOperationException("RED");
    }

    @Override
    public MergeOutcome merge(RepoRef repo, long pullRequest, String expectedHead, MergeMethod method) {
        throw new UnsupportedOperationException("RED");
    }

    @Override
    public BranchDeletion deleteBranch(RepoRef repo, String branch, String expectedSha) {
        throw new UnsupportedOperationException("RED");
    }
}
