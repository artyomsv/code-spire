package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic coverage for the scope-B (@-mention) and allowlist gates, plus the topLevel
 * (summary-thread) routing in {@link ConversationSaga#planFollowUp}. Collaborators are
 * field-injected, so hand-written fakes are set directly — no CDI container, no mocking
 * framework (mirrors {@link IntegrationSagaPolicyTest}). Full choreography beyond routing is
 * covered by the deferred @QuarkusTest E2E. */
class ConversationSagaTest {

    // The mention SYNTAX is each ingress's business now (its own tests cover extraction). What the
    // saga must get right is the comparison: this is a membership test over already-parsed
    // identities, so no provider's rendering appears here.

    @Test
    void aMentionedUsernameMatchesRegardlessOfCase() {
        assertTrue(ConversationSaga.mentionsBot(List.of("code-spire"), "code-spire", null));
        assertTrue(ConversationSaga.mentionsBot(List.of("Code-Spire"), "code-spire", null));
        assertTrue(ConversationSaga.mentionsBot(List.of("someone", "code-spire"), "code-spire", null),
                "the bot counts as mentioned even when it is not the only one");
        assertFalse(ConversationSaga.mentionsBot(List.of("code-spireworks"), "code-spire", null),
                "a longer name that merely starts with the bot's is a different account");
    }

    @Test
    void anAccountIdMustMatchExactlyBecauseItIsAnOpaqueKey() {
        String botId = "TEST-account-0001";
        assertTrue(ConversationSaga.mentionsBot(List.of(botId), null, botId));
        assertTrue(ConversationSaga.mentionsBot(List.of(botId), "code-spire-bot", botId));
        assertFalse(ConversationSaga.mentionsBot(List.of("TEST-account-0002"), "code-spire-bot", botId));
    }

    @Test
    void noMentionsOrAnUnresolvedBotIdentityNeverMatches() {
        assertFalse(ConversationSaga.mentionsBot(List.of(), "code-spire", null));
        assertFalse(ConversationSaga.mentionsBot(null, "code-spire", null));
        // A blank identity must not turn every mention into a hit.
        assertFalse(ConversationSaga.mentionsBot(List.of("someone"), "", null));
        assertFalse(ConversationSaga.mentionsBot(List.of("someone"), null, null));
        assertFalse(ConversationSaga.mentionsBot(List.of("TEST-account-0001"), "code-spire-bot", ""));
    }

    @Test
    void emptyAllowlistAllowsEveryone() {
        assertTrue(ConversationSaga.allowlistAllows(List.of(), Author.of("42", "octocat", "Octo Cat")));
        assertTrue(ConversationSaga.allowlistAllows(null, Author.of("42", "octocat", "Octo Cat")));
    }

    @Test
    void nonEmptyAllowlistMatchesByIdOrUsername() {
        assertTrue(ConversationSaga.allowlistAllows(List.of("octocat"), Author.of("42", "octocat", "Octo Cat")));
        assertTrue(ConversationSaga.allowlistAllows(List.of("42"), Author.of("42", "octocat", "Octo Cat")));
        assertFalse(ConversationSaga.allowlistAllows(List.of("someone-else"), Author.of("42", "octocat", "Octo Cat")));
        assertFalse(ConversationSaga.allowlistAllows(List.of("octocat"), null));
    }

    // --- topLevel (summary-thread) routing ---

    private static AuthorReplied topLevelReply(String reviewId) {
        return new AuthorReplied(new RepoRef("acme", "web"), 412L, reviewId,
                new ThreadRef("555"), "555", "looks wrong to me",
                Author.of("human-1", "alice", "Alice"), true);
    }

    private static ScmProvider githubProvider() {
        return new ScmProvider(UUID.randomUUID(), "GH", "github", "https://x", "acme",
                "bearer", null, "secret", "acct", true, List.of(), "code-spire", "EXPLAIN");
    }

    private static ConversationLevels fixedLevel(ConversationLevel level) {
        return new ConversationLevels() {
            @Override
            public ConversationLevel effectiveLevel(String type, String workspace) {
                return level;
            }

            @Override
            public int turnCap() {
                return 4;
            }

            @Override
            public int maxAttempts() {
                return 3;
            }

            @Override
            public long backoffBaseMs() {
                return 100L;
            }

            @Override
            public double backoffFactor() {
                return 2.0;
            }
        };
    }

    @Test
    void topLevelReplyRoutesToThePostedSummaryThread() {
        List<String> notes = new ArrayList<>();
        ConversationSaga saga = new ConversationSaga();
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return Optional.of(githubProvider());
            }
        };
        saga.levels = fixedLevel(ConversationLevel.EXPLAIN);
        saga.threads = new ReviewThreadView() {
            @Override
            public int turnCount(String reviewId, ThreadRef thread) {
                assertEquals("sum-1", thread.value(), "turn count must key off the SUMMARY thread, not the comment");
                return 0;
            }

            @Override
            public boolean isOurThread(String reviewId, ThreadRef thread) {
                throw new AssertionError("topLevel routing must not consult inline thread ownership");
            }

            @Override
            public ThreadRef rootOf(String reviewId, ThreadRef thread) {
                throw new AssertionError("topLevel routing goes to the summary thread, not a root lookup");
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public String pack(ScmProvider p) {
                return "packed:" + p.workspace();
            }
        };
        saga.workerLlmCredentials = new WorkerLlmCredentials() {
            @Override
            public Optional<String> packDefault(String workspace) {
                return Optional.of("llm-cred");
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                notes.add(type);
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public Optional<String> summaryRefOf(String reviewId) {
                return Optional.of("sum-1");
            }
        };
        saga.promptTemplates = new WorkerPromptTemplates() {
            @Override
            public dev.codespire.contract.llm.PromptTemplate forKind(dev.codespire.contract.llm.PromptKind kind) {
                return null;
            }
        };

        Optional<ActionCommand> cmd = saga.planFollowUp(topLevelReply("review::acme/web#412"));

        assertTrue(cmd.isPresent(), "a topLevel reply with a posted summary is answered");
        ActionCommand.AnswerFollowUp answer =
                assertInstanceOf(ActionCommand.AnswerFollowUp.class, cmd.get());
        assertEquals("sum-1", answer.threadRef().value(), "the command threads onto the SUMMARY comment");
        assertEquals("555", answer.triggeringCommentId(), "the triggering id stays the author's own comment");
        assertFalse(notes.contains("skipped:AnswerFollowUp"));
    }

    /**
     * Bitbucket threads by immediate parent, so a reply to the bot's own answer arrives with THAT
     * answer's comment id as the threadRef. The saga must normalize it to the conversation root, so the
     * command, the turn count and the stored event all key off one id per conversation.
     */
    @Test
    void aReplyOnTheBotsAnswerIsNormalizedToTheConversationRoot() {
        ConversationSaga saga = new ConversationSaga();
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return Optional.of(githubProvider());
            }
        };
        saga.levels = fixedLevel(ConversationLevel.INTERACTIVE);
        List<String> turnCountedOn = new ArrayList<>();
        saga.threads = new ReviewThreadView() {
            @Override
            public ThreadRef rootOf(String reviewId, ThreadRef thread) {
                return "answer-1".equals(thread.value()) ? new ThreadRef("finding-1") : thread;
            }

            @Override
            public boolean isOurThread(String reviewId, ThreadRef thread) {
                assertEquals("finding-1", thread.value(), "ownership is checked on the root");
                return true;
            }

            @Override
            public int turnCount(String reviewId, ThreadRef thread) {
                turnCountedOn.add(thread.value());
                return 0;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public String pack(ScmProvider p) {
                return "packed";
            }
        };
        saga.workerLlmCredentials = new WorkerLlmCredentials() {
            @Override
            public Optional<String> packDefault(String workspace) {
                return Optional.of("llm-cred");
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.promptTemplates = new WorkerPromptTemplates() {
            @Override
            public dev.codespire.contract.llm.PromptTemplate forKind(dev.codespire.contract.llm.PromptKind kind) {
                return null;
            }
        };

        AuthorReplied replyOnAnswer = new AuthorReplied(new RepoRef("acme", "web"), 412L,
                "review::acme/web#412", new ThreadRef("answer-1"), "c-99", "and the other errors?",
                Author.of("human-1", "alice", "Alice"), false);

        Optional<ActionCommand> cmd = saga.planFollowUp(replyOnAnswer);

        assertTrue(cmd.isPresent(), "a reply on the bot's own answer keeps the conversation going");
        ActionCommand.AnswerFollowUp answer =
                assertInstanceOf(ActionCommand.AnswerFollowUp.class, cmd.get());
        assertEquals("finding-1", answer.threadRef().value(),
                "the command targets the conversation root, not the answer comment it replied to");
        assertEquals("c-99", answer.triggeringCommentId(), "the triggering id stays the human's comment");
        assertEquals(List.of("finding-1"), turnCountedOn, "the turn cap counts on the root");
    }

    // --- turn cap: a hand-off has to be visible to the person waiting in the thread ---

    /**
     * Builds a saga whose owned thread "finding-1" already has {@code priorTurns} turns. The LLM
     * credential fake throws: a cap notice is fixed text, so needing one would mean the notice path
     * had drifted into the paid answer path.
     */
    private static ConversationSaga cappedThreadSaga(int priorTurns, List<String> notes) {
        ConversationSaga saga = new ConversationSaga();
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return Optional.of(githubProvider());
            }
        };
        saga.levels = fixedLevel(ConversationLevel.INTERACTIVE);
        saga.threads = new ReviewThreadView() {
            @Override
            public ThreadRef rootOf(String reviewId, ThreadRef thread) {
                return new ThreadRef("finding-1");
            }

            @Override
            public boolean isOurThread(String reviewId, ThreadRef thread) {
                return true;
            }

            @Override
            public int turnCount(String reviewId, ThreadRef thread) {
                return priorTurns;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public String pack(ScmProvider p) {
                return "packed:" + p.workspace();
            }
        };
        saga.workerLlmCredentials = new WorkerLlmCredentials() {
            @Override
            public Optional<String> packDefault(String workspace) {
                throw new AssertionError("the cap notice is fixed text — it must not need an LLM credential");
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                notes.add(type);
            }
        };
        saga.promptTemplates = new WorkerPromptTemplates() {
            @Override
            public dev.codespire.contract.llm.PromptTemplate forKind(dev.codespire.contract.llm.PromptKind kind) {
                return null;
            }
        };
        return saga;
    }

    private static AuthorReplied inlineReply(List<String> mentions) {
        return new AuthorReplied(new RepoRef("acme", "web"), 412L, "review::acme/web#412",
                new ThreadRef("finding-1"), "c-99", "and this one?",
                Author.of("human-1", "alice", "Alice"), false, mentions);
    }

    /**
     * The cap used to produce a dashboard note and nothing else, so the bot just went quiet — which
     * from inside the thread is indistinguishable from a lost webhook or a crash. It must hand off out
     * loud.
     */
    @Test
    void aCappedThreadPlansTheHandOffNoticeInsteadOfAnAnswer() {
        List<String> notes = new ArrayList<>();
        // fixedLevel caps at 4; four turns are already spent.
        Optional<ActionCommand> cmd = cappedThreadSaga(4, notes).planFollowUp(inlineReply(List.of()));

        assertTrue(cmd.isPresent(), "reaching the cap must still emit something — silence is not a hand-off");
        ActionCommand.NotifyTurnCap notice =
                assertInstanceOf(ActionCommand.NotifyTurnCap.class, cmd.get());
        assertEquals("finding-1", notice.threadRef().value(), "the notice posts on the conversation root");
        assertEquals(4, notice.turnCap(), "the notice states the cap it actually hit");
        assertEquals("packed:acme", notice.scmCredential(), "the worker needs the SCM credential to post");
        assertNull(notice.llmCredential(), "no LLM call — the notice is fixed text");
        assertTrue(notes.contains("conversation:cap"), "the dashboard note stays alongside the reply");
    }

    /** An @-mention past the cap is a person asking directly, so it gets a real answer, not the notice. */
    @Test
    void aMentionPastTheCapPlansARealAnswerNotTheNotice() {
        ConversationSaga saga = cappedThreadSaga(4, new ArrayList<>());
        // The mention override takes the paid path, so this saga needs a working LLM credential.
        saga.workerLlmCredentials = new WorkerLlmCredentials() {
            @Override
            public Optional<String> packDefault(String workspace) {
                return Optional.of("llm-cred");
            }
        };

        Optional<ActionCommand> cmd = saga.planFollowUp(inlineReply(List.of("code-spire")));

        ActionCommand.AnswerFollowUp answer =
                assertInstanceOf(ActionCommand.AnswerFollowUp.class, cmd.orElseThrow(),
                        "a direct mention past the cap is answered, not handed off again");
        assertTrue(answer.mentioned(), "the command records that a mention is what let this through");
    }

    @Test
    void blankBotAccountIdFailsClosedBeforeAnyThreadResolution() {
        List<String> notes = new ArrayList<>();
        List<String> details = new ArrayList<>();
        ConversationSaga saga = new ConversationSaga();
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return Optional.of(new ScmProvider(UUID.randomUUID(), "GH", "github", "https://x", "acme",
                        "bearer", null, "secret", "", true, List.of(), "code-spire", "EXPLAIN"));
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                notes.add(type);
                details.add(detail);
            }
        };
        saga.threads = new ReviewThreadView() {
            @Override
            public int turnCount(String reviewId, ThreadRef thread) {
                throw new AssertionError("blank bot identity must short-circuit before thread resolution");
            }

            @Override
            public boolean isOurThread(String reviewId, ThreadRef thread) {
                throw new AssertionError("blank bot identity must short-circuit before thread resolution");
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public Optional<String> summaryRefOf(String reviewId) {
                throw new AssertionError("blank bot identity must short-circuit before summary lookup");
            }
        };

        Optional<ActionCommand> cmd = saga.planFollowUp(topLevelReply("review::acme/web#412"));

        assertTrue(cmd.isEmpty(), "blank botAccountId must fail closed with no command");
        assertTrue(notes.contains("skipped:AnswerFollowUp"));
        assertTrue(details.contains("bot identity unknown — re-save the provider to resolve it"));
    }

    @Test
    void topLevelReplyWithNoPostedSummaryIsSkippedWithATimelineNote() {
        List<String> notes = new ArrayList<>();
        ConversationSaga saga = new ConversationSaga();
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return Optional.of(githubProvider());
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                notes.add(type);
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public Optional<String> summaryRefOf(String reviewId) {
                return Optional.empty();
            }
        };

        Optional<ActionCommand> cmd = saga.planFollowUp(topLevelReply("review::acme/web#412"));

        assertTrue(cmd.isEmpty(), "no posted summary to converse on -> no command");
        assertTrue(notes.contains("skipped:AnswerFollowUp"));
    }
}
