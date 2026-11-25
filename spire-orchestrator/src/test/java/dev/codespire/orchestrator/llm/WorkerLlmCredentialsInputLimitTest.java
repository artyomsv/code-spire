package dev.codespire.orchestrator.llm;

import dev.codespire.contract.llm.ModelParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The input a review may send: the catalog window minus the output cap the call really sends. */
class WorkerLlmCredentialsInputLimitTest {

    @Test
    void anUnknownWindowIsUnknown() {
        assertNull(WorkerLlmCredentials.maxInputTokens(null, 4_096), "a manual model keeps today's budgets");
    }

    @Test
    void theProvidersOutputCapIsReserved() {
        assertEquals(1_000_000 - 4_096, WorkerLlmCredentials.maxInputTokens(1_000_000L, 4_096));
    }

    @Test
    void withoutAProviderCapTheDefaultOutputCapIsReserved() {
        assertEquals(1_000_000 - 16_384, WorkerLlmCredentials.maxInputTokens(1_000_000L, null));
        assertEquals(16_384, ModelParams.DEFAULT_MAX_OUTPUT_TOKENS, "the cap the provider sends when none is set");
    }

    @Test
    void aTwoMillionWindowDoesNotOverflow() {
        assertEquals(2_000_000 - 16_384, WorkerLlmCredentials.maxInputTokens(2_000_000L, null));
    }

    @Test
    void aWindowPastIntIsClampedRatherThanWrapped() {
        assertEquals(Integer.MAX_VALUE, WorkerLlmCredentials.maxInputTokens(Long.MAX_VALUE, null));
    }

    @Test
    void aWindowSmallerThanTheOutputCapLeavesNoInputRatherThanANegativeOne() {
        assertEquals(0, WorkerLlmCredentials.maxInputTokens(8_000L, null));
    }
}
