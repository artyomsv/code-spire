package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.PostHarnessReply;
import dev.codespire.contract.command.ActionCommand.PrepareHarnessReply;
import dev.codespire.contract.event.IntegrationEvent.FollowUpGenerated;
import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.event.IntegrationEvent.HarnessReplyPrepared;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.llm.FollowUpAnswer;
import dev.codespire.llm.HarnessReviewInputFiles;
import dev.codespire.worker.adapters.WorkerScmClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The review worker's half of a reply on a harness (harness reviews, step 7). {@link #prepare} does what the API path
 * does before its paid call (fetch the thread, "smart 1:1", the {@code followup:<commentId>} claim) and renders the
 * thread for the run; {@link #post} posts the run's answer under the same claim. The model runs on the run-worker
 * (ADR-029), never here.
 *
 * <p>The claim taken at prepare reserves the slot without blocking a redelivery ({@code posted_ref} stays null until
 * the post): the orchestrator's launch stops a second run of one turn, and the post's claim stops a second comment.
 */
@ApplicationScoped
public class HarnessReplyWorker {

    private static final Logger LOG = Logger.getLogger(HarnessReplyWorker.class);

    @Inject
    WorkerScmClients scm;

    @Inject
    CommentIdempotencyStore idempotency;

    @Inject
    ResultsEmitter results;

    public void prepare(PrepareHarnessReply command) {
        FollowUpRetry.run(new FollowUpRetry.RetryCall(command.reviewId(), "PrepareHarnessReply", command.retry()),
                () -> doPrepare(command));
    }

    public void post(PostHarnessReply command) {
        FollowUpRetry.run(new FollowUpRetry.RetryCall(command.reviewId(), "PostHarnessReply", command.retry()),
                () -> doPost(command));
    }

    /** The API path's claim key, so a reply is never answered twice whichever path a repository switched to. */
    static String claimKey(String triggeringCommentId) {
        return "followup:" + triggeringCommentId;
    }

    private void doPrepare(PrepareHarnessReply command) {
        WorkerScmClients.Clients clients = scm.forCommand(command);
        if (!(clients.comments() instanceof ThreadSource threadSource)) {
            LOG.debugf("No ThreadSource for %s — conversational replies unsupported for this provider",
                    command.reviewId());
            return;
        }
        ThreadTranscript transcript = threadSource.fetchThread(command.repo(), command.prId(), command.threadRef());
        if (!FollowUpWorker.shouldAnswer(transcript, command.mentioned())) {
            LOG.debugf("Staying quiet on %s — multi-party thread with no @-mention", command.reviewId());
            return;
        }
        if (idempotency.claim(command.reviewId(), command.threadRef().value(),
                claimKey(command.triggeringCommentId())) instanceof CommentIdempotencyStore.Claim.AlreadyPosted) {
            LOG.debugf("Skipping already-answered comment %s for %s", command.triggeringCommentId(),
                    command.reviewId());
            return;
        }
        // The run checks out the head, not the thread's anchor commit: a force-push can make that one unreachable.
        String head = clients.diff().fetchPullRequest(command.repo(), command.prId()).headCommit();
        results.emit(new HarnessReplyPrepared(command.reviewId(), command.prId(), head, command.threadRef(),
                command.triggeringCommentId(), command.round(),
                HarnessReviewInputFiles.reply(command.prId(), transcript, command.otherFindings())));
    }

    /**
     * Posted as Markdown as-is, as the API path posts: the forge renders and sanitizes it, and escaping {@code <} would
     * corrupt code the answer quotes. Null usage on {@code FollowUpGenerated}: {@code ReviewRunCharges} charged the run.
     */
    private void doPost(PostHarnessReply command) {
        String key = claimKey(command.triggeringCommentId());
        String thread = command.threadRef().value();
        if (idempotency.claim(command.reviewId(), thread, key) instanceof CommentIdempotencyStore.Claim.AlreadyPosted) {
            LOG.infof("Harness reply to comment %s on %s was already posted — staying quiet",
                    command.triggeringCommentId(), command.reviewId());
            return;
        }
        String text = FollowUpAnswer.of(command.answerText()).text();
        CommentRef ref = scm.forCommand(command).comments().replyInThread(command.repo(), command.prId(),
                command.threadRef(), text);
        idempotency.markPosted(command.reviewId(), thread, key, ref.commentId());
        results.emit(new FollowUpGenerated(command.reviewId(), command.threadRef(), text, null,
                command.triggeringCommentId()));
        results.emit(new FollowUpPosted(command.reviewId(), command.threadRef(), ref.commentId()));
    }
}
