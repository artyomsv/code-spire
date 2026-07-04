package dev.codespire.worker.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-001 fail-fast: a selected provider with missing credentials refuses to start, naming the key. */
class WorkerProducerFailFastTest {

    private WorkerScmProducer scm(String provider, Optional<String> appPassword) {
        WorkerScmProducer producer = new WorkerScmProducer();
        producer.provider = provider;
        producer.baseUrl = "https://api.bitbucket.org/2.0";
        producer.botUsername = Optional.of("bot");
        producer.botAppPassword = appPassword;
        producer.botAccountId = Optional.of("acc-1");
        producer.mapper = new ObjectMapper();
        return producer;
    }

    @Test
    void bitbucketProviderWithoutAppPasswordFailsNamingTheKey() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> scm("bitbucket-cloud", Optional.empty()).diffSource());
        assertTrue(thrown.getMessage().contains("spire.scm.bitbucket.bot-app-password"));
    }

    @Test
    void unknownScmProviderFails() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> scm("github", Optional.of("x")).commentSink());
        assertTrue(thrown.getMessage().contains("Unknown spire.scm.provider"));
    }

    @Test
    void stubScmNeedsNoCredentials() {
        WorkerScmProducer producer = new WorkerScmProducer();
        producer.provider = "stub";
        producer.botUsername = Optional.empty();
        producer.botAppPassword = Optional.empty();
        producer.botAccountId = Optional.empty();
        assertNotNull(producer.diffSource());
        assertNotNull(producer.commentSink());
    }

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
