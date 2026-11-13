package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand.PrepareHarnessReply;
import dev.codespire.contract.command.ReplyRetry;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Whether a reply that policy says to answer is answered on its repository's harness (harness reviews, step 7), and if
 * so the {@code PrepareHarnessReply} for the review worker. {@code ConversationSaga} has already applied every policy
 * check (allowlist, ownership or mention, level, turn cap); this decides only where the answer comes from.
 *
 * <p>Decides in this order: no repository or no review harness is {@link NotHarness}; an untrusted pull request with no pull ref (Bitbucket),
 * is {@link Refused}; a round that cannot be read is refused; no packed reviewer credential is refused; otherwise
 * {@link Preparing}. A refusal never falls back to the API (operator decision 6). The command is returned, not sent:
 * {@code IntegrationSaga} sends it as it sends {@code AnswerFollowUp}.
 */
@ApplicationScoped
public class HarnessReplyStart {

    @Inject
    RepositoryRegistry repositories;

    @Inject
    ReviewProjection projection;

    @Inject
    FixTargets targets;

    @Inject
    ReviewRuns runs;

    @Inject
    WorkerCredentials credentials;

    /** The conversation settings' retry, the same the API reply carries. */
    @Inject
    ConversationLevels levels;

    /** The reply to answer: the event, its conversation root, whether it mentioned the bot, and the off-limits list. */
    public record ReplyAsk(AuthorReplied event, ThreadRef thread, boolean mentioned, List<PriorFinding> otherFindings) {
    }

    /** Where a reply's answer comes from. */
    public sealed interface Decision permits NotHarness, Preparing, Refused {
    }

    /** The repository reviews with an API provider: the reply takes the API path, as before. */
    public record NotHarness() implements Decision {
    }

    /** Send this to the worker; the run is launched once it has prepared the thread. */
    public record Preparing(PrepareHarnessReply command) implements Decision {
    }

    /** The reply is refused on its harness, never answered by the API instead. */
    public record Refused(String harness, String reason) implements Decision {

        /** What the thread's history shows: the harness and the reason. */
        public String detail() {
            return "harness reply refused: " + harness + ": " + reason;
        }
    }

    public Decision start(ReplyAsk ask) {
        String reviewId = ask.event().reviewId();
        Optional<String> harness = projection.repositoryIdOf(reviewId).flatMap(id -> repositories.reviewHarness(id))
                .map(ReviewHarness::harness);
        if (harness.isEmpty()) {
            return new NotHarness();
        }
        // An untrusted pull request with no pull ref is refused, as a review is (step 8).
        Optional<FixTargets.PushTarget> target = targets.forReview(reviewId);
        if (HarnessReviewStart.refusedForNoPullRef(target)) {
            return new Refused(harness.orElseThrow(), HarnessReviewStart.FORK_REFUSED);
        }
        int round = runs.roundOrUnknown(reviewId);
        if (round == ReviewRuns.UNKNOWN_RUN) {
            return new Refused(harness.orElseThrow(), HarnessReviewStart.ROUND_UNKNOWN);
        }
        Optional<String> credential = credentials.packForReview(reviewId);
        if (credential.isEmpty()) {
            return new Refused(harness.orElseThrow(), HarnessReviewStart.CREDENTIAL_UNAVAILABLE);
        }
        return new Preparing(command(ask, credential.orElseThrow(), round));
    }

    private PrepareHarnessReply command(ReplyAsk ask, String credential, int round) {
        AuthorReplied event = ask.event();
        return new PrepareHarnessReply(event.reviewId(), event.repo(), event.prId(), ask.thread(), event.commentId(),
                credential, ask.mentioned(), round, ask.otherFindings(),
                new ReplyRetry(levels.maxAttempts(), levels.backoffBaseMs(), levels.backoffFactor()),
                replyAuthor(event.author()));
    }

    /**
     * The person a reply answers, as the worker needs them for the forge's mention: the stable id, the login and the
     * display name, never the email, which is never put on a command (CLAUDE.md: email is never logged or persisted).
     */
    public static Author replyAuthor(Author author) {
        return author == null ? null : Author.of(author.providerUserId(), author.username(), author.displayName());
    }
}
