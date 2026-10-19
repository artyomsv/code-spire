package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.PostedReview;
import dev.codespire.contract.scm.ReviewSubmission;
import dev.codespire.contract.scm.ReviewSubmission.InlineComment;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * The posting ladder of one review (spec §5.1 step 2): the full review; on a refusal once more as COMMENT
 * with the verdict in words (D6); on a rejected anchor once more with the body only (D7). At most three
 * posts. One instance posts one review: it keeps the rung it is on.
 */
final class ReviewPostLadder {

    /** Builds the submission for a rung: the verdict to post and the comments it carries. */
    interface Submissions {
        ReviewSubmission submission(ReviewVerdict posting, List<InlineComment> comments);
    }

    private static final Logger LOG = Logger.getLogger(ReviewPostLadder.class);
    private static final int MAX_POST_ATTEMPTS = 3;

    private final CommentSink sink;
    private final PostComments command;
    private final Submissions submissions;
    private ReviewVerdict posting;
    private List<InlineComment> comments = List.of();
    private boolean refused;
    private boolean bodyOnly;

    ReviewPostLadder(CommentSink sink, PostComments command, Submissions submissions) {
        this.sink = sink;
        this.command = command;
        this.submissions = submissions;
        this.posting = command.verdict();
    }

    /** @return null when the forge has no native review */
    NativeReviewPost.Posted post(List<InlineComment> fullComments) {
        comments = fullComments;
        for (int attempt = 1; attempt <= MAX_POST_ATTEMPTS; attempt++) {
            try {
                Optional<PostedReview> posted = sink.postReview(command.repo(), command.prId(), command.commit(),
                        submissions.submission(posting, comments));
                return posted.map(review -> new NativeReviewPost.Posted(review, bodyOnly)).orElse(null);
            } catch (CommentSink.ReviewVerdictRefused e) {
                stepDownToComment(e);
            } catch (CommentSink.ReviewAnchorRejected e) {
                stepDownToBodyOnly(e);
            }
        }
        throw new IllegalStateException("review post attempts exhausted for " + command.reviewId());
    }

    private void stepDownToComment(CommentSink.ReviewVerdictRefused refusal) {
        if (refused) {
            throw refusal;
        }
        LOG.infof("review %s: the forge refused %s — posting as COMMENT with the verdict in words",
                command.reviewId(), posting);
        refused = true;
        posting = ReviewVerdict.COMMENT;
    }

    private void stepDownToBodyOnly(CommentSink.ReviewAnchorRejected rejection) {
        if (bodyOnly) {
            throw rejection;
        }
        LOG.infof("review %s: the forge rejected an anchor — posting the body, then the findings one by one",
                command.reviewId());
        bodyOnly = true;
        comments = List.of();
    }
}
