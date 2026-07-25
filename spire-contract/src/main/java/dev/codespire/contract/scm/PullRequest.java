package dev.codespire.contract.scm;

/**
 * Provider-neutral pull request. {@code prId} is repo-scoped (Bitbucket id /
 * GitHub number / GitLab iid / DC id).
 *
 * <p>{@code headCommit} is the branch head under review — one commit, because that is the whole of
 * what the core does with it: compare it against the run's commit to notice the head moved. Extra
 * SHAs an API needs to anchor a comment are the adapter's to obtain.
 */
public record PullRequest(RepoRef repo,
                          long prId,
                          String title,
                          String description,
                          String sourceBranch,
                          String targetBranch,
                          String headCommit,
                          Author author,
                          String htmlUrl) {
}
