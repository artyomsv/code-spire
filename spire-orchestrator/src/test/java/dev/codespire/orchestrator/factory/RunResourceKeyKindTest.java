package dev.codespire.orchestrator.factory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A run dispatched through {@code POST /api/runs} is paid with a key of its harness's own vendor (harness reviews 3b).
 *
 * <p>A plain unit test: the pool is a fake whose only overridden method is the one this path reaches, so nothing
 * opens a database.
 */
class RunResourceKeyKindTest {

    private final List<Optional<String>> kindsAsked = new ArrayList<>();

    private RunResource resource() {
        RunResource resource = new RunResource();
        resource.pool = new HarnessCredentialPool() {
            @Override
            public Selection select(Optional<String> keyKind) {
                kindsAsked.add(keyKind);
                return new Selection.Chosen(new PoolMember(UUID.randomUUID(), "TEST-pool", keyKind.orElse("TEST"),
                        "https://TEST.invalid", "TEST-PLACEHOLDER"));
            }
        };
        return resource;
    }

    @Test
    void aCodexRunAsksForAnOpenAiKey() {
        resource().harnessCredential("codex");

        assertEquals(List.of(Optional.of("openai")), kindsAsked);
    }

    @Test
    void aClaudeCodeRunAsksForAnAnthropicKey() {
        resource().harnessCredential("claude_code");

        assertEquals(List.of(Optional.of("anthropic")), kindsAsked);
    }
}
