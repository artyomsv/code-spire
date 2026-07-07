package dev.codespire.worker.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.llm.LlmCredential;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.llm.ModelParamProfile.OutputTokenParam;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void clientForCarriesTheModelParameterProfile() {
        var profile = new ModelParamProfile(OutputTokenParam.MAX_COMPLETION_TOKENS, false, "medium", Map.of());
        var cred = new LlmCredential("openai", "https://api.openai.com/v1", "sk", "o3", 0.2, 256, profile);
        WorkerLlmProvider.LlmClient client = WorkerLlmProvider.clientFor(cred);
        assertEquals(OutputTokenParam.MAX_COMPLETION_TOKENS, client.params().profile().outputTokenParam());
        assertFalse(client.params().profile().supportsTemperature());
    }

    @Test
    void credentialProfileSurvivesTheJsonWireRoundTrip() throws Exception {
        // The credential is JSON-serialized, encrypted, brokered, then read back in the
        // worker. If the nested profile record didn't survive, it would normalize back to
        // the legacy MAX_TOKENS dialect and silently resend max_tokens.
        var mapper = new ObjectMapper();
        var profile = new ModelParamProfile(
                OutputTokenParam.MAX_COMPLETION_TOKENS, false, "medium", Map.of("service_tier", "flex"));
        var cred = new LlmCredential("openai", "https://api.openai.com/v1", "sk", "o3", 0.2, 256, profile);

        LlmCredential back = mapper.readValue(mapper.writeValueAsString(cred), LlmCredential.class);
        assertEquals(OutputTokenParam.MAX_COMPLETION_TOKENS, back.profile().outputTokenParam());
        assertFalse(back.profile().supportsTemperature());
        assertEquals("medium", back.profile().reasoningEffort());
        assertEquals("flex", back.profile().extraParams().get("service_tier"));
    }

    @Test
    void rejectsAnUnsupportedProviderType() {
        var cred = new LlmCredential("mystery-llm", "https://x/v1", "k", "m", 0.2, null);
        var thrown = assertThrows(IllegalStateException.class, () -> WorkerLlmProvider.clientFor(cred));
        assertEquals("Unsupported LLM provider type: mystery-llm", thrown.getMessage());
    }
}
