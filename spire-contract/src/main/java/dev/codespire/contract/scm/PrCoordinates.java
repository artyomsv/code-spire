package dev.codespire.contract.scm;

/**
 * The repo-scoped coordinates of a pull/merge request extracted from its URL:
 * the {@link RepoRef} and the repo-scoped number ({@code prId} — a Bitbucket id,
 * a GitHub PR number, or a GitLab MR iid).
 */
public record PrCoordinates(RepoRef repo, long prId) {
}
