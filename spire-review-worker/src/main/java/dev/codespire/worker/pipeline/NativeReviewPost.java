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
 * and supplies the rendering and the legacy inline poster it already has. The posting ladder itself is
 * {@link ReviewPostLadder}.
 */
final class NativeReviewPost {

    static final String REVIEW_KEY = "REVIEW";
    static final String REF_PREFIX = "review:";
    /** Between two findings that share one anchor and so one comment (M-1). */
    static final String FINDING_SEPARATOR = "\n\n---\n\n";
    private static final Logger LOG = Logger.getLogger(NativeReviewPost.class);

    /** Posts one inline comment the legacy way: the worker's backoff and throttle. */
    interface InlinePoster {
        CommentRef post(InlineAnchor anchor, String bodyMd);
    }

    /** Renders the summary body for the given unanchored and failed lists. */
    interface BodyRenderer {
        String render(List<Finding> unanchored, List<Finding> failed);
    }

    /** What the worker renders and posts for this path: the summary, one finding's body, the legacy inline post. */
    record Rendering(BodyRenderer summary, Function<Finding, String> inline, InlinePoster poster) {
    }

    /** The stores and the forge this path goes through. */
    record Ports(CommentIdempotencyStore idempotency, CommentSink sink, Rendering rendering) {
    }

    record Outcome(String reviewRef, ReviewVerdict postedVerdict, List<CommentsPosted.PostedInline> inline, String reviewUrl) {
    }

    /** The review the forge holds for this run, and whether its findings still have to go one by one (D7). */
    record Posted(PostedReview review, boolean inlineFallback) {
    }

    private record Anchored(InlineAnchor anchor, List<Finding> findings) {
    }

    /** Everything resolved and claimed before the first call to the forge. */
    private record Plan(Map<String, Anchored> anchored, Map<String, String> alreadyPosted,
                        List<Finding> unanchored, List<Finding> failed, List<InlineComment> comments) {
    }

    private final CommentIdempotencyStore idempotency;
    private final CommentSink sink;
    private final Rendering rendering;
    private final PostComments command;
    private final Diff diff;

    NativeReviewPost(Ports ports, PostComments command, Diff diff) {
        this.idempotency = ports.idempotency();
        this.sink = ports.sink();
        this.rendering = ports.rendering();
        this.command = command;
        this.diff = diff;
    }

    /** @return empty when the forge has no native review: the caller runs the legacy path. */
    Optional<Outcome> run() {
        Plan plan = plan();
        Posted posted = findOrPost(plan);
        if (posted == null) {
            return Optional.empty();
        }
        List<CommentsPosted.PostedInline> inline = settleInline(plan, posted);
        finish(plan, posted);
        // The verdict is what the forge accepted: read from it, or kept in the mark (M-4, M-2); unknown stays null.
        PostedReview review = posted.review();
        return Optional.of(new Outcome(review.reviewRef(), review.verdict() == null ? command.verdict() : review.verdict(), inline, review.reviewUrl()));
    }

    private Plan plan() {
        List<Finding> unanchored = new ArrayList<>();
        Map<String, Anchored> anchored = resolveAnchors(unanchored);
        Map<String, String> alreadyPosted = claimAnchors(anchored.keySet());
        return new Plan(anchored, alreadyPosted, unanchored, new ArrayList<>(), commentsFor(anchored, alreadyPosted));
    }

    /**
     * The claim does not say whether it was fresh or reclaimed, so look first: an attempt that died between
     * the POST and the mark left a review the forge still holds (spec §5.1 step 3). The mark is written at
     * once, before any read-back or fallback: everything after it is a read, a per-anchor claimed post or an
     * idempotent PUT, so a crash later re-enters here and finds the mark.
     *
     * @return null when the forge has no native review
     */
    private Posted findOrPost(Plan plan) {
        if (idempotency.claim(command.reviewId(), command.commit(), REVIEW_KEY)
                instanceof CommentIdempotencyStore.Claim.AlreadyPosted marked) {
            return marked(marked.postedRef(), plan);
        }
        Posted posted = find(plan).orElseGet(() -> ladder(plan).post(plan.comments()));
        if (posted != null) {
            idempotency.markPosted(command.reviewId(), command.commit(), REVIEW_KEY, ReviewMark.of(posted.review()));
        }
        return posted;
    }

    /** A redelivery after the mark: the forge's copy when it can be found, else what the mark kept ({@link ReviewMark}). */
    private Posted marked(String postedRef, Plan plan) {
        return find(plan).orElseGet(() -> {
            PostedReview held = ReviewMark.parse(postedRef);
            return new Posted(held, postedWithoutItsComments(held, plan.comments()));
        });
    }

    private Optional<Posted> find(Plan plan) {
        return sink.findReview(command.repo(), command.prId(), command.commit(), submission(command.verdict(), plan, plan.comments()))
                .map(review -> new Posted(review, postedWithoutItsComments(review, plan.comments())));
    }

    private ReviewPostLadder ladder(Plan plan) {
        return new ReviewPostLadder(sink, command, (posting, comments) -> submission(posting, plan, comments));
    }

    /** The body update the ladder owes (D7) or a failed inline post needs, then the hold release (D11). */
    private void finish(Plan plan, Posted posted) {
        String reviewRef = posted.review().reviewRef();
        if (posted.inlineFallback() || !plan.failed().isEmpty()) {
            // The verdict line belongs to the verdict that stands; an older mark without one gets the command's.
            ReviewVerdict standing = posted.review().verdict();
            sink.updateReviewBody(command.repo(), command.prId(), reviewRef, body(plan, standing == null ? command.verdict() : standing));
        }
        if (Boolean.FALSE.equals(command.reviewerMayApprove())) {
            dismissQuietly(reviewRef);
        }
    }

    /**
     * A review found on the forge that carries none of the comments this run would post was left by an
     * attempt that took the body-only ladder (D7) and died before finishing it: the findings still go
     * one by one, as that ladder would have posted them.
     */
    private static boolean postedWithoutItsComments(PostedReview review, List<InlineComment> comments) {
        return !comments.isEmpty() && review.inlineThreads().isEmpty();
    }

    private Map<String, Anchored> resolveAnchors(List<Finding> unanchored) {
        Map<String, Anchored> anchored = new LinkedHashMap<>();
        for (Finding finding : command.findings().findings()) {
            Optional<InlineAnchor> resolved = Anchors.resolveLine(
                    diff.files(), finding.path(), finding.range().startLine(), finding.range().endLine());
            if (resolved.isEmpty()) {
                unanchored.add(finding);
                continue;
            }
            InlineAnchor anchor = resolved.orElseThrow();
            anchored.computeIfAbsent(anchor.anchorKey(), key -> new Anchored(anchor, new ArrayList<>())).findings().add(finding);
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

    /**
     * One comment per anchor. The claim, the read-back and the thread record are all keyed by the anchor,
     * so a second comment at the same anchor would be posted and then owned by nobody (M-1); the findings
     * that share an anchor share its comment.
     */
    private List<InlineComment> commentsFor(Map<String, Anchored> anchored, Map<String, String> alreadyPosted) {
        List<InlineComment> comments = new ArrayList<>();
        anchored.forEach((key, entry) -> {
            if (!alreadyPosted.containsKey(key)) {
                comments.add(new InlineComment(entry.anchor(), inlineBody(entry)));
            }
        });
        return comments;
    }

    private String inlineBody(Anchored entry) {
        return String.join(FINDING_SEPARATOR, entry.findings().stream().map(rendering.inline()).toList());
    }

    private ReviewSubmission submission(ReviewVerdict posting, Plan plan, List<InlineComment> comments) {
        return new ReviewSubmission(posting, body(plan, posting), comments);
    }

    /** The verdict in words when the posted verdict is COMMENT and the assessment is not (D3, D6). */
    private String body(Plan plan, ReviewVerdict posting) {
        String rendered = withRunMarker(rendering.summary().render(plan.unanchored(), plan.failed()), command.runToken());
        return posting == ReviewVerdict.COMMENT && command.assessment() != ReviewVerdict.COMMENT
                ? withVerdictLine(rendered, command.assessment()) : rendered;
    }

    /**
     * The run's token on the header line, as an HTML comment the forge does not render. The look-first
     * ({@code findReview}) matches on that first line, so a redelivery of this run finds its own
     * half-posted review and a re-run on the same head — which clears the worker's claims — does not
     * reuse the earlier run's review (spire-test PR 55, 2026-10-16). No token: no marker (D10).
     */
    static String withRunMarker(String body, String runToken) {
        if (runToken == null || runToken.isBlank()) {
            return body;
        }
        int end = body.indexOf('\n');
        String marker = " <!-- code-spire-run:" + runToken + " -->";
        return end < 0 ? body + marker : body.substring(0, end) + marker + body.substring(end);
    }

    static String withVerdictLine(String body, ReviewVerdict assessment) {
        String line = "**Verdict:** " + (assessment == ReviewVerdict.APPROVE ? "approve" : "changes requested") + "\n\n";
        int cut = body.indexOf("\n\n");
        return cut < 0 ? body + "\n\n" + line : body.substring(0, cut + 2) + line + body.substring(cut + 2);
    }

    /** Every anchor's thread: already posted, inside the review, posted one by one on the fallback, or failed. */
    private List<CommentsPosted.PostedInline> settleInline(Plan plan, Posted posted) {
        List<CommentsPosted.PostedInline> inline = new ArrayList<>();
        plan.anchored().forEach((key, entry) -> {
            String thread = plan.alreadyPosted().get(key);
            if (thread == null) {
                thread = posted.review().inlineThreads().get(key);
            }
            if (thread == null && posted.inlineFallback()) {
                thread = postInlineQuietly(entry);
            }
            if (thread == null) {
                plan.failed().addAll(entry.findings());
                return;
            }
            if (!plan.alreadyPosted().containsKey(key)) {
                idempotency.markPosted(command.reviewId(), command.commit(), key, thread);
            }
            Finding first = entry.findings().getFirst();
            inline.add(new CommentsPosted.PostedInline(thread, first.path(), first.range().startLine()));
        });
        return inline;
    }

    /** Per-anchor isolation, as the legacy path: one failure never aborts the batch; the claim stays reclaimable. */
    private String postInlineQuietly(Anchored entry) {
        try {
            return rendering.poster().post(entry.anchor(), inlineBody(entry)).thread().value();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Inline post failed for %s at %s", command.reviewId(), entry.anchor().anchorKey());
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
