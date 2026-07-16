package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.AnswerFollowUp;
import dev.codespire.contract.event.IntegrationEvent.FollowUpGenerated;
import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.diff.DiffRenderer;
import dev.codespire.llm.FollowUpAnswer;
import dev.codespire.llm.FollowUpPrompt;
import dev.codespire.worker.adapters.WorkerLlmProvider;
import dev.codespire.worker.adapters.WorkerScmClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionException;

/**
 * Answers a reviewer's follow-up in a review thread (spec §4-§5): claim per-triggering-comment
 * idempotency, re-fetch the thread + its anchored diff (never persisted, ADR-011), one bounded LLM call,
 * then reply in the thread. GitHub-only in Plan 1 (the CommentSink must also be a ThreadSource).
 */
@ApplicationScoped
public class FollowUpWorker {

    private static final Logger LOG = Logger.getLogger(FollowUpWorker.class);

    @Inject
    WorkerScmClients scm;

    @Inject
    WorkerLlmProvider llm;

    @Inject
    CommentIdempotencyStore idempotency;

    @Inject
    ResultsEmitter results;

    /** The parsed answer + the id of the reply the bot posted. */
    public record FollowUpResult(String answerText, String postedCommentId) {
    }

    public void answer(AnswerFollowUp command) {
        try {
            WorkerScmClients.Clients clients = scm.forCommand(command);
            if (!(clients.comments() instanceof ThreadSource threadSource)) {
                LOG.debugf("No ThreadSource for %s — conversational replies need a GitHub provider (Plan 1)",
                        command.reviewId());
                return;
            }
            // Idempotency per triggering comment: threadRef is the "commit" slot (both known up front, no
            // fetch needed). A redelivered reply for the same comment never double-posts or double-pays.
            String key = "followup:" + command.triggeringCommentId();
            if (idempotency.claim(command.reviewId(), command.threadRef().value(), key)
                    instanceof CommentIdempotencyStore.Claim.AlreadyPosted) {
                LOG.debugf("Skipping already-answered %s for %s", key, command.reviewId());
                return;
            }
            WorkerLlmProvider.LlmClient client = llm.forCommand(command);
            FollowUpResult result = answer(command.repo(), command.prId(), command.threadRef(),
                    threadSource, clients.diff(), client.provider(), client.params(), clients.comments());
            idempotency.markPosted(command.reviewId(), command.threadRef().value(), key, result.postedCommentId());
            results.emit(new FollowUpGenerated(command.reviewId(), command.threadRef(), result.answerText()));
            results.emit(new FollowUpPosted(command.reviewId(), command.threadRef(), result.postedCommentId()));
        } catch (RuntimeException e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            // Plan 1: a follow-up failure isn't a review outcome; log and drop. At-least-once redelivery
            // retries (the NULL idempotency claim stays reclaimable).
            LOG.warnf(cause, "AnswerFollowUp failed for %s", command.reviewId());
        }
    }

    /**
     * The pure fetch→answer→reply core (no Kafka/DB) so it unit-tests with mocks. Re-fetches the thread and
     * its anchored diff, renders the injection-fenced follow-up prompt, calls the LLM, and posts the reply.
     */
    static FollowUpResult answer(RepoRef repo, long prId, ThreadRef thread, ThreadSource threads,
                                 DiffSource diffs, LlmProvider llmProvider, ModelParams params, CommentSink sink) {
        ThreadTranscript transcript = threads.fetchThread(repo, prId, thread);
        Diff diff = diffs.fetchDiff(repo, prId, transcript.commit());
        String diffText = DiffRenderer.render(diff.files());
        Prompt prompt = FollowUpPrompt.render(transcript, diffText);
        Completion completion = llmProvider.complete(prompt, params).toCompletableFuture().join();
        FollowUpAnswer parsed = FollowUpAnswer.of(completion.text());
        CommentRef ref = sink.replyInThread(repo, prId, thread, parsed.text());
        return new FollowUpResult(parsed.text(), ref.commentId());
    }
}
