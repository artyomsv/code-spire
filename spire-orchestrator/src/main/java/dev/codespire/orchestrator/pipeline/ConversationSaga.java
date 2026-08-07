package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.llm.DefaultLlm;
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

    /**
     * The command to emit for a non-bot reply, or empty when policy says stay quiet: an
     * {@code AnswerFollowUp} normally, or a one-off {@code NotifyTurnCap} on the turn that first
     * exhausts the thread's budget — the cap is a hand-off, so somebody has to be told.
     */
    public Optional<ActionCommand> planFollowUp(AuthorReplied e) {
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

        // A thread the bot doesn't own, sitting on a line it flagged, still engages (item 15). Only
        // asked when needed: an owned thread or a mention is already eligible, so this read is skipped
        // for the common cases.
        boolean onFlaggedLine = !target.isOurs() && !botMentioned
                && e.location() != null
                && projection.hasOpenFindingAt(e.reviewId(), e.location().loc());

        ConversationPolicy.ConversationDecision decision =
                decide(e, provider, target, botMentioned, onFlaggedLine);
        if (decision.capReached()) {
            // Hand the thread back visibly. The worker keeps this to one notice per thread, so the
            // later replies that also land here post nothing.
            return Optional.of(new ActionCommand.NotifyTurnCap(
                    e.reviewId(), e.repo(), e.prId(), target.thread(), levels.turnCap(),
                    workerCredentials.pack(provider)));
        }
        if (!decision.answer()) {
            return Optional.empty();
        }

        // Resolving the default credential also answers whether the model can be priced — the same
        // pre-spend guard the review path applies, on a path that used to have none: an unpriceable
        // model refused new reviews while an author replying in a live thread still made the bot spend,
        // up to the turn cap or unbounded with an @-mention. ADR-023 argued this path was safe by
        // construction because the registry guard makes an unpriceable provider impossible; V30
        // falsifies that, creating rateless models in SQL without passing through the registry at all.
        DefaultLlm llm = workerLlmCredentials.resolveDefault(e.repo().workspace());
        if (!llm.isSpendable()) {
            skipUnspendable(e, llm);
            return Optional.empty();
        }
        LOG.infof("Answering reply on %s — thread %s, mentioned=%b", e.reviewId(), target.thread().value(), botMentioned);
        return Optional.of(new ActionCommand.AnswerFollowUp(
                e.reviewId(), e.repo(), e.prId(), target.thread(), e.commentId(), e.text(),
                workerCredentials.pack(provider), llm.packed(), botMentioned,
                levels.maxAttempts(), levels.backoffBaseMs(), levels.backoffFactor(),
                promptTemplates.forKind(dev.codespire.contract.llm.PromptKind.FOLLOWUP),
                findingsOwnedByOtherThreads(e.reviewId(), target.thread())));
    }

    /**
     * The review's findings that belong to threads OTHER than this one, so the reply prompt can rule
     * them out. Without it the model sees every defect in the file's diff with no way to know which
     * are already under discussion elsewhere, and answers a narrow question with a survey.
     *
     * <p>Reuses the ADR-019 posted-run snapshot — the same source the reconcile flow reads — so there
     * is one definition of "the findings that own threads". Degrades to empty on any read failure:
     * a missing exclusion list makes the reply broader, never wrong.
     */
    private List<PriorFinding> findingsOwnedByOtherThreads(String reviewId, ThreadRef thread) {
        return projection.priorRunFor(reviewId)
                .map(prior -> prior.findings().stream()
                        .filter(f -> f.threadRef() != null && !f.threadRef().equals(thread.value()))
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * A reply that will not be answered because the call cannot be paid for, said out loud.
     *
     * <p>Timeline note AND dashboard note, in the shape of the sibling skips: someone is waiting in that
     * thread, and this project has already been burned by the bot going quiet for a reason nobody could
     * see — the turn cap used to record a note and post nothing, indistinguishable from a lost webhook.
     * Posting a notice into the thread is deliberately NOT done here: unlike the turn cap, this is a
     * misconfiguration the operator fixes and the reply then happens on the next attempt.
     */
    private void skipUnspendable(AuthorReplied e, DefaultLlm llm) {
        timeline.record("integration", "skipped:AnswerFollowUp", e.reviewId(), llm.detail());
        projection.setNote(e.reviewId(), llm.note());
        LOG.infof("Follow-up skipped for %s — %s (workspace '%s')",
                e.reviewId(), llm.detail(), e.repo().workspace());
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

    /**
     * The policy gate: level / allowlist / thread-ownership-or-mention / turn-cap (spec §4). Records
     * the cap-reached timeline note itself, since it's the only branch that needs one.
     *
     * <p>The cap and a true decline log DIFFERENTLY on purpose. Both produce no answer, but one posts a
     * hand-off notice and the other stays silent — a single "declined" line for both makes it impossible
     * to tell from the log whether the thread was told anything, which is the exact ambiguity this
     * factor-logging exists to remove.
     */
    private ConversationPolicy.ConversationDecision decide(
            AuthorReplied e, ScmProvider provider, ThreadTarget target, boolean botMentioned,
            boolean onFlaggedLine) {
        ConversationLevel level = levels.effectiveLevel(provider.type(), e.repo().workspace());
        boolean authorAllowed = allowlistAllows(provider.authors(), e.author());
        int priorTurns = threads.turnCount(e.reviewId(), target.thread());

        // botIsAuthor is already false here — IntegrationSaga drops bot-authored replies before calling.
        ConversationPolicy.ConversationDecision decision = ConversationPolicy.decide(
                level, authorAllowed, false, target.isOurs(), botMentioned, onFlaggedLine,
                priorTurns, levels.turnCap());
        if (decision.capReached()) {
            timeline.record("integration", "conversation:cap", e.reviewId(),
                    "turn cap reached — handing back to the team");
            // "handing back", not "posting": whether a notice actually goes out is the worker's
            // call — it keeps one per thread — so claiming the post here would be a promise this
            // saga cannot keep, and on the second reply to a capped thread it would be false.
            LOG.infof("Turn cap reached on %s thread %s — handing back to the team "
                    + "(priorTurns=%d/%d); the notice posts once per thread, and an @-mention "
                    + "reopens it", e.reviewId(), target.thread().value(), priorTurns, levels.turnCap());
        } else if (!decision.answer()) {
            LOG.infof("Follow-up declined for %s — level=%s authorAllowed=%b threadIsOurs=%b mentioned=%b "
                    + "onFlaggedLine=%b priorTurns=%d/%d (no reply posted)",
                    e.reviewId(), level, authorAllowed, target.isOurs(), botMentioned, onFlaggedLine,
                    priorTurns, levels.turnCap());
        }
        return decision;
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
