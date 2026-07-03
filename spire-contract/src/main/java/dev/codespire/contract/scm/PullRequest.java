package dev.codespire.contract.scm;

/**
 * Provider-neutral pull request. {@code prId} is repo-scoped (Bitbucket id /
 * GitHub number / GitLab iid / DC id).
 */
public record PullRequest(RepoRef repo,
                          long prId,
                          String title,
                          String description,
                          String sourceBranch,
                          String targetBranch,
                          DiffRefs diffRefs,
                          Author author,
                          String htmlUrl) {
}
