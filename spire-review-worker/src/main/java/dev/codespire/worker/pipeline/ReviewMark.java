package dev.codespire.worker.pipeline;

import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.PostedReview;

import java.util.Map;

/**
 * The {@code REVIEW} idempotency mark: {@code review:<id>:<verdict>}. The verdict is the one the forge
 * ACCEPTED, which the posting ladder may have stepped down from the one asked for, so a redelivery whose
 * look-up misses reports what stands, not what was asked (review of PR #196, M-2). A mark written before
 * the verdict was kept has none, and unknown stays unknown.
 */
final class ReviewMark {

    private ReviewMark() {
    }

    static String of(PostedReview review) {
        return NativeReviewPost.REF_PREFIX + review.reviewRef() + (review.verdict() == null ? "" : ":" + review.verdict());
    }

    static PostedReview parse(String mark) {
        String rest = mark.substring(NativeReviewPost.REF_PREFIX.length());
        int cut = rest.lastIndexOf(':');
        if (cut > 0) {
            for (ReviewVerdict verdict : ReviewVerdict.values()) {
                if (verdict.name().equals(rest.substring(cut + 1))) {
                    return new PostedReview(rest.substring(0, cut), verdict, Map.of());
                }
            }
        }
        return new PostedReview(rest, null, Map.of());
    }
}
