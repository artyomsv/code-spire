package dev.codespire.orchestrator.factory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A work item's run paid by an API key is paid with a key of its harness's own vendor (harness reviews 3b).
 *
 * <p>A plain unit test. Every collaborator {@link RunFunding#fund} reaches on the key path is a fake overriding the
 * method it reaches: left to the real one, each would open a database from a unit test.
 */
class RunFundingKeyKindTest {

    private final List<Optional<String>> kindsAsked = new ArrayList<>();

    private RunFunding funding() {
        HarnessCredentialPool pool = new HarnessCredentialPool() {
            @Override
            public Selection select(Optional<String> keyKind) {
                kindsAsked.add(keyKind);
                return new Selection.Chosen(new PoolMember(UUID.randomUUID(), "TEST-pool", keyKind.orElse("TEST"),
                        "https://TEST.invalid", "TEST-PLACEHOLDER"));
            }
        };
        return RunFundingFixtures.funding(new ArrayList<>(), pool);
    }

    private void fundOnAKey(String harness) {
        funding().fund(new RunFunding.Choice(harness, "TEST-model", null, false));
    }

    @Test
    void aCodexRunAsksForAnOpenAiKey() {
        fundOnAKey("codex");

        assertEquals(List.of(Optional.of("openai")), kindsAsked);
    }

    @Test
    void aClaudeCodeRunAsksForAnAnthropicKey() {
        fundOnAKey("claude_code");

        assertEquals(List.of(Optional.of("anthropic")), kindsAsked);
    }
}
