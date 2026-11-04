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

import java.util.Map;

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

    /**
     * Where the prepare keeps the mention the post opens the answer with: the run's result names only the thread and
     * the turn, so who wrote the comment would otherwise be lost between the two. A {@code harness:} key, which
     * {@code PostComments}' recovery skips.
     */
    static String mentionKey(String triggeringCommentId) {
        return HarnessReviewWorker.CLAIM_PREFIX + "mention:" + triggeringCommentId;
    }

    /** Where the post records that both completion events went out, so a retry knows whether to send them again. */
    static String doneKey(String triggeringCommentId) {
        return HarnessReviewWorker.CLAIM_PREFIX + "reply-done:" + triggeringCommentId;
    }

    /** @param mention the forge's mention of the reply's author, or null when it has none: nothing is stored then */
    private void rememberMention(PrepareHarnessReply command, String mention) {
        if (mention == null) {
            return;
        }
        String thread = command.threadRef().value();
        String key = mentionKey(command.triggeringCommentId());
        idempotency.claim(command.reviewId(), thread, key);
        idempotency.markPosted(command.reviewId(), thread, key, mention);
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
        rememberMention(command, clients.comments().mention(command.replyAuthor()).orElse(null));
        // The run checks out the head, not the thread's anchor commit: a force-push can make that one unreachable.
        String head = clients.diff().fetchPullRequest(command.repo(), command.prId()).headCommit();
        results.emit(new HarnessReplyPrepared(command.reviewId(), command.prId(), head, command.threadRef(),
                command.triggeringCommentId(), command.round(),
                HarnessReviewInputFiles.reply(command.prId(), transcript, command.otherFindings())));
    }

    /**
     * Posted escaped once, whole, outside Markdown code, as every posted comment is (review of PR #371, rules/H-1);
     * code keeps its `<`. Null usage on {@code FollowUpGenerated}: {@code ReviewRunCharges} charged the run.
     *
     * <p>A comment already posted is never posted again, but its completion events are sent again until a done mark
     * says both went out: a publish that failed after the post would otherwise lose them for good, leaving the thread
     * uncounted and "answering" up (review of PR #371, code-quality/M-1). The orchestrator counts a turn once per
     * posted comment, so a resend counts nothing twice.
     */
    private void doPost(PostHarnessReply command) {
        String thread = command.threadRef().value();
        Map<String, String> posted = idempotency.postedFor(command.reviewId(), thread);
        if (posted.containsKey(doneKey(command.triggeringCommentId()))) {
            LOG.infof("Harness reply to comment %s on %s was already posted — staying quiet",
                    command.triggeringCommentId(), command.reviewId());
            return;
        }
        String text = ReplyMention.posted(posted.get(mentionKey(command.triggeringCommentId())),
                FollowUpAnswer.of(command.answerText()).text());
        String key = claimKey(command.triggeringCommentId());
        String commentId = idempotency.claim(command.reviewId(), thread, key)
                instanceof CommentIdempotencyStore.Claim.AlreadyPosted already
                ? already.postedRef() : postAndMark(command, key, text);
        results.emit(new FollowUpGenerated(command.reviewId(), command.threadRef(), text, null,
                command.triggeringCommentId()));
        results.emit(new FollowUpPosted(command.reviewId(), command.threadRef(), commentId, true));
        String done = doneKey(command.triggeringCommentId());
        idempotency.claim(command.reviewId(), thread, done);
        idempotency.markPosted(command.reviewId(), thread, done, commentId);
    }

    private String postAndMark(PostHarnessReply command, String key, String text) {
        CommentRef ref = scm.forCommand(command).comments().replyInThread(command.repo(), command.prId(),
                command.threadRef(), text);
        idempotency.markPosted(command.reviewId(), command.threadRef().value(), key, ref.commentId());
        return ref.commentId();
    }
}
