package dev.codespire.contract.event;

import dev.codespire.contract.scm.RepoRef;

/** reviewId = the ReviewLifecycle aggregate stream id: review::{workspace}/{slug}#{prId} (CONTRACT §2). */
public final class ReviewIds {

    private ReviewIds() {
    }

    public static String reviewId(RepoRef repo, long prId) {
        return "review::" + repo.full() + "#" + prId;
    }
}
