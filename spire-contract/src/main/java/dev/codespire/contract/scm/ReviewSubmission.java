package dev.codespire.contract.scm;

import dev.codespire.contract.review.ReviewVerdict;

import java.util.List;
import java.util.Objects;

/** One forge-native review to post: verdict, body, and the inline comments inside it (ADR-047). */
public record ReviewSubmission(ReviewVerdict verdict, String bodyMd, List<InlineComment> comments) {

    public ReviewSubmission {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(bodyMd, "bodyMd");
        comments = comments == null ? List.of() : List.copyOf(comments);
    }

    /** One inline comment of the review; the anchor is resolved against the reviewed diff. */
    public record InlineComment(InlineAnchor anchor, String bodyMd) {
    }

    public ReviewSubmission withVerdict(ReviewVerdict replacement) {
        return new ReviewSubmission(replacement, bodyMd, comments);
    }

    public ReviewSubmission withBody(String replacement) {
        return new ReviewSubmission(verdict, replacement, comments);
    }

    /** The same review with no inline comments — the D7 fallback. */
    public ReviewSubmission bodyOnly() {
        return new ReviewSubmission(verdict, bodyMd, List.of());
    }

    /** The header line, which {@code CommentSink.findReview} matches a posted body on. */
    public String firstLine() {
        int end = bodyMd.indexOf('\n');
        return end < 0 ? bodyMd : bodyMd.substring(0, end);
    }
}
