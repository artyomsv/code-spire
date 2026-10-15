package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostComments;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PostedReview;
import dev.codespire.contract.scm.ReviewSubmission;
import dev.codespire.contract.scm.ReviewSubmission.InlineComment;
import dev.codespire.diff.Anchors;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The forge-native review path of PostComments (ADR-047, spec §5.1): claim, find, post, mark at once,
 * read back, fall back, update the body, dismiss a hold. Package-private; {@link ReviewWorker} owns it
 * and supplies the rendering and the legacy inline poster it already has.
 */
final class NativeReviewPost {

    static final String REVIEW_KEY = "REVIEW";
    static final String REF_PREFIX = "review:";
    private static final Logger LOG = Logger.getLogger(NativeReviewPost.class);
    private static final int MAX_POST_ATTEMPTS = 3;

    /** Posts one inline comment the legacy way: the worker's backoff and throttle. */
    interface InlinePoster {
        CommentRef post(InlineAnchor anchor, String bodyMd);
    }

    /** Renders the summary body for the given unanchored and failed lists. */
    interface BodyRenderer {
        String render(List<Finding> unanchored, List<Finding> failed);
    }

    record Outcome(String reviewRef, ReviewVerdict postedVerdict, List<CommentsPosted.PostedInline> inline, String reviewUrl) {
    }

    private record Anchored(InlineAnchor anchor, List<Finding> findings) {
    }

    private record Attempt(PostedReview review, boolean bodyOnly) {
    }

    private final CommentIdempotencyStore idempotency;
    private final CommentSink sink;
    private final PostComments command;
    private final Diff diff;
    private final BodyRenderer bodies;
    private final InlinePoster inlinePoster;
    private final Function<Finding, String> inlineBodies;

    NativeReviewPost(CommentIdempotencyStore idempotency, CommentSink sink, PostComments command, Diff diff,
                     BodyRenderer bodies, InlinePoster inlinePoster, Function<Finding, String> inlineBodies) {
        this.idempotency = idempotency;
        this.sink = sink;
        this.command = command;
        this.diff = diff;
        this.bodies = bodies;
        this.inlinePoster = inlinePoster;
        this.inlineBodies = inlineBodies;
    }

    /** @return empty when the forge has no native review: the caller runs the legacy path. */
    Optional<Outcome> run() {
        List<Finding> unanchored = new ArrayList<>();
        List<Finding> failed = new ArrayList<>();
        Map<String, Anchored> anchored = resolveAnchors(unanchored);
        Map<String, String> alreadyPosted = claimAnchors(anchored.keySet());
        List<InlineComment> comments = commentsFor(anchored, alreadyPosted);

        boolean inlineFallback = false;
        PostedReview review;
        if (idempotency.claim(command.reviewId(), command.commit(), REVIEW_KEY)
                instanceof CommentIdempotencyStore.Claim.AlreadyPosted marked) {
            String ref = marked.postedRef().substring(REF_PREFIX.length());
            review = sink.findReview(command.repo(), command.prId(), command.commit(),
                            submission(command.verdict(), unanchored, failed, comments))
                    .orElse(new PostedReview(ref, null, Map.of()));
        } else {
            // The claim does not say whether it was fresh or reclaimed, so look first: an attempt that
            // died between the POST and the mark left a review GitHub still holds (spec §5.1 step 3).
            Optional<PostedReview> found = sink.findReview(command.repo(), command.prId(), command.commit(),
                    submission(command.verdict(), unanchored, failed, comments));
            if (found.isPresent()) {
                review = found.get();
            } else {
                Attempt attempt = postWithFallbacks(unanchored, failed, comments);
                if (attempt == null) {
                    return Optional.empty();
                }
                review = attempt.review();
                inlineFallback = attempt.bodyOnly();
            }
            // At once, before any read-back or fallback: everything after this is a read, a per-anchor
            // claimed post, or an idempotent PUT, so a crash later re-enters here and finds the mark.
            idempotency.markPosted(command.reviewId(), command.commit(), REVIEW_KEY, REF_PREFIX + review.reviewRef());
        }

        List<CommentsPosted.PostedInline> inline = settleInline(anchored, alreadyPosted, review, inlineFallback, failed);
        if (inlineFallback || !failed.isEmpty()) {
            sink.updateReviewBody(command.repo(), command.prId(), review.reviewRef(),
                    body(unanchored, failed, review.verdict() == null ? command.verdict() : review.verdict()));
        }
        if (Boolean.FALSE.equals(command.reviewerMayApprove())) {
            dismissQuietly(review.reviewRef());
        }
        return Optional.of(new Outcome(review.reviewRef(), review.verdict(), inline, review.reviewUrl()));
    }

    private Map<String, Anchored> resolveAnchors(List<Finding> unanchored) {
        Map<String, Anchored> anchored = new LinkedHashMap<>();
        for (Finding finding : command.findings().findings()) {
            Optional<InlineAnchor> anchor = Anchors.resolveLine(
                    diff.files(), finding.path(), finding.range().startLine(), finding.range().endLine());
            if (anchor.isEmpty()) {
                unanchored.add(finding);
                continue;
            }
            anchored.computeIfAbsent(anchor.get().anchorKey(), key -> new Anchored(anchor.get(), new ArrayList<>()))
                    .findings().add(finding);
        }
        return anchored;
    }

    /** Claims every anchor key before any call; answers the keys a previous attempt already posted. */
    private Map<String, String> claimAnchors(Iterable<String> keys) {
        Map<String, String> alreadyPosted = new LinkedHashMap<>();
        for (String key : keys) {
            if (idempotency.claim(command.reviewId(), command.commit(), key)
                    instanceof CommentIdempotencyStore.Claim.AlreadyPosted already) {
                alreadyPosted.put(key, already.postedRef());
            }
        }
        return alreadyPosted;
    }

    private List<InlineComment> commentsFor(Map<String, Anchored> anchored, Map<String, String> alreadyPosted) {
        List<InlineComment> comments = new ArrayList<>();
        anchored.forEach((key, entry) -> {
            if (!alreadyPosted.containsKey(key)) {
                for (Finding finding : entry.findings()) {
                    comments.add(new InlineComment(entry.anchor(), inlineBodies.apply(finding)));
                }
            }
        });
        return comments;
    }

    private ReviewSubmission submission(ReviewVerdict posting, List<Finding> unanchored, List<Finding> failed,
                                        List<InlineComment> comments) {
        return new ReviewSubmission(posting, body(unanchored, failed, posting), comments);
    }

    /** The verdict in words when the posted verdict is COMMENT and the assessment is not (D3, D6). */
    private String body(List<Finding> unanchored, List<Finding> failed, ReviewVerdict posting) {
        String rendered = bodies.render(unanchored, failed);
        return posting == ReviewVerdict.COMMENT && command.assessment() != ReviewVerdict.COMMENT
                ? withVerdictLine(rendered, command.assessment()) : rendered;
    }

    static String withVerdictLine(String body, ReviewVerdict assessment) {
        String line = "**Verdict:** " + (assessment == ReviewVerdict.APPROVE ? "approve" : "changes requested") + "\n\n";
        int cut = body.indexOf("\n\n");
        return cut < 0 ? body + "\n\n" + line : body.substring(0, cut + 2) + line + body.substring(cut + 2);
    }

    /**
     * Full review; on a refusal once more as COMMENT with the verdict line (D6); on a rejected anchor
     * once more with the body only (D7). At most three posts.
     *
     * @return null when the forge has no native review
     */
    private Attempt postWithFallbacks(List<Finding> unanchored, List<Finding> failed, List<InlineComment> comments) {
        ReviewVerdict posting = command.verdict();
        List<InlineComment> current = comments;
        boolean refused = false;
        boolean bodyOnly = false;
        for (int attempt = 1; attempt <= MAX_POST_ATTEMPTS; attempt++) {
            try {
                Optional<PostedReview> posted = sink.postReview(command.repo(), command.prId(), command.commit(),
                        submission(posting, unanchored, failed, current));
                if (posted.isEmpty()) {
                    return null;
                }
                return new Attempt(posted.get(), bodyOnly);
            } catch (CommentSink.ReviewVerdictRefused e) {
                if (refused) {
                    throw e;
                }
                LOG.infof("review %s: the forge refused %s — posting as COMMENT with the verdict in words",
                        command.reviewId(), posting);
                refused = true;
                posting = ReviewVerdict.COMMENT;
            } catch (CommentSink.ReviewAnchorRejected e) {
                if (bodyOnly) {
                    throw e;
                }
                LOG.infof("review %s: the forge rejected an anchor — posting the body, then the findings one by one",
                        command.reviewId());
                bodyOnly = true;
                current = List.of();
            }
        }
        throw new IllegalStateException("review post attempts exhausted for " + command.reviewId());
    }

    private List<CommentsPosted.PostedInline> settleInline(Map<String, Anchored> anchored, Map<String, String> alreadyPosted,
                                                            PostedReview review, boolean inlineFallback, List<Finding> failed) {
        List<CommentsPosted.PostedInline> inline = new ArrayList<>();
        anchored.forEach((key, entry) -> {
            Finding first = entry.findings().getFirst();
            String thread = alreadyPosted.get(key);
            if (thread == null) {
                thread = review.inlineThreads().get(key);
            }
            if (thread == null && inlineFallback) {
                thread = postInlineQuietly(entry.anchor(), first);
            }
            if (thread == null) {
                failed.addAll(entry.findings());
                return;
            }
            if (!alreadyPosted.containsKey(key)) {
                idempotency.markPosted(command.reviewId(), command.commit(), key, thread);
            }
            inline.add(new CommentsPosted.PostedInline(thread, first.path(), first.range().startLine()));
        });
        return inline;
    }

    /** Per-finding isolation, as the legacy path: one failure never aborts the batch; the claim stays reclaimable. */
    private String postInlineQuietly(InlineAnchor anchor, Finding finding) {
        try {
            return inlinePoster.post(anchor, inlineBodies.apply(finding)).thread().value();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Inline post failed for %s at %s", command.reviewId(), anchor.anchorKey());
            return null;
        }
    }

    /** D11. A failure here must not fail the run: the Comment review is posted, and the next run tries again. */
    private void dismissQuietly(String reviewRef) {
        try {
            sink.dismissOwnChangesRequested(command.repo(), command.prId(),
                            "Superseded by review " + reviewRef + ": the reviewer may not change merge state on this repository")
                    .ifPresent(dismissed -> LOG.infof("review %s: dismissed the standing changes-requested review %s",
                            command.reviewId(), dismissed));
        } catch (RuntimeException e) {
            LOG.warnf(e, "review %s: could not dismiss the standing changes-requested review after posting %s",
                    command.reviewId(), reviewRef);
        }
    }
}
