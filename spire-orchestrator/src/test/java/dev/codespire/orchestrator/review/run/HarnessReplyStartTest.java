package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ReplyRetry;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Harness reviews, step 7: whether a reply is answered on its repository's harness, and the prepare command it gets. A
 * plain unit test: every collaborator the start reaches is a fake overriding every method it calls (CLAUDE.md, saga
 * test fakes).
 */
class HarnessReplyStartTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final long PR = 7L;
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, PR);
    private static final UUID REPOSITORY = UUID.fromString("00000000-0000-4000-8000-0000000000c7");
    private static final ThreadRef ROOT = new ThreadRef("TEST-root-thread");
    private static final List<PriorFinding> OTHERS = List.of(
            new PriorFinding("src/B.java", 9, Severity.MINOR, "TEST-OTHER-FINDING", "TEST-thread-2"));
    private static final AuthorReplied EVENT = new AuthorReplied(REPO, PR, REVIEW_ID, new ThreadRef("TEST-reply-id"),
            "TEST-comment-5", "TEST why?", Author.of("TEST-human", "TEST-alice", "TEST Alice"), false);

    private Optional<ReviewHarness> harness =
            Optional.of(new ReviewHarness("TEST-harness", "TEST-model", "high", "SUBSCRIPTION"));
    private Boolean fromFork = false;
    private int round = 3;
    private Optional<String> reviewerCredential = Optional.of("TEST-packed-scm");

    private HarnessReplyStart.Decision start() {
        return starter().start(new HarnessReplyStart.ReplyAsk(EVENT, ROOT, true, OTHERS));
    }

    @Test
    void aRepositoryWithoutAHarnessIsNotAHarnessReply() {
        harness = Optional.empty();

        assertInstanceOf(HarnessReplyStart.NotHarness.class, start());
    }

    @Test
    void aReplyIsPreparedWithItsThreadAndTurn() {
        HarnessReplyStart.Preparing preparing = assertInstanceOf(HarnessReplyStart.Preparing.class, start());

        ActionCommand.PrepareHarnessReply command = preparing.command();
        assertEquals(REVIEW_ID, command.reviewId());
        assertEquals(REPO, command.repo());
        assertEquals(PR, command.prId());
        assertEquals(ROOT, command.threadRef(), "the conversation root, not the reply's own id");
        assertEquals("TEST-comment-5", command.triggeringCommentId());
        assertEquals("TEST-packed-scm", command.scmCredential());
        assertEquals(true, command.mentioned());
        assertEquals(3, command.round());
        assertEquals(OTHERS, command.otherFindings());
        assertEquals(new ReplyRetry(4, 250, 3.0), command.retry(), "the conversation settings' retry");
    }

    @Test
    void aForkIsRefused() {
        fromFork = true;

        assertEquals(new HarnessReplyStart.Refused("TEST-harness", HarnessReviewStart.FORK_REFUSED), start());
    }

    @Test
    void anUnknownForkStatusIsRefused() {
        fromFork = null;

        assertEquals(new HarnessReplyStart.Refused("TEST-harness", HarnessReviewStart.FORK_REFUSED), start());
    }

    @Test
    void anUnreadableRoundIsRefused() {
        round = ReviewRuns.UNKNOWN_RUN;

        assertEquals(new HarnessReplyStart.Refused("TEST-harness", HarnessReviewStart.ROUND_UNKNOWN), start());
    }

    @Test
    void aMissingReviewerCredentialIsRefused() {
        reviewerCredential = Optional.empty();

        assertEquals(new HarnessReplyStart.Refused("TEST-harness", HarnessReviewStart.CREDENTIAL_UNAVAILABLE), start());
    }

    private HarnessReplyStart starter() {
        HarnessReplyStart start = new HarnessReplyStart();
        start.repositories = new RepositoryRegistry() {
            @Override
            public Optional<ReviewHarness> reviewHarness(UUID repositoryId) {
                return REPOSITORY.equals(repositoryId) ? harness : Optional.empty();
            }
        };
        start.projection = new ReviewProjection() {
            @Override
            public Optional<UUID> repositoryIdOf(String reviewId) {
                return Optional.of(REPOSITORY);
            }
        };
        start.targets = new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String reviewId) {
                return Optional.of(new PushTarget("github", REPO.workspace(), REPO.slug(), PR, "TEST-source",
                        "TEST-main", "a".repeat(40), "OPEN", fromFork));
            }
        };
        start.runs = new ReviewRuns() {
            @Override
            public int roundOrUnknown(String reviewId) {
                return round;
            }
        };
        start.credentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return reviewerCredential;
            }
        };
        start.levels = levels();
        return start;
    }

    /** The real one reads app_setting. */
    private static ConversationLevels levels() {
        return new ConversationLevels() {
            @Override
            public int maxAttempts() {
                return 4;
            }

            @Override
            public long backoffBaseMs() {
                return 250L;
            }

            @Override
            public double backoffFactor() {
                return 3.0;
            }
        };
    }
}
