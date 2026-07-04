package dev.codespire.contract.event;

import dev.codespire.contract.scm.RepoRef;

/** reviewId = the ReviewLifecycle aggregate stream id: review::{workspace}/{slug}#{prId} (CONTRACT §2). */
public final class ReviewIds {

    private static final String PREFIX = "review::";

    private ReviewIds() {
    }

    public static String reviewId(RepoRef repo, long prId) {
        return PREFIX + repo.full() + "#" + prId;
    }

    /** The repo + prId encoded in a reviewId — the id IS the address; nothing else is needed. */
    public record Parsed(RepoRef repo, long prId) {
    }

    /**
     * Inverse of {@link #reviewId}. Throws on malformed input — callers must
     * never fall back to a synthetic repo (that converts a parse miss into
     * silent data loss; review finding H2).
     */
    public static Parsed parse(String reviewId) {
        if (reviewId == null || !reviewId.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not a reviewId: " + reviewId);
        }
        String rest = reviewId.substring(PREFIX.length());
        int hash = rest.lastIndexOf('#');
        int slash = rest.indexOf('/');
        if (hash <= 0 || slash <= 0 || slash >= hash) {
            throw new IllegalArgumentException("Not a reviewId: " + reviewId);
        }
        return new Parsed(
                new RepoRef(rest.substring(0, slash), rest.substring(slash + 1, hash)),
                Long.parseLong(rest.substring(hash + 1)));
    }
}
