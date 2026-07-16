package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.contract.scm.Author;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.ConversationPolicy;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

    /** Max bot replies per thread before deferring to the team (spec §8). Tuning knob; safe default. */
    @ConfigProperty(name = "spire.conversation.turn-cap", defaultValue = "4")
    int turnCap;

    /** The AnswerFollowUp to emit for a non-bot reply, or empty when policy says stay quiet. */
    public Optional<ActionCommand.AnswerFollowUp> planFollowUp(AuthorReplied e) {
        Optional<ScmProvider> providerOpt = providers.resolveByWorkspace(e.repo().workspace());
        if (providerOpt.isEmpty()) {
            return Optional.empty();
        }
        ScmProvider provider = providerOpt.get();

        ConversationLevel level = levels.effectiveLevel(provider.type(), e.repo().workspace());
        boolean authorAllowed = allowlistAllows(provider.authors(), e.author());
        boolean threadIsOurs = threads.isOurThread(e.reviewId(), e.threadRef());
        boolean botMentioned = mentionsBot(e.text(), provider.botUsername());
        int priorTurns = threads.turnCount(e.reviewId(), e.threadRef());

        // botIsAuthor is already false here — IntegrationSaga drops bot-authored replies before calling.
        ConversationPolicy.ConversationDecision decision = ConversationPolicy.decide(
                level, authorAllowed, false, threadIsOurs, botMentioned, priorTurns, turnCap);
        if (decision.capReached()) {
            timeline.record("integration", "conversation:cap", e.reviewId(),
                    "turn cap reached — deferring to the team");
            return Optional.empty();
        }
        if (!decision.answer()) {
            return Optional.empty();
        }

        Optional<String> llmCred = workerLlmCredentials.packDefault(e.repo().workspace());
        if (llmCred.isEmpty()) {
            timeline.record("integration", "skipped:AnswerFollowUp", e.reviewId(),
                    "no default LLM provider configured");
            return Optional.empty();
        }
        return Optional.of(new ActionCommand.AnswerFollowUp(
                e.reviewId(), e.repo(), e.prId(), e.threadRef(), e.commentId(), e.text(),
                workerCredentials.pack(provider), llmCred.get()));
    }

    /** Scope B: a human explicitly @-mentions the bot's login. Case-insensitive; blank login never matches. */
    static boolean mentionsBot(String text, String botUsername) {
        return text != null && botUsername != null && !botUsername.isBlank()
                && text.toLowerCase(Locale.ROOT).contains("@" + botUsername.toLowerCase(Locale.ROOT));
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
