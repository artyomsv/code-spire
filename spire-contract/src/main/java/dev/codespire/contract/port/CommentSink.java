package dev.codespire.contract.port;

import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;

/**
 * SCM write adapter (CONTRACT §7). Thread-reply and PR-author are FIRST-CLASS
 * (unimplemented in PR-Agent's Bitbucket providers — a founding gap).
 *
 * <p>Every reference here is opaque: a ThreadRef is a comment id on some APIs and a discussion id on
 * others, and the head commit is all the anchoring detail the caller supplies. What those values mean
 * — and anything further an API needs — stays inside the adapter. See SCM-MAPPING.md.
 */
public interface CommentSink {

    ScmType type();

    CommentRef postSummary(RepoRef repo, long prId, String bodyMd);

    /**
     * Post an inline finding against {@code headCommit}. An API needing more than the head to place
     * a comment (a base/start SHA pair) resolves that itself — the caller supplies the one commit it
     * actually knows.
     */
    CommentRef postInline(RepoRef repo, long prId, String headCommit, InlineAnchor anchor, String bodyMd);

    CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd);

    Author getPullRequestAuthor(RepoRef repo, long prId);

    /** Outcome of a resolve attempt — ALREADY_RESOLVED means a human beat us to it. */
    enum ThreadResolution { RESOLVED_NOW, ALREADY_RESOLVED, UNSUPPORTED }

    /**
     * Resolve a review thread. Every current SCM adapter implements this; the default exists
     * for stubs and for any future provider whose API cannot resolve a thread, degrading the
     * caller to reply-only instead of failing the review. An adapter must also return
     * UNSUPPORTED when it matched no resolvable thread — reporting a resolve that did not
     * happen silently skips both the resolve and the reply.
     */
    default ThreadResolution resolveThread(RepoRef repo, long prId, ThreadRef thread) {
        return ThreadResolution.UNSUPPORTED;
    }

    /**
     * Rewrite a thread's opening comment in place (the summary update on a re-review).
     *
     * <p>Takes the THREAD, not a comment id, so the caller never has to know how the two relate on
     * a given API — the adapter resolves which comment carries the body. That keeps one opaque ref
     * flowing end to end: the same value identifies where replies land and what gets rewritten, and
     * nothing outside the adapter interprets it.
     */
    default CommentRef updateComment(RepoRef repo, long prId, ThreadRef thread, String bodyMd) {
        throw new UnsupportedOperationException(type() + " cannot update comments");
    }
}
