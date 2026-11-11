package dev.codespire.orchestrator.factory;

import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
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
        RunFunding funding = new RunFunding();
        funding.models = new LlmModelRegistry() {
            @Override
            public boolean isDisabled(String model) {
                return false;
            }
        };
        funding.catalogues = new HarnessCatalogues() {
            @Override
            public Admission admit(String harness, String model, String effort) {
                return new Admission(null, "TEST-image");
            }
        };
        funding.pricer = new LlmModelPricer() {
            @Override
            public List<TokenType> unpricedTypes(String model, String harness) {
                return List.of();
            }
        };
        funding.spend = new SpendGate() {
            @Override
            public Decision decide() {
                return Decision.of(CapRefusal.allow());
            }
        };
        funding.pool = new HarnessCredentialPool() {
            @Override
            public Selection select(Optional<String> keyKind) {
                kindsAsked.add(keyKind);
                return new Selection.Chosen(new PoolMember(UUID.randomUUID(), "TEST-pool", keyKind.orElse("TEST"),
                        "https://TEST.invalid", "TEST-PLACEHOLDER"));
            }
        };
        return funding;
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
