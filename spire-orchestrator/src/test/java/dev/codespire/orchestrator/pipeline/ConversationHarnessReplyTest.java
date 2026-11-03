package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ReplyRetry;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.TestRepositoryProjection;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.DefaultLlm;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.review.run.HarnessReplyStart;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness reviews, step 7: after every policy check, a reply on a harness repository is prepared for a reply run, never
 * answered by the API, and a refused one is said under its thread. A plain unit test: every collaborator the path reaches
 * is a fake, and the API-only ones throw, so reaching them fails the test (CLAUDE.md, saga test fakes).
 */
class ConversationHarnessReplyTest {

    private static final String REVIEW_ID = "review::TEST-WS/TEST-REPO#412";
    private static final ThreadRef SUMMARY = new ThreadRef("TEST-summary");
    private static final List<PriorFinding> FINDINGS = List.of(
            new PriorFinding("src/A.java", 3, Severity.MAJOR, "TEST-OTHER-FINDING", "TEST-other-thread"));
    private static final ActionCommand.PrepareHarnessReply PREPARED = new ActionCommand.PrepareHarnessReply(REVIEW_ID,
            new RepoRef("TEST-WS", "TEST-REPO"), 412, SUMMARY, "TEST-comment-555", "TEST-packed", false, 2, List.of(),
            new ReplyRetry(3, 100, 2.0), null);

    private HarnessReplyStart.Decision decision = new HarnessReplyStart.Preparing(PREPARED);
    private int priorTurns;
    private DefaultLlm apiLlm;
    private final List<HarnessReplyStart.ReplyAsk> asks = new ArrayList<>();
    private final List<String> timelineTypes = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    private static AuthorReplied reply() {
        return new AuthorReplied(new RepoRef("TEST-WS", "TEST-REPO"), 412L, REVIEW_ID, new ThreadRef("TEST-comment-555"),
                "TEST-comment-555", "TEST why?", Author.of("TEST-human", "TEST-alice", "TEST Alice"), true);
    }

    @Test
    void aReplyOnAHarnessRepositoryIsPreparedNotAnswered() {
        Optional<ActionCommand> command = saga().planFollowUp(reply());

        assertSame(PREPARED, command.orElseThrow(), "the harness start's command is what IntegrationSaga sends");
        HarnessReplyStart.ReplyAsk ask = asks.getFirst();
        assertEquals(SUMMARY, ask.thread(), "a top-level reply goes to the summary thread");
        assertEquals(FINDINGS, ask.otherFindings(), "the other threads' findings are off-limits for the reply");
        assertEquals("TEST-comment-555", ask.event().commentId());
    }

    /** A harness repository may have no API provider at all: the API credential is never resolved for it. */
    @Test
    void aHarnessRepositoryWithNoApiProviderStillAnswers() {
        apiLlm = DefaultLlm.noDefaultProvider();

        Optional<ActionCommand> command = saga().planFollowUp(reply());

        assertInstanceOf(ActionCommand.PrepareHarnessReply.class, command.orElseThrow());
        assertTrue(events.isEmpty(), "nothing refused: " + events);
    }

    @Test
    void aRefusedHarnessReplyIsSaidOutLoud() {
        decision = new HarnessReplyStart.Refused("TEST-harness", "fork_pull_request_not_reviewed_on_harness");

        Optional<ActionCommand> command = saga().planFollowUp(reply());

        assertTrue(command.isEmpty(), "never answered by the API instead");
        assertEquals(List.of("FollowUpRefused@TEST-summary: harness reply refused: TEST-harness: "
                + "fork_pull_request_not_reviewed_on_harness"), events);
        assertTrue(timelineTypes.contains("refused:AnswerFollowUp"), timelineTypes.toString());
    }

    @Test
    void theTurnCapStillAppliesOnAHarness() {
        priorTurns = 4;

        Optional<ActionCommand> command = saga().planFollowUp(reply());

        assertInstanceOf(ActionCommand.NotifyTurnCap.class, command.orElseThrow());
        assertTrue(asks.isEmpty(), "the cap is decided before where the answer comes from");
    }

    private ConversationSaga saga() {
        ConversationSaga saga = new ConversationSaga();
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return Optional.of(new ScmProvider(UUID.randomUUID(), "GH", "github", "https://forge.example.test",
                        "bearer", null, "TEST-secret", "TEST-acct", true, List.of(), "TEST-bot", "EXPLAIN",
                        ProviderRole.REVIEWER));
            }
        };
        saga.levels = levels();
        saga.threads = new ReviewThreadView() {
            @Override
            public int turnCount(String reviewId, ThreadRef thread) {
                return priorTurns;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public String pack(ScmProvider provider, String repositoryWorkspace) {
                return "TEST-packed";
            }
        };
        saga.workerLlmCredentials = apiCredentials();
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                timelineTypes.add(type);
            }
        };
        saga.projection = projection();
        saga.promptTemplates = new WorkerPromptTemplates() {
            @Override
            public dev.codespire.contract.llm.PromptTemplate forKind(dev.codespire.contract.llm.PromptKind kind,
                                                                     RepoRef repo) {
                throw new AssertionError("a harness reply composes its prompt at launch, not here");
            }
        };
        saga.spendGate = new SpendGate() {
            @Override
            public Decision decide() {
                throw new AssertionError("the run's funding applies the spend gate, not the conversation path");
            }
        };
        saga.harnessReplies = new HarnessReplyStart() {
            @Override
            public Decision start(ReplyAsk ask) {
                asks.add(ask);
                return decision;
            }
        };
        return saga;
    }

    /** Null for the harness cases: resolving the API credential at all is the defect under test. */
    private WorkerLlmCredentials apiCredentials() {
        return new WorkerLlmCredentials() {
            @Override
            public DefaultLlm resolveForReview(String reviewId) {
                if (apiLlm == null) {
                    throw new AssertionError("a harness reply never resolves the API credential");
                }
                return apiLlm;
            }
        };
    }

    private TestRepositoryProjection projection() {
        return new TestRepositoryProjection() {
            @Override
            public Optional<String> summaryRefOf(String reviewId) {
                return Optional.of(SUMMARY.value());
            }

            @Override
            public Optional<PriorRun> priorRunFor(String reviewId) {
                return Optional.of(new PriorRun("TEST-head", SUMMARY.value(), FINDINGS));
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
                events.add(type + "@" + threadRef + ": " + detail);
            }
        };
    }

    private static ConversationLevels levels() {
        return new ConversationLevels() {
            @Override
            public ConversationLevel effectiveLevel(ScmProvider provider) {
                return ConversationLevel.EXPLAIN;
            }

            @Override
            public int turnCap() {
                return 4;
            }
        };
    }
}
