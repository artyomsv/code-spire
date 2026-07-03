package dev.codespire.orchestrator.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-001 fail-fast: a selected provider with missing credentials/model must
 * refuse to start, naming the exact missing key — no silent defaults.
 */
class ProducerFailFastTest {

    private ScmProducer scm(String provider, Optional<String> appPassword) {
        ScmProducer producer = new ScmProducer();
        producer.provider = provider;
        producer.baseUrl = "https://api.bitbucket.org/2.0";
        producer.botUsername = Optional.of("bot");
        producer.botAppPassword = appPassword;
        producer.webhookSecret = Optional.of("secret");
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
    void blankCredentialIsRejectedLikeMissing() {
        assertThrows(IllegalStateException.class,
                () -> scm("bitbucket-cloud", Optional.of("  ")).commentSink());
    }

    @Test
    void unknownScmProviderFails() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> scm("github", Optional.of("x")).ingress());
        assertTrue(thrown.getMessage().contains("Unknown spire.scm.provider"));
    }

    @Test
    void stubScmNeedsNoCredentials() {
        ScmProducer producer = new ScmProducer();
        producer.provider = "stub";
        producer.botUsername = Optional.empty();
        producer.botAppPassword = Optional.empty();
        producer.webhookSecret = Optional.empty();
        producer.botAccountId = Optional.empty();
        assertNotNull(producer.diffSource());
        assertNotNull(producer.commentSink());
        assertNotNull(producer.ingress());
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

    @Test
    void stubLlmProducesCannedSelfLabeledOutput() throws Exception {
        LlmProducer producer = new LlmProducer();
        producer.provider = "stub";
        var completion = producer.llmProvider()
                .complete(new dev.codespire.contract.llm.Prompt("s", "u"),
                        new dev.codespire.contract.llm.ModelParams("stub-model", 0, null))
                .toCompletableFuture().get();
        assertTrue(completion.text().contains("STUB"));
        assertEquals(0, completion.usage().costMillicents());
    }
}
