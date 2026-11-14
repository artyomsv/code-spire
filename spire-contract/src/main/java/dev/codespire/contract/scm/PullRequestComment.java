package dev.codespire.contract.scm;

/**
 * One comment on a pull request, as read for context links (harness reviews, step 8b): who wrote it and what it says.
 * Read on demand and never stored; the body is held only long enough to extract links from it.
 */
public record PullRequestComment(Author author, String body) {
}
