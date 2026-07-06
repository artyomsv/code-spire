package dev.codespire.worker.adapters;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-001 fail-fast for the LLM producer: a selected provider with missing
 * config refuses to start, naming the key. (SCM credentials are no longer read
 * from config — ADR-015 delivers them per command via the encrypted registry —
 * so there is no worker-side SCM producer to fail-fast; the KEK fail-fast lives
 * in spire-crypto's CryptoServiceTest.)
 */
class WorkerProducerFailFastTest {

    @Test
    void openAiCompatibleWithoutModelFailsNamingTheKey() {
        LlmProducer producer = new LlmProducer();
        producer.provider = "openai-compatible";
        producer.baseUrl = Optional.of("http://localhost:34999/v1");
        producer.apiKey = Optional.of("test-key");
        producer.model = Optional.empty();
        var thrown = assertThrows(IllegalStateException.class, producer::llmProvider);
        assertTrue(thrown.getMessage().contains("spire.llm.model"));
    }

    @Test
    void unknownLlmProviderFails() {
        LlmProducer producer = new LlmProducer();
        producer.provider = "anthropic-direct";
        var thrown = assertThrows(IllegalStateException.class, producer::llmProvider);
        assertTrue(thrown.getMessage().contains("Unknown spire.llm.provider"));
    }
}
