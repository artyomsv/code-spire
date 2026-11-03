package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand.PostHarnessReply;
import dev.codespire.contract.command.ReplyRetry;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * A reply run's answer is posted, or its failure is said (harness reviews, step 7). An answer goes to the review worker
 * as a post-only {@code PostHarnessReply}; a run that ends with no answer to post (failed, cancelled, over the cap, no
 * reviewer account) writes {@code FollowUpFailed} under the thread and clears "answering", so the person waiting sees
 * why. No round check: a reply answers a thread, not a round.
 *
 * <p>Never charged here: {@code ReviewRunCharges} charged the run under the reply's turn. The answer's text is never
 * logged: it is model output about the reviewed code and may quote it (SECURITY.md).
 */
@ApplicationScoped
public class HarnessReplyResults {

    private static final Logger LOG = Logger.getLogger(HarnessReplyResults.class);

    static final String OVER_CAP = "The harness answer was larger than " + RunReviewArtifact.MAX_TEXT_BYTES
            + " bytes and was not posted";

    static final String CANCELLED = "Harness reply run cancelled";

    static final String CREDENTIAL_UNAVAILABLE = "The reviewer's forge account is unavailable, so the harness answer"
            + " could not be posted";

    @Inject
    WorkerCredentials credentials;

    /** The conversation settings' retry for the post, as the API reply carries it. */
    @Inject
    ConversationLevels levels;

    @Inject
    CommandsEmitter commands;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    ReviewProjection projection;

    public void accept(RunResult result, ReviewRunBinding binding) {
        ReplyCall call = ReplyCall.ofSlot(binding.callSlot());
        switch (result) {
            case RunReviewArtifact answer -> post(answer, binding, call);
            case RunResult.RunFailed failed -> fail(binding, call, HarnessReviewResults.CANCELLED.equals(failed.cause())
                    ? CANCELLED : "Harness reply run failed: " + failed.cause());
            default -> LOG.debugf("reply run %s for %s: %s", result.runId(), binding.reviewId(),
                    result.getClass().getSimpleName());
        }
    }

    /** Within the cap the contract guarantees a non-blank text (RunReviewArtifact), so every post has words to post. */
    private void post(RunReviewArtifact answer, ReviewRunBinding binding, ReplyCall call) {
        if (false) {
            fail(binding, call, OVER_CAP);
            return;
        }
        Optional<String> credential = credentials.packForReview(binding.reviewId());
        if (credential.isEmpty()) {
            fail(binding, call, CREDENTIAL_UNAVAILABLE);
            return;
        }
        commands.emit(new PostHarnessReply(binding.reviewId(), ReviewIds.parse(binding.reviewId()).repo(),
                binding.prId(), new ThreadRef(call.threadRef()), call.triggeringCommentId(), answer.text(),
                credential.orElseThrow(),
                new ReplyRetry(levels.maxAttempts(), levels.backoffBaseMs(), levels.backoffFactor())));
    }

    private void fail(ReviewRunBinding binding, ReplyCall call, String why) {
        timeline.record("result", "failed:HarnessReply", binding.reviewId(), why);
        projection.appendEvent(binding.reviewId(), "result", "FollowUpFailed", why, call.threadRef());
        projection.setAnswering(binding.reviewId(), false);
        LOG.warnf("Harness reply on %s thread %s got no answer: %s", binding.reviewId(), call.threadRef(), why);
    }
}
