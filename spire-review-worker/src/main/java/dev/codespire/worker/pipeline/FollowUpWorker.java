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
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.diff.DiffRenderer;
import dev.codespire.llm.FollowUpAnswer;
import dev.codespire.llm.FollowUpPrompt;
import dev.codespire.worker.adapters.WorkerLlmProvider;
import dev.codespire.worker.adapters.WorkerScmClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

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

    /** In-worker attempts on a transient SCM/LLM failure before the command is dead-lettered to cs.dlq. */
    @ConfigProperty(name = "spire.conversation.max-attempts", defaultValue = "3")
    int maxAttempts;

    /** The parsed answer + the id of the reply the bot posted. */
    public record FollowUpResult(String answerText, String postedCommentId) {
    }

    public void answer(AnswerFollowUp command) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                doAnswer(command);
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                Throwable cause = unwrap(e);
                if (attempt < maxAttempts && isTransient(cause)) {
                    LOG.warnf("Follow-up attempt %d/%d for %s failed transiently (%s) — retrying",
                            attempt, maxAttempts, command.reviewId(), cause.getMessage());
                    backoff(attempt);
                    continue;
                }
                break; // non-retryable, or attempts exhausted
            }
        }
        // Never silently lost (ADR-013): re-throw so the channel's dead-letter-queue strategy routes the
        // command to cs.dlq, where it can be inspected and replayed once the SCM/LLM recovers.
        LOG.warnf(unwrap(lastFailure), "AnswerFollowUp for %s failed after %d attempt(s) — routing to cs.dlq",
                command.reviewId(), maxAttempts);
        throw lastFailure;
    }

    private void doAnswer(AnswerFollowUp command) {
        WorkerScmClients.Clients clients = scm.forCommand(command);
        if (!(clients.comments() instanceof ThreadSource threadSource)) {
            LOG.debugf("No ThreadSource for %s — conversational replies need a GitHub provider (Plan 1)",
                    command.reviewId());
            return;
        }
        // Re-fetch the thread first (ADR-011): its participants decide whether to engage, BEFORE any paid
        // work. Scope "smart 1:1": auto-answer while it's the bot + one human; once a second human joins,
        // stay quiet unless the triggering comment @-mentioned the bot.
        ThreadTranscript transcript =
                threadSource.fetchThread(command.repo(), command.prId(), command.threadRef());
        if (!shouldAnswer(transcript, command.mentioned())) {
            LOG.debugf("Staying quiet on %s — multi-party thread with no @-mention", command.reviewId());
            return;
        }
        // Idempotency per triggering comment: threadRef is the "commit" slot. A redelivered reply for the
        // same comment never double-posts or double-pays the LLM.
        String key = "followup:" + command.triggeringCommentId();
        if (idempotency.claim(command.reviewId(), command.threadRef().value(), key)
                instanceof CommentIdempotencyStore.Claim.AlreadyPosted) {
            LOG.debugf("Skipping already-answered %s for %s", key, command.reviewId());
            return;
        }
        WorkerLlmProvider.LlmClient client = llm.forCommand(command);
        FollowUpResult result = answer(command.repo(), command.prId(), command.threadRef(),
                transcript, clients.diff(), client.provider(), client.params(), clients.comments());
        idempotency.markPosted(command.reviewId(), command.threadRef().value(), key, result.postedCommentId());
        results.emit(new FollowUpGenerated(command.reviewId(), command.threadRef(), result.answerText()));
        results.emit(new FollowUpPosted(command.reviewId(), command.threadRef(), result.postedCommentId()));
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(4_000L, 500L * (1L << (attempt - 1)))); // 500ms, 1s, 2s … capped at 4s
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Throwable unwrap(RuntimeException e) {
        return e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
    }

    /** Transient SCM (5xx / 429) or IO / timeout → worth a retry; anything else → dead-letter immediately. */
    static boolean isTransient(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ScmApiException api && (api.status() >= 500 || api.isRateLimited())) {
                return true;
            }
            if (t instanceof UncheckedIOException || t instanceof IOException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scope "smart 1:1" (spec §3): an explicit @-mention always engages; otherwise the bot only auto-answers
     * while the thread is a back-and-forth with a SINGLE human — the moment a second distinct human joins it
     * stays quiet (don't butt into a developer-to-developer discussion) until someone @-mentions it.
     */
    static boolean shouldAnswer(ThreadTranscript transcript, boolean mentioned) {
        if (mentioned) {
            return true;
        }
        long distinctHumans = transcript.messages().stream()
                .filter(m -> !m.fromBot())
                .map(ThreadMessage::author)
                .distinct()
                .count();
        return distinctHumans <= 1;
    }

    /**
     * The pure answer→reply core (no Kafka/DB) so it unit-tests with mocks. The thread is already fetched;
     * this fetches the anchored diff, renders the injection-fenced prompt, calls the LLM, and posts the reply.
     */
    static FollowUpResult answer(RepoRef repo, long prId, ThreadRef thread, ThreadTranscript transcript,
                                 DiffSource diffs, LlmProvider llmProvider, ModelParams params, CommentSink sink) {
        Diff diff = diffs.fetchDiff(repo, prId, transcript.commit());
        String diffText = DiffRenderer.render(diff.files());
        Prompt prompt = FollowUpPrompt.render(transcript, diffText);
        Completion completion = llmProvider.complete(prompt, params).toCompletableFuture().join();
        FollowUpAnswer parsed = FollowUpAnswer.of(completion.text());
        // Post the answer as Markdown as-is. The SCM renders + sanitizes HTML in comments (no active markup
        // executes), and the injection defense is the prompt fence — NOT output escaping. Escaping "<" here
        // would corrupt code the answer includes, e.g. "if (n < 2)" inside a ``` block rendering as "n &lt; 2".
        CommentRef ref = sink.replyInThread(repo, prId, thread, parsed.text());
        return new FollowUpResult(parsed.text(), ref.commentId());
    }
}
