package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.ConversationPolicy;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(ConversationSaga.class);

    @Inject
    ReviewProviderResolver reviewProviders;

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
        Optional<ScmProvider> providerOpt = reviewProviders.resolveForReview(e.reviewId());
        if (providerOpt.isEmpty()) {
            LOG.infof("Follow-up skipped for %s — no enabled provider for workspace '%s'",
                    e.reviewId(), e.repo().workspace());
            return Optional.empty();
        }
        ScmProvider provider = providerOpt.get();
        LOG.debugf("Follow-up on %s resolved provider %s/%s (comment %s, topLevel=%b)",
                e.reviewId(), provider.type(), provider.workspace(), e.commentId(), e.topLevel());
        if (botIdentityUnknown(provider, e)) {
            return Optional.empty();
        }

        Optional<ThreadTarget> targetOpt = resolveThread(e);
        if (targetOpt.isEmpty()) {
            return Optional.empty();
        }
        ThreadTarget target = targetOpt.get();
        boolean botMentioned = mentionsBot(e.mentions(), provider.botUsername(), provider.botAccountId());

        if (!decideToAnswer(e, provider, target, botMentioned)) {
            return Optional.empty();
        }

        Optional<String> llmCred = workerLlmCredentials.packDefault(e.repo().workspace());
        if (llmCred.isEmpty()) {
            timeline.record("integration", "skipped:AnswerFollowUp", e.reviewId(),
                    "no default LLM provider configured");
            LOG.infof("Follow-up skipped for %s — no default LLM provider configured for workspace '%s'",
                    e.reviewId(), e.repo().workspace());
            return Optional.empty();
        }
        LOG.infof("Answering reply on %s — thread %s, mentioned=%b", e.reviewId(), target.thread().value(), botMentioned);
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
        LOG.infof("Follow-up skipped for %s — bot identity unknown for provider %s/%s "
                + "(botAccountId blank; re-save the provider to resolve it)",
                e.reviewId(), provider.type(), provider.workspace());
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
        if (!decision.answer()) {
            LOG.infof("Follow-up declined for %s — level=%s authorAllowed=%b threadIsOurs=%b mentioned=%b "
                    + "priorTurns=%d/%d capReached=%b",
                    e.reviewId(), level, authorAllowed, target.isOurs(), botMentioned,
                    priorTurns, levels.turnCap(), decision.capReached());
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
            // Normalize to the conversation root: on Bitbucket a reply to the bot's own answer carries
            // that answer's comment id, so keying off it would split one conversation across refs (turn
            // counter never accumulating, turns stored under a non-finding ref). GitHub already sends
            // the root, for which rootOf is the identity.
            ThreadRef root = threads.rootOf(e.reviewId(), e.threadRef());
            return Optional.of(new ThreadTarget(root, threads.isOurThread(e.reviewId(), root)));
        }
        Optional<String> summaryRef = projection.summaryRefOf(e.reviewId());
        if (summaryRef.isEmpty()) {
            timeline.record("integration", "skipped:AnswerFollowUp", e.reviewId(),
                    "top-level comment but no posted summary to converse on");
            LOG.infof("Follow-up skipped for %s — top-level comment but no posted summary to converse on",
                    e.reviewId());
            return Optional.empty();
        }
        return Optional.of(new ThreadTarget(new ThreadRef(summaryRef.get()), true));
    }

    /**
     * Scope B: a human explicitly @-mentions the bot — a membership test over the identities the
     * ingress already extracted, so nothing here knows how any SCM renders a mention.
     *
     * <p>A username matches case-insensitively (logins are); an account id must match exactly, since
     * it is an opaque key rather than a name. A blank login or id never matches, so an unresolved bot
     * identity cannot make every comment look like a mention.
     */
    static boolean mentionsBot(List<String> mentions, String botUsername, String botAccountId) {
        if (mentions == null || mentions.isEmpty()) {
            return false;
        }
        boolean hasUsername = botUsername != null && !botUsername.isBlank();
        boolean hasAccountId = botAccountId != null && !botAccountId.isBlank();
        return mentions.stream().anyMatch(mentioned ->
                (hasUsername && mentioned.equalsIgnoreCase(botUsername))
                        || (hasAccountId && mentioned.equals(botAccountId)));
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
