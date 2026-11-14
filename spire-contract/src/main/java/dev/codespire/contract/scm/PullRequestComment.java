package dev.codespire.contract.scm;

import dev.codespire.contract.review.LinkSource;

/**
 * One comment on a pull request, as read for context links (harness reviews, step 8b): who wrote it and what it says, and what the forge itself says about the author.
 * Read on demand and never stored; the body is held only long enough to extract links from it.
 */
public record PullRequestComment(Author author, String body, LinkSource.Vouch vouch) {

    /** A comment whose forge says nothing about its author. */
    public PullRequestComment(Author author, String body) {
        this(author, body, LinkSource.Vouch.UNKNOWN);
    }
}
