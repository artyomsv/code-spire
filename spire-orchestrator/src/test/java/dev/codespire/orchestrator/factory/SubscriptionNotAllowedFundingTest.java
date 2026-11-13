package dev.codespire.orchestrator.factory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The funding door of "a subscription for Claude Code is refused at every door" (harness reviews 3b, Review Focus 2):
 * a stored choice reaches {@link RunFunding#fund} as it is, and a Claude subscription is refused before the image, the
 * price list, the spend gate and the seat are asked. A plain unit test; each collaborator is a fake that notes it was
 * reached ({@link RunFundingFixtures}), and the pool fails the test if a key is asked for.
 */
class SubscriptionNotAllowedFundingTest {

    private static final String CLAUDE = "claude_code";
    private static final String CODEX = "codex";
    private static final String NOT_ALLOWED = "subscription_not_allowed";

    private final List<String> reached = new ArrayList<>();

    private RunFunding funding() {
        HarnessCredentialPool pool = new HarnessCredentialPool() {
            @Override
            public Optional<PoolMember> selectSubscription(String harness) {
                reached.add("seat");
                return Optional.empty();
            }

            @Override
            public Selection select(Optional<String> keyKind) {
                throw new AssertionError("a subscription choice never asks for a key");
            }
        };
        return RunFundingFixtures.funding(reached, pool);
    }

    /** A hand-made dispatch, or a row stored before this change: the choice reaches fund as it is. */
    @Test
    void aStoredClaudeSubscriptionIsRefusedFirst() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> funding().fund(new RunFunding.Choice(CLAUDE, "TEST-model", null, true)));

        assertEquals(NOT_ALLOWED, refused.getMessage());
        assertEquals(List.of(), reached, "refused before the image, the price list, the spend gate and the seat");
    }

    @Test
    void aStoredCodexSubscriptionIsNotRefusedForIt() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> funding().fund(new RunFunding.Choice(CODEX, "TEST-model", null, true)));

        assertEquals("subscription_unavailable", refused.getMessage());
        assertEquals(List.of("admit", "spend", "seat"), reached);
    }
}
