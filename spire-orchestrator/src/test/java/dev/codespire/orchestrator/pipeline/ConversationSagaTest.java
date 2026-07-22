package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
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

    @Test
    void mentionsBotIsCaseInsensitiveAndNeedsTheAtPrefix() {
        assertTrue(ConversationSaga.mentionsBot("hey @code-spire what about this?", "code-spire"));
        assertTrue(ConversationSaga.mentionsBot("HEY @Code-Spire", "code-spire"));
        assertFalse(ConversationSaga.mentionsBot("code-spire without the at sign", "code-spire"));
        assertFalse(ConversationSaga.mentionsBot("hi there", "code-spire"));
        assertFalse(ConversationSaga.mentionsBot("@code-spireworks contribute", "code-spire")); // word-bounded

    }

    @Test
    void mentionsBotNeverMatchesOnBlankOrNull() {
        assertFalse(ConversationSaga.mentionsBot("@ nobody", ""));
        assertFalse(ConversationSaga.mentionsBot("@bot", null));
        assertFalse(ConversationSaga.mentionsBot(null, "code-spire"));
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
        saga.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
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

        Optional<ActionCommand.AnswerFollowUp> cmd = saga.planFollowUp(topLevelReply("review::acme/web#412"));

        assertTrue(cmd.isPresent(), "a topLevel reply with a posted summary is answered");
        assertEquals("sum-1", cmd.get().threadRef().value(), "the command threads onto the SUMMARY comment");
        assertEquals("555", cmd.get().triggeringCommentId(), "the triggering id stays the author's own comment");
        assertFalse(notes.contains("skipped:AnswerFollowUp"));
    }

    @Test
    void blankBotAccountIdFailsClosedBeforeAnyThreadResolution() {
        List<String> notes = new ArrayList<>();
        List<String> details = new ArrayList<>();
        ConversationSaga saga = new ConversationSaga();
        saga.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
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

        Optional<ActionCommand.AnswerFollowUp> cmd = saga.planFollowUp(topLevelReply("review::acme/web#412"));

        assertTrue(cmd.isEmpty(), "blank botAccountId must fail closed with no command");
        assertTrue(notes.contains("skipped:AnswerFollowUp"));
        assertTrue(details.contains("bot identity unknown — re-save the provider to resolve it"));
    }

    @Test
    void topLevelReplyWithNoPostedSummaryIsSkippedWithATimelineNote() {
        List<String> notes = new ArrayList<>();
        ConversationSaga saga = new ConversationSaga();
        saga.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
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

        Optional<ActionCommand.AnswerFollowUp> cmd = saga.planFollowUp(topLevelReply("review::acme/web#412"));

        assertTrue(cmd.isEmpty(), "no posted summary to converse on -> no command");
        assertTrue(notes.contains("skipped:AnswerFollowUp"));
    }
}
