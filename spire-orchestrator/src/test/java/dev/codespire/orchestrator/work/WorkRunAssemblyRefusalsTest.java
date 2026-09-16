package dev.codespire.orchestrator.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which refusal a work item is allowed to keep.
 *
 * <p>The item's reason is durable and rendered on a page, so what reaches it is an allowlist rather
 * than "whatever the exception said". The first version matched the text before the first colon and
 * then stored the WHOLE message: every current producer emits a fixed literal, but a prefix check is
 * not a boundary — it is a boundary-shaped thing that the next producer walks through.
 */
class WorkRunAssemblyRefusalsTest {

    private static String reason(String message) {
        return WorkRunAssemblyRefusals.reasonOf(new IllegalStateException(message));
    }

    @Test
    void aKnownReasonWithNoPayloadIsKept() {
        assertEquals("factory_account_unavailable", reason("factory_account_unavailable"));
        assertEquals("harness_credential_unavailable", reason("harness_credential_unavailable"));
        assertEquals("model_disabled", reason("model_disabled"));
    }

    @Test
    void theOnePayloadIsKeptWhenEveryTypeIsARealTokenType() {
        assertEquals("model_pricing_incomplete:CACHED_INPUT,REASONING",
                reason("model_pricing_incomplete:CACHED_INPUT,REASONING"));
    }

    /** The discriminating cases: a prefix match would have stored every one of these unchanged. */
    @Test
    void anythingElseBecomesTheUnnamedReason() {
        assertEquals("build_configuration_unavailable", reason("model_pricing_incomplete:CACHED_INPUT:secret-suffix"));
        assertEquals("build_configuration_unavailable", reason("model_pricing_incomplete:not-a-token-type"));
        assertEquals("build_configuration_unavailable", reason("model_pricing_incomplete:"));
        assertEquals("build_configuration_unavailable", reason("factory_account_unavailable: forge said no"));
        assertEquals("build_configuration_unavailable", reason("connection refused to https://TEST.example/api"));
        assertEquals("build_configuration_unavailable", reason(""));
        assertEquals("build_configuration_unavailable", WorkRunAssemblyRefusals.reasonOf(new IllegalStateException()));
    }

    /** TOTAL is the unreconciled case and can never carry a rate, so it can never be a missing one. */
    @Test
    void theUnreconciledTotalIsNotAnAcceptablePayload() {
        assertEquals("build_configuration_unavailable", reason("model_pricing_incomplete:TOTAL"));
        assertEquals("build_configuration_unavailable", reason("model_pricing_incomplete:INPUT,TOTAL"));
    }
}
