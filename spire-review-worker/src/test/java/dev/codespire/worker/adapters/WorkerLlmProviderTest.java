package dev.codespire.worker.adapters;

import dev.codespire.contract.llm.LlmCredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Building the LLM client from a brokered credential (ADR-018). LLM config is no
 * longer read from env — the credential's type selects the model. The stub path
 * and the encrypted unpack are exercised by the worker's @QuarkusTest pipeline.
 */
class WorkerLlmProviderTest {

    @Test
    void buildsAnOpenAiClientFromTheCredential() {
        var cred = new LlmCredential("openai", "https://api.openai.com/v1", "sk-test", "gpt-4o", 0.2, 512);
        WorkerLlmProvider.LlmClient client = WorkerLlmProvider.clientFor(cred);
        assertNotNull(client.provider());
        assertEquals("gpt-4o", client.params().model());
        assertEquals(512, client.params().maxTokens());
        assertEquals(0.2, client.params().temperature());
    }

    @Test
    void rejectsAnUnsupportedProviderType() {
        var cred = new LlmCredential("mystery-llm", "https://x/v1", "k", "m", 0.2, null);
        var thrown = assertThrows(IllegalStateException.class, () -> WorkerLlmProvider.clientFor(cred));
        assertEquals("Unsupported LLM provider type: mystery-llm", thrown.getMessage());
    }
}
