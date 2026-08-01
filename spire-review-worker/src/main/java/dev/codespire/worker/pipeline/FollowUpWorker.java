package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.AnswerFollowUp;
import dev.codespire.contract.event.IntegrationEvent.FollowUpGenerated;
import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.event.IntegrationEvent.TurnCapNotified;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.FilePatch;
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
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Answers a reviewer's follow-up in a review thread (spec §4-§5): claim per-triggering-comment
 * idempotency, re-fetch the thread + its anchored diff (never persisted, ADR-011), one bounded LLM call,
 * then reply in the thread. Works for any provider whose CommentSink also implements ThreadSource
 * (GitHub, GitLab, Bitbucket).
 */
@ApplicationScoped
public class FollowUpWorker {

    private static final Logger LOG = Logger.getLogger(FollowUpWorker.class);

    /** One hand-off notice per thread — the slot is the thread, so the key is a constant. */
    private static final String CAP_NOTICE_KEY = "capnotice";

    @Inject
    WorkerScmClients scm;

    @Inject
    WorkerLlmProvider llm;

    @Inject
    PromptLog promptLog;

    @Inject
    CommentIdempotencyStore idempotency;

    @Inject
    ResultsEmitter results;

    /** The parsed answer, the id of the reply the bot posted, and the LLM call's usage (cost breakdown,
     * roadmap 11). */
    public record FollowUpResult(String answerText, String postedCommentId, ModelUsage usage) {
    }

    public void answer(AnswerFollowUp command) {
        int maxAttempts = Math.max(1, command.maxAttempts());
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
                    backoff(attempt, command.backoffBaseMs(), command.backoffFactor());
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
            LOG.debugf("No ThreadSource for %s — conversational replies unsupported for this provider",
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
                transcript, clients.diff(), promptLog.tap("follow-up", client.provider()),
                client.params(), clients.comments(),
                command.followUpPrompt(), command.otherFindings());
        idempotency.markPosted(command.reviewId(), command.threadRef().value(), key, result.postedCommentId());
        results.emit(new FollowUpGenerated(command.reviewId(), command.threadRef(), result.answerText(),
                result.usage()));
        results.emit(new FollowUpPosted(command.reviewId(), command.threadRef(), result.postedCommentId()));
    }

    /**
     * Post the turn-cap hand-off notice, once per thread (spec §8).
     *
     * <p>Fixed text, no LLM call: reaching the cap must not cost anything, and the wording should not
     * drift between threads. The idempotency slot is the thread itself rather than the triggering
     * comment, so every further reply that re-reaches the cap finds the slot taken and posts nothing —
     * one hand-off, not a repeated "I'm done" on each new comment.
     *
     * <p>The notice invites an @-mention because {@code ConversationPolicy} lets a mention override the
     * cap; if that policy ever changes, this text has to change with it.
     */
    public void notifyTurnCap(ActionCommand.NotifyTurnCap command) {
        WorkerScmClients.Clients clients = scm.forCommand(command);
        String thread = command.threadRef().value();
        if (idempotency.claim(command.reviewId(), thread, CAP_NOTICE_KEY)
                instanceof CommentIdempotencyStore.Claim.AlreadyPosted) {
            // INFO, not DEBUG: this is the only record that a reply on a capped thread went
            // unanswered ON PURPOSE. At DEBUG the logs showed the saga handing back and nothing
            // after it, so a repeat read as if a second notice had gone out. Bounded by human
            // replies, so it cannot get noisy.
            LOG.infof("Turn-cap notice already posted for %s thread %s — staying quiet",
                    command.reviewId(), thread);
            return;
        }
        CommentRef ref = clients.comments().replyInThread(command.repo(), command.prId(),
                command.threadRef(), capNoticeText(command.turnCap()));
        idempotency.markPosted(command.reviewId(), thread, CAP_NOTICE_KEY, ref.commentId());
        LOG.infof("Posted turn-cap notice for %s thread %s (cap %d)",
                command.reviewId(), thread, command.turnCap());
        results.emit(new TurnCapNotified(command.reviewId(), command.threadRef(), ref.commentId()));
    }

    static String capNoticeText(int turnCap) {
        return "I've replied " + turnCap + " times in this thread, so I'll hand it back to the team "
                + "rather than keep going. @-mention me if you still need something here.";
    }

    /**
     * The thread's own file, or the whole diff when the thread has no anchor.
     *
     * <p>An inline thread is a question about one file, and the persona says to answer only about the
     * anchored code — but the prompt used to carry every file in the PR, inviting exactly the survey
     * answers the persona forbids (and paying for the tokens). A summary thread genuinely is about the
     * whole PR, so it keeps the full diff. An anchor that matches nothing in the current diff also
     * falls back to everything rather than sending an empty diff: the file may have been renamed since
     * the finding was posted, and no diff at all would be worse than too much.
     */
    static List<FilePatch> anchoredFiles(Diff diff, String anchorPath) {
        if (anchorPath == null || anchorPath.isBlank()) {
            return diff.files();
        }
        List<FilePatch> matching = diff.files().stream()
                .filter(f -> anchorPath.equals(f.newPath()) || anchorPath.equals(f.oldPath()))
                .toList();
        return matching.isEmpty() ? diff.files() : matching;
    }

    /** {@code min(cap, base * factor^(attempt-1))} — the base/factor are the command's runtime-configured
     * retry policy (ADR: conversation settings), packed by the orchestrator from {@code app_setting}. */
    private static void backoff(int attempt, long backoffBaseMs, double backoffFactor) {
        long sleepMs = (long) Math.min(60_000L, backoffBaseMs * Math.pow(backoffFactor, attempt - 1));
        try {
            Thread.sleep(sleepMs);
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
     * this fetches the diff, narrows it to the thread's own file, renders the injection-fenced prompt,
     * calls the LLM, and posts the reply. A summary-thread transcript (a topLevel {@code AuthorReplied}'s
     * issue-comment fallback thread) carries no anchor commit, so the PR's current head is resolved first.
     */
    static FollowUpResult answer(RepoRef repo, long prId, ThreadRef thread, ThreadTranscript transcript,
                                 DiffSource diffs, LlmProvider llmProvider, ModelParams params, CommentSink sink,
                                 PromptTemplate followUpPrompt, List<PriorFinding> otherFindings) {
        String commit = transcript.commit() != null
                ? transcript.commit() : diffs.fetchPullRequest(repo, prId).headCommit();
        Diff diff = diffs.fetchDiff(repo, prId, commit);
        String diffText = DiffRenderer.render(anchoredFiles(diff, transcript.path()));
        Prompt prompt = FollowUpPrompt.render(transcript, diffText, otherFindings, followUpPrompt);
        Completion completion = llmProvider.complete(prompt, params).toCompletableFuture().join();
        FollowUpAnswer parsed = FollowUpAnswer.of(completion.text());
        // Post the answer as Markdown as-is. The SCM renders + sanitizes HTML in comments (no active markup
        // executes), and the injection defense is the prompt fence — NOT output escaping. Escaping "<" here
        // would corrupt code the answer includes, e.g. "if (n < 2)" inside a ``` block rendering as "n &lt; 2".
        CommentRef ref = sink.replyInThread(repo, prId, thread, parsed.text());
        return new FollowUpResult(parsed.text(), ref.commentId(), completion.usage());
    }
}
