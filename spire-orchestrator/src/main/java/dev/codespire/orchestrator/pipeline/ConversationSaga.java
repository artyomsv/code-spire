package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.ConversationPolicy;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Conversational-reply policy (spec §4): decides whether an {@code AuthorReplied} warrants a bot answer
 * and, if so, builds the credential-packed {@code AnswerFollowUp} command. The bot-self drop (ADR-013)
 * runs upstream in {@link IntegrationSaga}; this collaborator applies the per-provider author allowlist,
 * thread-ownership OR @-mention (scope A+B), the effective interaction level, and the per-thread turn cap.
 */
@ApplicationScoped
public class ConversationSaga {

    @Inject
    ProviderRegistry providers;

    @Inject
    ConversationLevels levels;

    @Inject
    ReviewThreadView threads;

    @Inject
    WorkerCredentials workerCredentials;

    @Inject
    WorkerLlmCredentials workerLlmCredentials;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    ReviewProjection projection;

    @Inject
    dev.codespire.orchestrator.prompt.WorkerPromptTemplates promptTemplates;

    /** The AnswerFollowUp to emit for a non-bot reply, or empty when policy says stay quiet. */
    public Optional<ActionCommand.AnswerFollowUp> planFollowUp(AuthorReplied e) {
        Optional<ScmProvider> providerOpt = providers.resolveByWorkspace(e.repo().workspace());
        if (providerOpt.isEmpty()) {
            return Optional.empty();
        }
        ScmProvider provider = providerOpt.get();
        if (botIdentityUnknown(provider, e)) {
            return Optional.empty();
        }

        Optional<ThreadTarget> targetOpt = resolveThread(e);
        if (targetOpt.isEmpty()) {
            return Optional.empty();
        }
        ThreadTarget target = targetOpt.get();
        boolean botMentioned = mentionsBot(e.text(), provider.botUsername());

        if (!decideToAnswer(e, provider, target, botMentioned)) {
            return Optional.empty();
        }

        Optional<String> llmCred = workerLlmCredentials.packDefault(e.repo().workspace());
        if (llmCred.isEmpty()) {
            timeline.record("integration", "skipped:AnswerFollowUp", e.reviewId(),
                    "no default LLM provider configured");
            return Optional.empty();
        }
        return Optional.of(new ActionCommand.AnswerFollowUp(
                e.reviewId(), e.repo(), e.prId(), target.thread(), e.commentId(), e.text(),
                workerCredentials.pack(provider), llmCred.get(), botMentioned,
                levels.maxAttempts(), levels.backoffBaseMs(), levels.backoffFactor(),
                promptTemplates.forKind(dev.codespire.contract.llm.PromptKind.FOLLOWUP)));
    }

    /** The self-loop guard can't recognize the bot's own comments without a resolved id — fail closed. */
    private boolean botIdentityUnknown(ScmProvider provider, AuthorReplied e) {
        if (provider.botAccountId() != null && !provider.botAccountId().isBlank()) {
            return false;
        }
        timeline.record("integration", "skipped:AnswerFollowUp", e.reviewId(),
                "bot identity unknown — re-save the provider to resolve it");
        return true;
    }

    /** The policy gate: level / allowlist / thread-ownership-or-mention / turn-cap (spec §4). Records
     *  the cap-reached timeline note itself, since it's the only branch that needs one. */
    private boolean decideToAnswer(AuthorReplied e, ScmProvider provider, ThreadTarget target, boolean botMentioned) {
        ConversationLevel level = levels.effectiveLevel(provider.type(), e.repo().workspace());
        boolean authorAllowed = allowlistAllows(provider.authors(), e.author());
        int priorTurns = threads.turnCount(e.reviewId(), target.thread());

        // botIsAuthor is already false here — IntegrationSaga drops bot-authored replies before calling.
        ConversationPolicy.ConversationDecision decision = ConversationPolicy.decide(
                level, authorAllowed, false, target.isOurs(), botMentioned, priorTurns, levels.turnCap());
        if (decision.capReached()) {
            timeline.record("integration", "conversation:cap", e.reviewId(),
                    "turn cap reached — deferring to the team");
        }
        return decision.answer();
    }

    /** Which SCM thread the answer threads onto, and whether the bot owns it. */
    private record ThreadTarget(ThreadRef thread, boolean isOurs) {
    }

    /**
     * A topLevel reply (a plain PR comment, no SCM thread of its own) routes to the review's
     * POSTED summary comment — treated as bot-owned (it IS the bot's own comment) — empty when
     * nothing has been posted yet (timeline-noted: nothing to converse on). An inline reply keeps
     * today's behavior: its own thread, with ownership looked up as before.
     */
    private Optional<ThreadTarget> resolveThread(AuthorReplied e) {
        if (!e.topLevel()) {
            return Optional.of(new ThreadTarget(e.threadRef(), threads.isOurThread(e.reviewId(), e.threadRef())));
        }
        Optional<String> summaryRef = projection.summaryRefOf(e.reviewId());
        if (summaryRef.isEmpty()) {
            timeline.record("integration", "skipped:AnswerFollowUp", e.reviewId(),
                    "top-level comment but no posted summary to converse on");
            return Optional.empty();
        }
        return Optional.of(new ThreadTarget(new ThreadRef(summaryRef.get()), true));
    }

    /**
     * Scope B: a human explicitly @-mentions the bot's login. Case-insensitive and word-bounded, so
     * {@code @code-spire} matches but {@code @code-spireworks} does not; blank login never matches.
     */
    static boolean mentionsBot(String text, String botUsername) {
        if (text == null || botUsername == null || botUsername.isBlank()) {
            return false;
        }
        return Pattern.compile("@" + Pattern.quote(botUsername) + "(?![A-Za-z0-9_-])",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    /** An empty allowlist answers everyone; else match by account id or username (mirrors the PR gate). */
    static boolean allowlistAllows(List<String> allowlist, Author author) {
        if (allowlist == null || allowlist.isEmpty()) {
            return true;
        }
        if (author == null) {
            return false;
        }
        return allowlist.stream().anyMatch(a ->
                a.equalsIgnoreCase(author.providerUserId()) || a.equalsIgnoreCase(author.username()));
    }
}
