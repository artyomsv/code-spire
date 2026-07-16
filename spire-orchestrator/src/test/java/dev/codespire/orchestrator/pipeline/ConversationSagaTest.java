package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.scm.Author;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic coverage for the new scope-B (@-mention) and allowlist gates. Full planFollowUp choreography
 * is covered by the deferred @QuarkusTest E2E. */
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
}
