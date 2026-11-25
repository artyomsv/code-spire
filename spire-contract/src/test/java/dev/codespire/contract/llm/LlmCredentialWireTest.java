package dev.codespire.contract.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The two input limits ride inside the encrypted credential. An orchestrator and a worker of different versions
 * must still agree on everything else in it, in both directions.
 */
class LlmCredentialWireTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The shape an older worker decodes into. */
    record OldLlmCredential(String type, String baseUrl, String apiKey, String model,
                            double temperature, Integer maxTokens, ModelParamProfile profile) {
    }

    @Test
    void anOldCredentialDecodesWithNoLimits() throws Exception {
        String legacy = mapper.writeValueAsString(new OldLlmCredential("openai", "https://llm.example.invalid",
                "TEST-key", "TEST-model", 0.2, 2048, ModelParamProfile.legacyDefault()));

        LlmCredential back = mapper.readValue(legacy, LlmCredential.class);

        assertNull(back.maxInputTokens(), "unknown, so the worker keeps today's budgets");
        assertNull(back.diffTokenCap());
        assertEquals(2048, back.maxTokens());
    }

    @Test
    void aNewCredentialDecodesIntoTheOldShape() throws Exception {
        LlmCredential current = new LlmCredential("openai", "https://llm.example.invalid", "TEST-key", "TEST-model",
                0.2, 2048, ModelParamProfile.legacyDefault(), 983_616, 90_000);

        OldLlmCredential old = mapper.readValue(mapper.writeValueAsString(current), OldLlmCredential.class);

        assertEquals("TEST-model", old.model());
        assertEquals(2048, old.maxTokens());
    }

    @Test
    void theLimitsRoundTrip() throws Exception {
        LlmCredential current = new LlmCredential("openai", "https://llm.example.invalid", "TEST-key", "TEST-model",
                0.2, null, ModelParamProfile.legacyDefault(), 983_616, 90_000);

        LlmCredential back = mapper.readValue(mapper.writeValueAsString(current), LlmCredential.class);

        assertEquals(983_616, back.maxInputTokens());
        assertEquals(90_000, back.diffTokenCap());
    }
}
