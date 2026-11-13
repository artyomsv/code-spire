package dev.codespire.orchestrator.factory;

import jakarta.ws.rs.ClientErrorException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run dispatched through {@code POST /api/runs} is paid with a key of its harness's own vendor (harness reviews 3b).
 *
 * <p>A plain unit test: the pool is a fake whose only overridden method is the one this path reaches, so nothing
 * opens a database.
 */
class RunResourceKeyKindTest {

    private final List<Optional<String>> kindsAsked = new ArrayList<>();
    private boolean emptyPool;

    private RunResource resource() {
        RunResource resource = new RunResource();
        resource.pool = new HarnessCredentialPool() {
            @Override
            public Selection select(Optional<String> keyKind) {
                kindsAsked.add(keyKind);
                if (emptyPool) {
                    return new Selection.Empty();
                }
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

    /** An empty pool of the harness's kind names that kind: keys of another vendor may exist and do not pay it. */
    @Test
    void anEmptyPoolOfItsKindNamesTheKind() {
        emptyPool = true;

        ClientErrorException refused = assertThrows(ClientErrorException.class,
                () -> resource().harnessCredential("claude_code"));

        assertEquals(409, refused.getResponse().getStatus());
        String why = String.valueOf(refused.getResponse().getEntity());
        assertTrue(why.startsWith("No usable `anthropic` API key is configured for the claude_code harness"), why);
        assertTrue(why.contains("a key of another vendor does not pay it"), why);
    }
}
